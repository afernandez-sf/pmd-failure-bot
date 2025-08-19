package com.pmd_failure_bot.web.controller;

import com.pmd_failure_bot.web.dto.request.LogImportRequest;
import com.pmd_failure_bot.web.dto.response.LogImportResponse;
import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.service.imports.LogImportService;
import com.pmd_failure_bot.integration.salesforce.SalesforceService;
import com.pmd_failure_bot.service.analysis.NaturalLanguageProcessingService;
import com.pmd_failure_bot.common.constants.ErrorMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@Slf4j
public class ImportController {

    private final NaturalLanguageProcessingService nlpService;
    private final SalesforceService salesforceService;
    private final LogImportService logImportService;

    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processImportRequest(@RequestBody Map<String, Object> body) {
        String conversationId = UUID.randomUUID().toString();
        try {
            String queryText = extractQueryText(body);
            log.info("Processing import request: '{}' (conversation: {})", queryText, conversationId);
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = nlpService.extractParameters(queryText != null ? queryText : "", null);
            return handleImportRequest(extractionResult, conversationId);
        } catch (Exception e) {
            log.error("Error processing import request: ", e);
            return createErrorResponse(ErrorMessages.IMPORT_ERROR_MESSAGE, conversationId);
        }
    }

    private String extractQueryText(Map<String, Object> body) {
        return body != null && body.get("query") instanceof String ? (String) body.get("query") : null;
    }

    private ResponseEntity<NaturalLanguageQueryResponse> createErrorResponse(String message, String conversationId) {
        NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(message, new QueryRequest(), List.of(), 0.0);
        errorResponse.setConversationId(conversationId);
        return ResponseEntity.internalServerError().body(errorResponse);
    }

    private ResponseEntity<NaturalLanguageQueryResponse> handleImportRequest(NaturalLanguageProcessingService.ParameterExtractionResult extractionResult,
                                                                             String conversationId) {
        try {
            QueryRequest structuredQuery = extractionResult.getQueryRequest();

            LogImportRequest importRequest = new LogImportRequest();
            importRequest.setCaseNumber(structuredQuery.getCaseNumber());
            importRequest.setStepName(structuredQuery.getStepName());

            if (!isValidImportRequest(importRequest)) {
                return createValidationErrorResponse(structuredQuery, extractionResult.getConfidence(), conversationId);
            }

            String searchCriteria = buildSearchCriteria(importRequest);

            String importStatus = processImport(importRequest, searchCriteria);

            log.info("Successfully processed import request for {} (conversation: {})", searchCriteria, conversationId);

            NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(importStatus, structuredQuery, List.of(),
                    extractionResult.getConfidence());
            response.setConversationId(conversationId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing import request (conversation: {}): ", conversationId, e);
            return createErrorResponse(ErrorMessages.IMPORT_ERROR_MESSAGE, conversationId);
        }
    }

    private boolean isValidImportRequest(LogImportRequest importRequest) {
        return importRequest.getCaseNumber() != null || (importRequest.getStepName() != null && !importRequest.getStepName().trim().isEmpty());
    }

    private ResponseEntity<NaturalLanguageQueryResponse> createValidationErrorResponse(QueryRequest structuredQuery, double confidence, String conversationId) {
        NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(ErrorMessages.MISSING_PARAMS_MESSAGE,
                structuredQuery, List.of(), confidence);
        errorResponse.setConversationId(conversationId);
        return ResponseEntity.badRequest().body(errorResponse);
    }

    private String buildSearchCriteria(LogImportRequest importRequest) {
        if (importRequest.getCaseNumber() != null) {
            return "case " + importRequest.getCaseNumber();
        } else {
            return "step " + importRequest.getStepName();
        }
    }

    private String processImport(LogImportRequest importRequest, String searchCriteria) {
        try {
            List<Map<String, Object>> salesforceRecords = fetchSalesforceRecords(importRequest);
            
            if (salesforceRecords.isEmpty()) {
                return String.format(ErrorMessages.NO_ATTACHMENTS_FORMAT, searchCriteria);
            }

            ResponseEntity<LogImportResponse> importResponseEntity = logImportService.importLogs(importRequest);
            LogImportResponse importResponse = importResponseEntity.getBody();
            
            if (importResponse == null) {
                return String.format(ErrorMessages.IMPORT_FAILED_FORMAT, searchCriteria, "No response from import service");
            }

            return String.format(ErrorMessages.SUCCESS_FORMAT, searchCriteria, importResponse.getProcessedAttachments(), importResponse.getTotalAttachments(),
                    importResponse.getSkippedAttachments(), importResponse.getSuccessfulLogs(), importResponse.getFailedLogs(), searchCriteria);
        } catch (Exception importException) {
            log.error("Error during actual import for {}: ", searchCriteria, importException);
            return String.format(ErrorMessages.IMPORT_FAILED_FORMAT, searchCriteria, importException.getMessage());
        }
    }

    private List<Map<String, Object>> fetchSalesforceRecords(LogImportRequest importRequest) throws Exception {
        if (importRequest.getCaseNumber() != null) {
            return salesforceService.queryFailedAttachmentsByCaseNumber(importRequest.getCaseNumber());
        } else {
            return salesforceService.queryFailedStepAttachments(importRequest.getStepName());
        }
    }
}


