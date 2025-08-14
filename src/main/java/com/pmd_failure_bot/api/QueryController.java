package com.pmd_failure_bot.api;

import com.pmd_failure_bot.dto.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.dto.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.domain.query.DatabaseQueryService;
import com.pmd_failure_bot.domain.analysis.NaturalLanguageProcessingService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller for natural language query processing
 */
@RestController
@RequestMapping("/api/mcp/query")
public class QueryController {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);
    
    private final NaturalLanguageProcessingService nlpService;
    private final DatabaseQueryService databaseQueryService;
    
    @Autowired
    public QueryController(
            NaturalLanguageProcessingService nlpService,
            DatabaseQueryService databaseQueryService) {
        this.nlpService = nlpService;
        this.databaseQueryService = databaseQueryService;
    }
    
    /**
     * MCP Tool endpoint for natural language PMD log queries
     * This endpoint provides the core MCP functionality for conversational log analysis
     */
    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processNaturalLanguageQuery(
            @Valid @RequestBody NaturalLanguageQueryRequest request) {
        
        long startTime = System.currentTimeMillis();
        String conversationId = UUID.randomUUID().toString();
        
        try {
            logger.info("Processing natural language query: '{}' (conversation: {})", 
                       request.getQuery(), conversationId);
            
            // Process the query using function calling
            logger.info("🤖 Processing natural language query with function calling: {}", request.getQuery());
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
     * Handle natural language queries using function calling
     */
    private ResponseEntity<NaturalLanguageQueryResponse> handleNaturalLanguageQuery(
            NaturalLanguageQueryRequest request, String conversationId, long startTime) {
        
        try {
            // Route to metrics or analysis based on extracted intent
            String intent = "metrics"; // default
            try {
                NaturalLanguageProcessingService.ParameterExtractionResult extraction = 
                    nlpService.extractParameters(request.getQuery(), request.getConversationContext());
                intent = extraction.getIntent();
            } catch (Exception ignored) {}

            DatabaseQueryService.DatabaseQueryResult result = 
                databaseQueryService.processNaturalLanguageQuery(request.getQuery(), intent);
            
            if (result.isSuccessful()) {
                // Related work items removed; do not expose report links
                List<QueryResponse.ReportInfo> reportPaths = List.of();
                
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
}