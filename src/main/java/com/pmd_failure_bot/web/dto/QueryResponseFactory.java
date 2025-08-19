package com.pmd_failure_bot.web.dto;

import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class QueryResponseFactory {

    public NaturalLanguageQueryResponse createSuccessResponse(String content, QueryRequest request, String conversationId, double confidence, long executionTimeMs) {
        NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(content, request, List.of(), confidence,
                LocalDateTime.now(), executionTimeMs);
        response.setConversationId(conversationId);
        return response;
    }

    public NaturalLanguageQueryResponse createErrorResponse(String errorMessage, QueryRequest request, String conversationId, long executionTimeMs) {
        NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(errorMessage, request != null ? request : new QueryRequest(),
                List.of(), 0.0, LocalDateTime.now(), executionTimeMs);
        response.setConversationId(conversationId);
        return response;
    }
}


