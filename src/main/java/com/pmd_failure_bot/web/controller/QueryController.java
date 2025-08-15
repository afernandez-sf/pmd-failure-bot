package com.pmd_failure_bot.web.controller;

import com.pmd_failure_bot.web.dto.request.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.web.dto.response.QueryResponse;
import com.pmd_failure_bot.web.dto.QueryResponseFactory;
import com.pmd_failure_bot.service.query.DatabaseQueryService;
import com.pmd_failure_bot.service.analysis.NaturalLanguageProcessingService;
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
@RequestMapping("/api/query")
public class QueryController {

    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);

    private final NaturalLanguageProcessingService nlpService;
    private final DatabaseQueryService databaseQueryService;
    private final QueryResponseFactory responseFactory;

    @Autowired
    public QueryController(NaturalLanguageProcessingService nlpService,
                           DatabaseQueryService databaseQueryService,
                           QueryResponseFactory responseFactory) {
        this.nlpService = nlpService;
        this.databaseQueryService = databaseQueryService;
        this.responseFactory = responseFactory;
    }

    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processNaturalLanguageQuery(@Valid @RequestBody NaturalLanguageQueryRequest request) {
        long startTime = System.currentTimeMillis();
        String conversationId = UUID.randomUUID().toString();
        try {
            logger.info("Processing natural language query: '{}' (conversation: {})", request.getQuery(), conversationId);
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

    private ResponseEntity<NaturalLanguageQueryResponse> handleNaturalLanguageQuery(NaturalLanguageQueryRequest request,
                                                                                     String conversationId,
                                                                                     long startTime) {
        try {
            String intent = "metrics";
            try {
                NaturalLanguageProcessingService.ParameterExtractionResult extraction =
                    nlpService.extractParameters(request.getQuery(), request.getConversationContext());
                intent = extraction.getIntent();
            } catch (Exception ignored) {}

            DatabaseQueryService.DatabaseQueryResult result =
                databaseQueryService.processNaturalLanguageQuery(request.getQuery(), intent);

            if (result.isSuccessful()) {
                List<QueryResponse.ReportInfo> reportPaths = List.of();
                long executionTimeMs = System.currentTimeMillis() - startTime;
                logger.info("✅ Function calling query successful: {} records found in {}ms", result.getResultCount(), executionTimeMs);
                logger.info("📊 Generated SQL: {}", result.getSqlQuery());
                QueryRequest qr = new QueryRequest();
                qr.setQuery(request.getQuery());
                NaturalLanguageQueryResponse response = responseFactory.createSuccessResponse(
                    result.getNaturalLanguageResponse(),
                    qr,
                    conversationId,
                    1.0,
                    executionTimeMs
                );
                return ResponseEntity.ok(response);
            } else {
                logger.error("❌ Function calling query failed: {}", result.getErrorMessage());
                QueryRequest errorQueryRequest = new QueryRequest();
                errorQueryRequest.setQuery(request.getQuery());
                NaturalLanguageQueryResponse errorResponse = responseFactory.createErrorResponse(
                    "I encountered an error while processing your query: " + result.getErrorMessage(),
                    errorQueryRequest,
                    conversationId,
                    System.currentTimeMillis() - startTime
                );
                return ResponseEntity.internalServerError().body(errorResponse);
            }
        } catch (Exception e) {
            logger.error("💥 Exception in function calling query: ", e);
            QueryRequest exceptionQueryRequest = new QueryRequest();
            exceptionQueryRequest.setQuery(request.getQuery());
            NaturalLanguageQueryResponse errorResponse = responseFactory.createErrorResponse(
                "I encountered an unexpected error while processing your query. Please try again or contact support.",
                exceptionQueryRequest,
                conversationId,
                System.currentTimeMillis() - startTime
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}


