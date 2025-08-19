package com.pmd_failure_bot.web.dto;

import com.pmd_failure_bot.web.dto.response.NaturalLanguageQueryResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QueryResponseFactory {

    private static final double SUCCESS_CONFIDENCE = 1.0;
    private static final double ERROR_CONFIDENCE = 0.0;

    public NaturalLanguageQueryResponse createSuccessResponse(String content, QueryRequest request, String conversationId) {
        NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(content, request, List.of(), SUCCESS_CONFIDENCE);
        response.setConversationId(conversationId);
        return response;
    }

    public NaturalLanguageQueryResponse createErrorResponse(String errorMessage, QueryRequest request, String conversationId) {
        QueryRequest safeRequest = request != null ? request : new QueryRequest();
        NaturalLanguageQueryResponse response = new NaturalLanguageQueryResponse(errorMessage, safeRequest, List.of(), ERROR_CONFIDENCE);
        response.setConversationId(conversationId);
        return response;
    }
}


