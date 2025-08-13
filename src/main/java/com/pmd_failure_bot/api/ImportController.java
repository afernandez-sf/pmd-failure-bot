package com.pmd_failure_bot.api;

import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.dto.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.dto.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.domain.imports.LogImportService;
import com.pmd_failure_bot.domain.analysis.NaturalLanguageProcessingService;
import com.pmd_failure_bot.infrastructure.salesforce.SalesforceService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Controller for log import operations
 */
@RestController
@RequestMapping("/api/mcp/import")
public class ImportController {
    
    private static final Logger logger = LoggerFactory.getLogger(ImportController.class);
    
    private final NaturalLanguageProcessingService nlpService;
    private final SalesforceService salesforceService;
    private final LogImportService logImportService;
    
    @Autowired
    public ImportController(
            NaturalLanguageProcessingService nlpService,
            SalesforceService salesforceService,
            LogImportService logImportService) {
        this.nlpService = nlpService;
        this.salesforceService = salesforceService;
        this.logImportService = logImportService;
    }
    
    /**
     * Process natural language import requests
     */
    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processImportRequest(
            @Valid @RequestBody NaturalLanguageQueryRequest request) {
        
        long startTime = System.currentTimeMillis();
        String conversationId = UUID.randomUUID().toString();
        
        try {
            logger.info("Processing import request: '{}' (conversation: {})", 
                       request.getQuery(), conversationId);
            
            // Extract parameters from natural language query
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = 
                nlpService.extractParameters(request.getQuery(), request.getConversationContext());
            
            return handleImportRequest(request, extractionResult, conversationId, startTime);
            
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
    
    /**
     * Handle import requests through natural language
     */
    private ResponseEntity<NaturalLanguageQueryResponse> handleImportRequest(
            NaturalLanguageQueryRequest request, 
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult,
            String conversationId, 
            long startTime) {
        
        try {
            QueryRequest structuredQuery = extractionResult.getQueryRequest();
            
            // Create LogImportRequest from QueryRequest
            LogImportRequest importRequest = new LogImportRequest();
            importRequest.setCaseNumber(structuredQuery.getCaseNumber());
            importRequest.setStepName(structuredQuery.getStepName());
            
            // Validate that we have either case number or step name
            if (importRequest.getCaseNumber() == null && 
                (importRequest.getStepName() == null || importRequest.getStepName().trim().isEmpty())) {
                
                NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                    "I need either a case number or step name to import logs. " +
                    "For example: 'Import logs for case 123456' or 'Pull logs from SSH_TO_ALL_HOSTS step'",
                    structuredQuery,
                    List.of(),
                    extractionResult.getConfidence(),
                    LocalDateTime.now(),
                    System.currentTimeMillis() - startTime
                );
                errorResponse.setConversationId(conversationId);
                return ResponseEntity.badRequest().body(errorResponse);
            }
            
            String importStatus;
            String searchCriteria;
            
            // Determine search criteria
            if (importRequest.getCaseNumber() != null) {
                searchCriteria = "case " + importRequest.getCaseNumber();
            } else {
                searchCriteria = "step " + importRequest.getStepName();
            }
            
            // Actually perform the import
            try {
                java.util.List<java.util.Map<String, Object>> salesforceRecords;
                
                if (importRequest.getCaseNumber() != null) {
                    salesforceRecords = salesforceService.queryFailedAttachmentsByCaseNumber(importRequest.getCaseNumber());
                } else {
                    salesforceRecords = salesforceService.queryFailedStepAttachments(importRequest.getStepName());
                }
                
                if (salesforceRecords.isEmpty()) {
                    importStatus = String.format("No failed attachments found for %s. " +
                                               "There may be no failures to import, or they might already be processed.",
                                               searchCriteria);
                } else {
                    // Delegate to the import service
                    ResponseEntity<LogImportResponse> importResponseEntity = logImportService.importLogs(importRequest);
                    LogImportResponse importResponse = importResponseEntity.getBody();
                    
                    int totalAttachments = importResponse.getTotalAttachments();
                    int processedAttachments = importResponse.getProcessedAttachments();
                    int skippedAttachments = importResponse.getSkippedAttachments();
                    int successfulLogs = importResponse.getSuccessfulLogs();
                    int failedLogs = importResponse.getFailedLogs();
                    
                    importStatus = String.format("✅ Import completed for %s!\n" +
                                               "📊 Processed %d/%d attachments (%d skipped)\n" +
                                               "📝 Imported %d logs successfully (%d failed)\n" +
                                               "💡 You can now query with: 'What issues occurred in %s?'",
                                               searchCriteria, processedAttachments, totalAttachments, skippedAttachments,
                                               successfulLogs, failedLogs, searchCriteria);
                }
            } catch (Exception importException) {
                logger.error("Error during actual import for {}: ", searchCriteria, importException);
                importStatus = String.format("❌ Import failed for %s: %s\n" +
                                           "Please try again or contact support.",
                                           searchCriteria, importException.getMessage());
            }
            
            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.info("Successfully processed import request for {} in {}ms (conversation: {})", 
                       searchCriteria, executionTimeMs, conversationId);
            
            NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(
                importStatus,
                structuredQuery,
                List.of(), // No reports for import requests
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