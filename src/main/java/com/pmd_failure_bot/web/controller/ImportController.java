package com.pmd_failure_bot.web.controller;

import com.pmd_failure_bot.web.dto.request.LogImportRequest;
import com.pmd_failure_bot.web.dto.response.LogImportResponse;
import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.service.imports.LogImportService;
import com.pmd_failure_bot.integration.salesforce.SalesforceService;
import com.pmd_failure_bot.service.analysis.NaturalLanguageProcessingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Logger logger = LoggerFactory.getLogger(ImportController.class);

    private final NaturalLanguageProcessingService nlpService;
    private final SalesforceService salesforceService;
    private final LogImportService logImportService;

    @Autowired
    public ImportController(NaturalLanguageProcessingService nlpService,
                            SalesforceService salesforceService,
                            LogImportService logImportService) {
        this.nlpService = nlpService;
        this.salesforceService = salesforceService;
        this.logImportService = logImportService;
    }

    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processImportRequest(@RequestBody Map<String, Object> body) {
        long startTime = System.currentTimeMillis();
        String conversationId = UUID.randomUUID().toString();
        try {
            String queryText = body != null && body.get("query") instanceof String ? (String) body.get("query") : null;
            logger.info("Processing import request: '{}' (conversation: {})", queryText, conversationId);

            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult =
                nlpService.extractParameters(queryText != null ? queryText : "", null);

            return handleImportRequest(queryText, extractionResult, conversationId, startTime);
        } catch (Exception e) {
            logger.error("Error processing import request: ", e);
            NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                "I encountered an error while processing your import request. Please try again later or contact support if the issue persists.",
                new QueryRequest(),
                List.of(),
                0.0,
                LocalDateTime.now(),
                System.currentTimeMillis() - startTime
            );
            errorResponse.setConversationId(conversationId);
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    private ResponseEntity<NaturalLanguageQueryResponse> handleImportRequest(String originalQuery,
                                                                             NaturalLanguageProcessingService.ParameterExtractionResult extractionResult,
                                                                             String conversationId,
                                                                             long startTime) {
        try {
            QueryRequest structuredQuery = extractionResult.getQueryRequest();

            LogImportRequest importRequest = new LogImportRequest();
            importRequest.setCaseNumber(structuredQuery.getCaseNumber());
            importRequest.setStepName(structuredQuery.getStepName());

            if (importRequest.getCaseNumber() == null && (importRequest.getStepName() == null || importRequest.getStepName().trim().isEmpty())) {
                NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                    "I need either a case number or step name to import logs. For example: 'Import logs for case 123456' or 'Pull logs from SSH_TO_ALL_HOSTS step'",
                    structuredQuery,
                    List.of(),
                    extractionResult.getConfidence(),
                    LocalDateTime.now(),
                    System.currentTimeMillis() - startTime
                );
                errorResponse.setConversationId(conversationId);
                return ResponseEntity.badRequest().body(errorResponse);
            }

            String searchCriteria;
            if (importRequest.getCaseNumber() != null) {
                searchCriteria = "case " + importRequest.getCaseNumber();
            } else {
                searchCriteria = "step " + importRequest.getStepName();
            }

            String importStatus;
            try {
                java.util.List<java.util.Map<String, Object>> salesforceRecords;
                if (importRequest.getCaseNumber() != null) {
                    salesforceRecords = salesforceService.queryFailedAttachmentsByCaseNumber(importRequest.getCaseNumber());
                } else {
                    salesforceRecords = salesforceService.queryFailedStepAttachments(importRequest.getStepName());
                }

                if (salesforceRecords.isEmpty()) {
                    importStatus = String.format("No failed attachments found for %s. There may be no failures to import, or they might already be processed.", searchCriteria);
                } else {
                    ResponseEntity<LogImportResponse> importResponseEntity = logImportService.importLogs(importRequest);
                    LogImportResponse importResponse = importResponseEntity.getBody();
                    int totalAttachments = importResponse.getTotalAttachments();
                    int processedAttachments = importResponse.getProcessedAttachments();
                    int skippedAttachments = importResponse.getSkippedAttachments();
                    int successfulLogs = importResponse.getSuccessfulLogs();
                    int failedLogs = importResponse.getFailedLogs();
                    importStatus = String.format(
                        "✅ Import completed for %s!\n📊 Processed %d/%d attachments (%d skipped)\n📝 Imported %d logs successfully (%d failed)\n💡 You can now query with: 'What issues occurred in %s?'",
                        searchCriteria, processedAttachments, totalAttachments, skippedAttachments, successfulLogs, failedLogs, searchCriteria
                    );
                }
            } catch (Exception importException) {
                logger.error("Error during actual import for {}: ", searchCriteria, importException);
                importStatus = String.format("❌ Import failed for %s: %s\nPlease try again or contact support.", searchCriteria, importException.getMessage());
            }

            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.info("Successfully processed import request for {} in {}ms (conversation: {})", searchCriteria, executionTimeMs, conversationId);

            NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(
                importStatus,
                structuredQuery,
                List.of(),
                extractionResult.getConfidence(),
                LocalDateTime.now(),
                executionTimeMs
            );
            response.setConversationId(conversationId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing import request (conversation: {}): ", conversationId, e);
            NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                "I encountered an error while processing your import request. Please try again later or contact support if the issue persists.",
                new QueryRequest(),
                List.of(),
                0.0,
                LocalDateTime.now(),
                System.currentTimeMillis() - startTime
            );
            errorResponse.setConversationId(conversationId);
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}


