package com.pmd_failure_bot.controller;

import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.service.LogProcessingService;
import com.pmd_failure_bot.service.QueryService;
import com.pmd_failure_bot.service.SalesforceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;
    private final SalesforceService salesforceService;
    private final LogProcessingService logProcessingService;

    @Autowired
    public QueryController(QueryService queryService, SalesforceService salesforceService, 
                          LogProcessingService logProcessingService) {
        this.queryService = queryService;
        this.salesforceService = salesforceService;
        this.logProcessingService = logProcessingService;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> processQuery(@RequestBody QueryRequest request) {
        try {
            QueryResponse response = queryService.processQuery(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            QueryResponse errorResponse = new QueryResponse(
                "Error: " + e.getMessage(),
                List.of(),
                LocalDateTime.now(),
                0L
            );
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            QueryResponse errorResponse = new QueryResponse(
                "Internal server error: " + e.getMessage(),
                List.of(),
                LocalDateTime.now(),
                0L
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    @PostMapping("/import-logs")
    public ResponseEntity<LogImportResponse> importLogs(@RequestBody LogImportRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Validate request
            if (!request.isValid()) {
                LogImportResponse errorResponse = new LogImportResponse(
                    "Error: Either case_number or step_name must be provided",
                    0, 0, 0, 0, 0, List.of(), 0L
                );
                return ResponseEntity.badRequest().body(errorResponse);
            }
            
            List<Map<String, Object>> salesforceRecords = new ArrayList<>();
            String searchCriteria;
            
            // Query Salesforce based on criteria
            if (request.getCaseNumber() != null) {
                searchCriteria = "case number: " + request.getCaseNumber();
                salesforceRecords = salesforceService.queryFailedAttachmentsByCaseNumber(request.getCaseNumber());
            } else {
                searchCriteria = "step name: " + request.getStepName();
                salesforceRecords = salesforceService.queryFailedStepAttachments(request.getStepName());
            }
            
            if (salesforceRecords.isEmpty()) {
                LogImportResponse response = new LogImportResponse(
                    "No failed attachments found for " + searchCriteria,
                    0, 0, 0, 0, 0, List.of(), System.currentTimeMillis() - startTime
                );
                return ResponseEntity.ok(response);
            }
            
            // Collect all attachments to process
            List<AttachmentInfo> attachments = new ArrayList<>();
            for (Map<String, Object> record : salesforceRecords) {
                Map<String, Object> salesforceMetadata = salesforceService.extractSalesforceMetadata(record);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> attachmentsData = (Map<String, Object>) record.get("Attachments");
                if (attachmentsData != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> attachmentRecords = (List<Map<String, Object>>) attachmentsData.get("records");
                    if (attachmentRecords != null) {
                        for (Map<String, Object> attachment : attachmentRecords) {
                            String stepName = request.getStepName() != null ? 
                                request.getStepName() : extractStepNameFromSubject(record);
                            
                            attachments.add(new AttachmentInfo(
                                (String) attachment.get("Id"),
                                (String) attachment.get("Name"),
                                (String) record.get("Id"),
                                stepName,
                                salesforceMetadata
                            ));
                        }
                    }
                }
            }
            
            // Process attachments
            List<LogImportResponse.ProcessedRecord> processedRecords = new ArrayList<>();
            int totalAttachments = attachments.size();
            int processedAttachments = 0;
            int skippedAttachments = 0;
            int successfulLogs = 0;
            int failedLogs = 0;
            
            for (AttachmentInfo attachment : attachments) {
                LogProcessingService.ProcessingResult result = logProcessingService.processAttachment(
                    attachment.attachmentId,
                    attachment.attachmentName,
                    attachment.recordId,
                    attachment.stepName,
                    attachment.salesforceMetadata,
                    salesforceService
                );
                
                LogImportResponse.ProcessedRecord processedRecord = new LogImportResponse.ProcessedRecord(
                    result.attachmentId,
                    result.attachmentName,
                    result.recordId,
                    (String) attachment.salesforceMetadata.get("work_id"),
                    (Integer) attachment.salesforceMetadata.get("case_number"),
                    result.stepName,
                    result.logsProcessed,
                    result.status,
                    result.message
                );
                processedRecords.add(processedRecord);
                
                if ("SKIPPED".equals(result.status)) {
                    skippedAttachments++;
                } else {
                    processedAttachments++;
                }
                
                successfulLogs += result.logsProcessed;
                failedLogs += result.logsFailed;
            }
            
            long executionTime = System.currentTimeMillis() - startTime;
            String message = String.format(
                "Import completed for %s. Processed %d attachments, skipped %d, " +
                "imported %d logs successfully, %d failed.",
                searchCriteria, processedAttachments, skippedAttachments, successfulLogs, failedLogs
            );
            
            LogImportResponse response = new LogImportResponse(
                message, totalAttachments, processedAttachments, skippedAttachments,
                successfulLogs, failedLogs, processedRecords, executionTime
            );
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            LogImportResponse errorResponse = new LogImportResponse(
                "Error: " + e.getMessage(),
                0, 0, 0, 0, 0, List.of(), System.currentTimeMillis() - startTime
            );
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            LogImportResponse errorResponse = new LogImportResponse(
                "Internal server error: " + e.getMessage(),
                0, 0, 0, 0, 0, List.of(), System.currentTimeMillis() - startTime
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    private String extractStepNameFromSubject(Map<String, Object> record) {
        String subject = (String) record.get("WorkId_and_Subject__c");
        if (subject != null) {
            // Extract step name from subject like "Step: SSH_TO_ALL_HOSTS_CS310, Status: FAILED"
            int stepIndex = subject.indexOf("Step:");
            if (stepIndex >= 0) {
                String stepPart = subject.substring(stepIndex + 5);
                int commaIndex = stepPart.indexOf(",");
                if (commaIndex >= 0) {
                    return stepPart.substring(0, commaIndex).trim();
                }
                return stepPart.trim();
            }
        }
        return "UNKNOWN_STEP";
    }
    
    private static class AttachmentInfo {
        final String attachmentId;
        final String attachmentName;
        final String recordId;
        final String stepName;
        final Map<String, Object> salesforceMetadata;
        
        AttachmentInfo(String attachmentId, String attachmentName, String recordId, 
                      String stepName, Map<String, Object> salesforceMetadata) {
            this.attachmentId = attachmentId;
            this.attachmentName = attachmentName;
            this.recordId = recordId;
            this.stepName = stepName;
            this.salesforceMetadata = salesforceMetadata;
        }
    }
}