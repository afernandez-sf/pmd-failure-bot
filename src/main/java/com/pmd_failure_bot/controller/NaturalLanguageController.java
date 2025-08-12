package com.pmd_failure_bot.controller;

import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.dto.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.dto.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.service.LogProcessingService;
import com.pmd_failure_bot.service.LogImportService;
import com.pmd_failure_bot.service.NaturalLanguageProcessingService;
import com.pmd_failure_bot.service.QueryService;
import com.pmd_failure_bot.service.SalesforceService;
import com.pmd_failure_bot.service.DatabaseQueryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

@RestController
@RequestMapping("/api/mcp")
public class NaturalLanguageController {
    
    private static final Logger logger = LoggerFactory.getLogger(NaturalLanguageController.class);
    
    private final NaturalLanguageProcessingService nlpService;
    private final QueryService queryService;
    private final SalesforceService salesforceService;
    private final LogProcessingService logProcessingService;
    private final DatabaseQueryService databaseQueryService;
    
    @Autowired
    public NaturalLanguageController(NaturalLanguageProcessingService nlpService, QueryService queryService,
                                   SalesforceService salesforceService, LogProcessingService logProcessingService,
                                   DatabaseQueryService databaseQueryService) {
        this.nlpService = nlpService;
        this.queryService = queryService;
        this.salesforceService = salesforceService;
        this.logProcessingService = logProcessingService;
        this.databaseQueryService = databaseQueryService;
    }
    
    /**
     * MCP Tool endpoint for natural language PMD log queries
     * This endpoint provides the core MCP functionality for conversational log analysis
     */
    @PostMapping("/query")
    public ResponseEntity<NaturalLanguageQueryResponse> processNaturalLanguageQuery(
            @Valid @RequestBody NaturalLanguageQueryRequest request) {
        
        long startTime = System.currentTimeMillis();
        String conversationId = UUID.randomUUID().toString();
        
        try {
            logger.info("Processing natural language query: '{}' (conversation: {})", 
                       request.getQuery(), conversationId);
            
            // Step 1: Extract parameters from natural language query
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = 
                nlpService.extractParameters(request.getQuery(), request.getConversationContext());
            
            QueryRequest structuredQuery = extractionResult.getQueryRequest();
            double confidence = extractionResult.getConfidence();
            String intent = extractionResult.getIntent();
            
            logger.info("Extracted parameters - Case: {}, Step: {}, Host: {}, Date: {}, Intent: {}, Confidence: {} (Method: {})", 
                       structuredQuery.getCaseNumber(), structuredQuery.getStepName(), 
                       structuredQuery.getHostname(), structuredQuery.getReportDate(), 
                       intent, confidence, extractionResult.getExtractionMethod());
            
            // Check if this is an import request
            if (extractionResult.isImportRequest()) {
                return handleImportRequest(request, extractionResult, conversationId, startTime);
            }
            
            // Step 2: Always use function calling for natural language queries
            // The old parameter extraction is kept for import request detection only
            logger.info("🔀 Routing to function calling (natural language query): {}", request.getQuery());
            return handleNaturalLanguageQuery(request, conversationId, startTime);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid query parameters for conversation {}: {}", conversationId, e.getMessage());
            
            NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                "I couldn't understand your query: " + e.getMessage() + 
                ". Please try rephrasing or provide more specific details.",
                new QueryRequest(),
                List.of(),
                0.0,
                LocalDateTime.now(),
                System.currentTimeMillis() - startTime
            );
            errorResponse.setConversationId(conversationId);
            
            return ResponseEntity.badRequest().body(errorResponse);
            
        } catch (Exception e) {
            logger.error("Error processing natural language query for conversation {}: ", conversationId, e);
            
            NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                "I'm sorry, I encountered an error while processing your query. Please try again later or contact support if the issue persists.",
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
     * Health check endpoint for MCP tool availability
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("MCP Natural Language Query Tool is operational");
    }
    
    /**
     * Get information about supported query patterns and examples
     */
    @GetMapping("/help")
    public ResponseEntity<MCPHelpResponse> getHelp() {
        MCPHelpResponse help = new MCPHelpResponse();
        return ResponseEntity.ok(help);
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
                    // Delegate to the real import controller
                    LogImportService logImportService = new LogImportService(salesforceService, logProcessingService);
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

    /**
     * Check if all filter fields are empty (same logic as QueryService)
     */
    private boolean isAllFiltersEmpty(QueryRequest request) {
        return (request.getRecordId() == null || request.getRecordId().trim().isEmpty()) &&
               (request.getWorkId() == null || request.getWorkId().trim().isEmpty()) &&
               request.getCaseNumber() == null &&
               (request.getStepName() == null || request.getStepName().trim().isEmpty()) &&
               (request.getAttachmentId() == null || request.getAttachmentId().trim().isEmpty()) &&
               (request.getHostname() == null || request.getHostname().trim().isEmpty()) &&
               request.getReportDate() == null;
    }
    
    /**
     * Handle pure natural language queries using function calling
     */
    private ResponseEntity<NaturalLanguageQueryResponse> handleNaturalLanguageQuery(
            NaturalLanguageQueryRequest request, String conversationId, long startTime) {
        
        try {
            logger.info("🤖 Processing natural language query with function calling: {}", request.getQuery());
            
            // Use DatabaseQueryService for function calling
            DatabaseQueryService.DatabaseQueryResult result = 
                databaseQueryService.processNaturalLanguageQuery(request.getQuery());
            
            if (result.isSuccessful()) {
                // Convert results to report info
                List<QueryResponse.ReportInfo> reportPaths = result.getResults().stream()
                    .map(row -> {
                        String recordId = row.get("record_id") != null ? row.get("record_id").toString() : "N/A";
                        String workId = row.get("work_id") != null ? row.get("work_id").toString() : "N/A";
                        return new QueryResponse.ReportInfo(recordId, workId);
                    })
                    .distinct()
                    .collect(Collectors.toList());
                
                long executionTimeMs = System.currentTimeMillis() - startTime;
                
                logger.info("✅ Function calling query successful: {} records found in {}ms", 
                           result.getResultCount(), executionTimeMs);
                logger.info("📊 Generated SQL: {}", result.getSqlQuery());
                
                QueryRequest queryRequest = new QueryRequest();
                queryRequest.setQuery(request.getQuery());
                
                NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(
                    result.getNaturalLanguageResponse(),
                    queryRequest,
                    reportPaths,
                    1.0, // High confidence for function calling
                    LocalDateTime.now(),
                    executionTimeMs
                );
                response.setConversationId(conversationId);
                
                return ResponseEntity.ok(response);
                
            } else {
                logger.error("❌ Function calling query failed: {}", result.getErrorMessage());
                
                QueryRequest errorQueryRequest = new QueryRequest();
                errorQueryRequest.setQuery(request.getQuery());
                
                NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                    "I encountered an error while processing your query: " + result.getErrorMessage(),
                    errorQueryRequest,
                    List.of(),
                    0.0,
                    LocalDateTime.now(),
                    System.currentTimeMillis() - startTime
                );
                errorResponse.setConversationId(conversationId);
                
                return ResponseEntity.internalServerError().body(errorResponse);
            }
            
        } catch (Exception e) {
            logger.error("💥 Exception in function calling query: ", e);
            
            QueryRequest exceptionQueryRequest = new QueryRequest();
            exceptionQueryRequest.setQuery(request.getQuery());
            
            NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                "I encountered an unexpected error while processing your query. Please try again or contact support.",
                exceptionQueryRequest,
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
     * Help response for the MCP tool
     */
    public static class MCPHelpResponse {
        private final String description = "Natural Language Query Tool for PMD Failure Logs";
        private final String[] supportedParameters = {
            "case_number: Support case numbers (e.g., 'case 123456')",
            "step_name: Deployment step names (e.g., 'SSH deployment', 'GridForce')",
            "hostname: Server or host names (e.g., 'server prod01', 'CS58')",
            "date: Absolute or relative dates (e.g., 'yesterday', '2024-01-15')",
            "work_id: GUS work item identifiers",
            "record_id: Salesforce record identifiers",
            "attachment_id: Salesforce attachment identifiers"
        };
        private final String[] examples = {
            "What went wrong with case 123456's deployment yesterday?",
            "Show me SSH failures from last week",
            "Why did the GridForce deployment fail on CS58?",
            "What errors occurred during yesterday's deployments?",
            "Analyze the failures for case 789012",
            "What happened with the SSH deployment to prod servers?"
        };
        private final String[] tips = {
            "Include at least one filter (case number, step name, hostname, etc.) for better results",
            "Use relative dates like 'yesterday', 'last week', or 'today' for convenience",
            "Mention specific step names or partial matches (e.g., 'SSH' matches 'SSH_TO_ALL_HOSTS')",
            "Case numbers should be numeric only",
            "Be specific about what you want to know (failures, errors, analysis, etc.)"
        };
        
        public String getDescription() { return description; }
        public String[] getSupportedParameters() { return supportedParameters; }
        public String[] getExamples() { return examples; }
        public String[] getTips() { return tips; }
    }
}