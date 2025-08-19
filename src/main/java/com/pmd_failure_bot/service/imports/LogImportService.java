package com.pmd_failure_bot.service.imports;

import com.pmd_failure_bot.web.dto.request.LogImportRequest;
import com.pmd_failure_bot.web.dto.response.LogImportResponse;
import com.pmd_failure_bot.data.repository.PmdReportRepository;
import com.pmd_failure_bot.integration.salesforce.SalesforceService;
import com.pmd_failure_bot.common.util.StepNameNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogImportService {

    private static final int MIN_THREAD_POOL_SIZE = 2;
    private static final int MAX_THREAD_POOL_SIZE = 8;

    private final SalesforceService salesforceService;
    private final LogProcessingService logProcessingService;
    private final PmdReportRepository pmdReportRepository;
    private final StepNameNormalizer stepNameNormalizer;

    public ResponseEntity<LogImportResponse> importLogs(LogImportRequest request) {
        log.info("Starting log import for request: {}", request);
        
        try {
            validateRequestOrThrow(request);
            String searchCriteria = buildSearchCriteria(request);
            List<Map<String, Object>> salesforceRecords = fetchSalesforceRecords(request);
            
            if (salesforceRecords.isEmpty()) {
                log.info("No failed attachments found for {}", searchCriteria);
                return ResponseEntity.ok(new LogImportResponse(
                    "No failed attachments found for " + searchCriteria,
                    0, 0, 0, 0, 0, List.of()
                ));
            }
            
            List<AttachmentInfo> attachments = collectAttachments(request, salesforceRecords);
            Set<String> alreadyProcessedIds = computeAlreadyProcessedIds(attachments);
            ExecutionAggregation aggregation = processAttachments(attachments, alreadyProcessedIds);
            LogImportResponse response = buildResponse(searchCriteria, aggregation);
            
            log.info("Log import completed successfully for {}: {} total attachments, {} processed", 
                    searchCriteria, aggregation.totalAttachments, aggregation.processedAttachments);
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid request for log import: {}", e.getMessage());
            LogImportResponse errorResponse = new LogImportResponse(
                "Error: " + e.getMessage(),
                0, 0, 0, 0, 0, List.of()
            );
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            log.error("Internal server error during log import", e);
            LogImportResponse errorResponse = new LogImportResponse(
                "Internal server error: " + e.getMessage(),
                0, 0, 0, 0, 0, List.of()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    private void validateRequestOrThrow(LogImportRequest request) {
        if (!request.isValid()) {
            throw new IllegalArgumentException("Either case_number or step_name must be provided");
        }
    }

    private String buildSearchCriteria(LogImportRequest request) {
        if (request.getCaseNumber() != null) {
            return "case number: " + request.getCaseNumber();
        }
        return "step name: " + request.getStepName();
    }

    private List<Map<String, Object>> fetchSalesforceRecords(LogImportRequest request) throws Exception {
        if (request.getCaseNumber() != null) {
            return salesforceService.queryFailedAttachmentsByCaseNumber(request.getCaseNumber());
        }
        return salesforceService.queryFailedStepAttachments(request.getStepName());
    }

    private List<AttachmentInfo> collectAttachments(LogImportRequest request, List<Map<String, Object>> salesforceRecords) {
        List<AttachmentInfo> attachments = new ArrayList<>();
        for (Map<String, Object> record : salesforceRecords) {
            Map<String, Object> salesforceMetadata = salesforceService.extractSalesforceMetadata(record);
            @SuppressWarnings("unchecked")
            Map<String, Object> attachmentsData = (Map<String, Object>) record.get("Attachments");
            if (attachmentsData == null) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachmentRecords = (List<Map<String, Object>>) attachmentsData.get("records");
            if (attachmentRecords == null) continue;
            for (Map<String, Object> attachment : attachmentRecords) {
                String stepName = request.getStepName() != null ? request.getStepName() : extractStepNameFromSubject(record);
                if (stepName != null) {
                    stepName = stepNameNormalizer.normalize(stepName);
                }
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
        return attachments;
    }

    private Set<String> computeAlreadyProcessedIds(List<AttachmentInfo> attachments) {
        List<String> attachmentIds = attachments.stream()
                .map(AttachmentInfo::attachmentId)
                .collect(Collectors.toList());
        return Set.copyOf(pmdReportRepository.findExistingAttachmentIds(attachmentIds));
    }

    private ExecutionAggregation processAttachments(List<AttachmentInfo> attachments, Set<String> alreadyProcessedIds) throws Exception {
        List<LogImportResponse.ProcessedRecord> processedRecords = new ArrayList<>();
        int processedAttachments = 0;
        int skippedAttachments = 0;
        int successfulLogs = 0;
        int failedLogs = 0;
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
        int parallelism = Math.max(MIN_THREAD_POOL_SIZE, Math.min(MAX_THREAD_POOL_SIZE, Runtime.getRuntime().availableProcessors()));
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
                if ("SKIPPED".equals(result.status)) { skippedAttachments++; } else { processedAttachments++; }
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
        ExecutionAggregation agg = new ExecutionAggregation();
        agg.totalAttachments = attachments.size();
        agg.processedAttachments = processedAttachments;
        agg.skippedAttachments = skippedAttachments;
        agg.successfulLogs = successfulLogs;
        agg.failedLogs = failedLogs;
        agg.processedRecords = processedRecords;
        return agg;
    }

    private LogImportResponse buildResponse(String searchCriteria, ExecutionAggregation agg) {
        String message = String.format(
            "Import completed for %s. Processed %d attachments, skipped %d, imported %d logs successfully, %d failed.",
            searchCriteria, agg.processedAttachments, agg.skippedAttachments, agg.successfulLogs, agg.failedLogs
        );
        return new LogImportResponse(
            message,
            agg.totalAttachments,
            agg.processedAttachments,
            agg.skippedAttachments,
            agg.successfulLogs,
            agg.failedLogs,
            agg.processedRecords
        );
    }

    private static class ExecutionAggregation {
        int totalAttachments;
        int processedAttachments;
        int skippedAttachments;
        int successfulLogs;
        int failedLogs;
        List<LogImportResponse.ProcessedRecord> processedRecords;
    }

    private String extractStepNameFromSubject(Map<String, Object> record) {
        String subject = (String) record.get("WorkId_and_Subject__c");
        if (subject != null) {
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

    private record AttachmentInfo(String attachmentId, String attachmentName, String recordId, String stepName, Map<String, Object> salesforceMetadata) {}
}


