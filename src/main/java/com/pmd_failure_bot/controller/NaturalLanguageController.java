package com.pmd_failure_bot.controller;

import com.pmd_failure_bot.dto.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.dto.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.service.NaturalLanguageProcessingService;
import com.pmd_failure_bot.service.QueryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/mcp")
public class NaturalLanguageController {
    
    private static final Logger logger = LoggerFactory.getLogger(NaturalLanguageController.class);
    
    private final NaturalLanguageProcessingService nlpService;
    private final QueryService queryService;
    
    @Autowired
    public NaturalLanguageController(NaturalLanguageProcessingService nlpService, QueryService queryService) {
        this.nlpService = nlpService;
        this.queryService = queryService;
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
            
            logger.info("Extracted parameters - Case: {}, Step: {}, Host: {}, Date: {}, Confidence: {} (Method: {})", 
                       structuredQuery.getCaseNumber(), structuredQuery.getStepName(), 
                       structuredQuery.getHostname(), structuredQuery.getReportDate(), 
                       confidence, extractionResult.getExtractionMethod());
            
            // Step 2: Validate that we have enough filters to proceed
            if (isAllFiltersEmpty(structuredQuery)) {
                NaturalLanguageQueryResponse errorResponse = new NaturalLanguageQueryResponse(
                    "I need more specific information to search the logs. Please mention at least one of: " +
                    "case number, step name, hostname, work ID, record ID, attachment ID, or date. " +
                    "For example: 'What went wrong with case 123456?' or 'Show me SSH failures from yesterday'",
                    structuredQuery,
                    List.of(),
                    confidence,
                    LocalDateTime.now(),
                    System.currentTimeMillis() - startTime
                );
                errorResponse.setConversationId(conversationId);
                return ResponseEntity.badRequest().body(errorResponse);
            }
            
            // Step 3: Execute the structured query
            QueryResponse queryResponse = queryService.processQuery(structuredQuery);
            
            // Step 4: Format the response
            long executionTimeMs = System.currentTimeMillis() - startTime;
            
            NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(
                queryResponse.getLlmResponse(),
                structuredQuery,
                queryResponse.getReports(),
                confidence,
                LocalDateTime.now(),
                executionTimeMs
            );
            response.setConversationId(conversationId);
            
            logger.info("Successfully processed natural language query in {}ms (conversation: {})", 
                       executionTimeMs, conversationId);
            
            return ResponseEntity.ok(response);
            
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