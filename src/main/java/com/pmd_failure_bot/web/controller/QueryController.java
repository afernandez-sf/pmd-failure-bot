package com.pmd_failure_bot.web.controller;

import com.pmd_failure_bot.web.dto.request.NaturalLanguageQueryRequest;
import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.web.dto.QueryResponseFactory;
import com.pmd_failure_bot.service.query.DatabaseQueryService;
import com.pmd_failure_bot.service.analysis.NaturalLanguageProcessingService;
import com.pmd_failure_bot.common.constants.ErrorMessages;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {


    private final NaturalLanguageProcessingService nlpService;
    private final DatabaseQueryService databaseQueryService;
    private final QueryResponseFactory responseFactory;

    @PostMapping
    public ResponseEntity<NaturalLanguageQueryResponse> processNaturalLanguageQuery(@Valid @RequestBody NaturalLanguageQueryRequest request) {
        String conversationId = UUID.randomUUID().toString();
        try {
            log.info("Processing natural language query: '{}' (conversation: {})", request.getQuery(), conversationId);
            return handleNaturalLanguageQuery(request, conversationId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid query parameters for conversation {}: {}", conversationId, e.getMessage());
            return createErrorResponse(String.format(ErrorMessages.VALIDATION_ERROR_FORMAT, e.getMessage()), conversationId, true);
        } catch (Exception e) {
            log.error("Error processing natural language query for conversation {}: ", conversationId, e);
            return createErrorResponse(ErrorMessages.GENERAL_ERROR_MESSAGE, conversationId, false);
        }
    }

    private ResponseEntity<NaturalLanguageQueryResponse> handleNaturalLanguageQuery(NaturalLanguageQueryRequest request, String conversationId) {
        try {
            String intent = extractIntent(request);
            if (intent == null) {
                return createFactoryErrorResponse(ErrorMessages.INTENT_EXTRACTION_FAILURE_MESSAGE, request, conversationId);
            }
            DatabaseQueryService.DatabaseQueryResult result = databaseQueryService.processNaturalLanguageQuery(request.getQuery(), intent);
            return processQueryResult(result, request, conversationId);
        } catch (Exception e) {
            log.error("Exception in function calling query: ", e);
            return createFactoryErrorResponse(ErrorMessages.UNEXPECTED_ERROR_MESSAGE, request, conversationId);
        }
    }

    private ResponseEntity<NaturalLanguageQueryResponse> createErrorResponse(String message, String conversationId, boolean isBadRequest) {
        NaturalLanguageQueryResponse errorResponse = responseFactory.createErrorResponse(message, new QueryRequest(), conversationId);
        return isBadRequest ? ResponseEntity.badRequest().body(errorResponse) : ResponseEntity.internalServerError().body(errorResponse);
    }

    private String extractIntent(NaturalLanguageQueryRequest request) {
        try {
            NaturalLanguageProcessingService.ParameterExtractionResult extraction = nlpService.extractParameters(request.getQuery(), request.getConversationContext());
            return extraction.getIntent();
        } catch (Exception e) {
            log.warn("Failed to extract intent from query: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<NaturalLanguageQueryResponse> processQueryResult(DatabaseQueryService.DatabaseQueryResult result,
                                                                            NaturalLanguageQueryRequest request, String conversationId) {
        if (result.successful()) {
            log.info("Function calling query successful: {} records found", result.getResultCount());
            log.info("Generated SQL: {}", result.sqlQuery());
            
            QueryRequest queryRequest = createQueryRequest(request.getQuery());
            NaturalLanguageQueryResponse response = responseFactory.createSuccessResponse(result.naturalLanguageResponse(),
                    queryRequest, conversationId);
            return ResponseEntity.ok(response);
        } else {
            log.error("Function calling query failed: {}", result.errorMessage());
            return createFactoryErrorResponse(String.format(ErrorMessages.PROCESSING_ERROR_FORMAT, result.errorMessage()), request, conversationId);
        }
    }

    private ResponseEntity<NaturalLanguageQueryResponse> createFactoryErrorResponse(String message, NaturalLanguageQueryRequest request,
                                                                                    String conversationId) {
        QueryRequest queryRequest = createQueryRequest(request.getQuery());
        NaturalLanguageQueryResponse errorResponse = responseFactory.createErrorResponse(message, queryRequest, conversationId);
        return ResponseEntity.internalServerError().body(errorResponse);
    }

    private QueryRequest createQueryRequest(String query) {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setQuery(query);
        return queryRequest;
    }
}


