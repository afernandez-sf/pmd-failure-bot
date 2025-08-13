package com.pmd_failure_bot.domain.imports;

import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.infrastructure.salesforce.SalesforceService;
import com.pmd_failure_bot.repository.PmdReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Service for importing logs from Salesforce attachments
 */
@Service
public class LogImportService {

    private final SalesforceService salesforceService;
    private final LogProcessingService logProcessingService;
    private final PmdReportRepository pmdReportRepository;

    @Autowired
    public LogImportService(SalesforceService salesforceService, 
                          LogProcessingService logProcessingService,
                          PmdReportRepository pmdReportRepository) {
        this.salesforceService = salesforceService;
        this.logProcessingService = logProcessingService;
        this.pmdReportRepository = pmdReportRepository;
    }
    
    /**
     * Import logs based on specified criteria
     */
    public ResponseEntity<LogImportResponse> importLogs(LogImportRequest request) {
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
                            
                            // Combine Salesforce record metadata with attachment-specific metadata
                            Map<String, Object> combinedMetadata = new HashMap<>(salesforceMetadata);
                            combinedMetadata.put("attachment_last_modified_date", attachment.get("LastModifiedDate"));
                            
                            attachments.add(new AttachmentInfo(
                                (String) attachment.get("Id"),
                                (String) attachment.get("Name"),
                                (String) record.get("Id"),
                                stepName,
                                combinedMetadata
                            ));
                        }
                    }
                }
            }
            
            // Pre-compute already processed attachments to skip early
            List<String> attachmentIds = attachments.stream()
                .map(a -> a.attachmentId)
                .collect(Collectors.toList());
            Set<String> alreadyProcessedIds = new HashSet<>(
                pmdReportRepository.findByAttachmentIdIn(attachmentIds).stream()
                    .map(r -> r.getAttachmentId())
                    .collect(Collectors.toSet())
            );

            List<LogImportResponse.ProcessedRecord> processedRecords = new ArrayList<>();
            int totalAttachments = attachments.size();
            int processedAttachments = 0;
            int skippedAttachments = 0;
            int successfulLogs = 0;
            int failedLogs = 0;

            // Add skipped records immediately
            List<AttachmentInfo> attachmentsToProcess = new ArrayList<>();
            for (AttachmentInfo attachment : attachments) {
                if (alreadyProcessedIds.contains(attachment.attachmentId)) {
                    skippedAttachments++;
                    processedRecords.add(new LogImportResponse.ProcessedRecord(
                        attachment.attachmentId,
                        attachment.attachmentName,
                        attachment.recordId,
                        (String) attachment.salesforceMetadata.get("work_id"),
                        (Integer) attachment.salesforceMetadata.get("case_number"),
                        attachment.stepName,
                        0,
                        "SKIPPED",
                        "Attachment already processed"
                    ));
                } else {
                    attachmentsToProcess.add(attachment);
                }
            }

            // Process remaining attachments in parallel
            int parallelism = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            ExecutorService executor = Executors.newFixedThreadPool(parallelism);
            List<Future<LogProcessingService.ProcessingResult>> futures = new ArrayList<>();

            for (AttachmentInfo attachment : attachmentsToProcess) {
                Callable<LogProcessingService.ProcessingResult> task = () ->
                    logProcessingService.processAttachment(
                        attachment.attachmentId,
                        attachment.attachmentName,
                        attachment.recordId,
                        attachment.stepName,
                        attachment.salesforceMetadata,
                        salesforceService
                    );
                futures.add(executor.submit(task));
            }

            for (int i = 0; i < attachmentsToProcess.size(); i++) {
                AttachmentInfo attachment = attachmentsToProcess.get(i);
                try {
                    LogProcessingService.ProcessingResult result = futures.get(i).get();
                    processedRecords.add(new LogImportResponse.ProcessedRecord(
                        result.attachmentId,
                        result.attachmentName,
                        result.recordId,
                        (String) attachment.salesforceMetadata.get("work_id"),
                        (Integer) attachment.salesforceMetadata.get("case_number"),
                        result.stepName,
                        result.logsProcessed,
                        result.status,
                        result.message
                    ));

                    if ("SKIPPED".equals(result.status)) {
                        skippedAttachments++;
                    } else {
                        processedAttachments++;
                    }
                    successfulLogs += result.logsProcessed;
                    failedLogs += result.logsFailed;
                } catch (Exception e) {
                    processedRecords.add(new LogImportResponse.ProcessedRecord(
                        attachment.attachmentId,
                        attachment.attachmentName,
                        attachment.recordId,
                        (String) attachment.salesforceMetadata.get("work_id"),
                        (Integer) attachment.salesforceMetadata.get("case_number"),
                        attachment.stepName,
                        0,
                        "FAILED",
                        "Processing error: " + e.getMessage()
                    ));
                    failedLogs++;
                }
            }

            executor.shutdown();
            
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
    
    /**
     * Extract step name from the subject field of a Salesforce record
     */
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
    
    /**
     * Helper class to store attachment information
     */
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