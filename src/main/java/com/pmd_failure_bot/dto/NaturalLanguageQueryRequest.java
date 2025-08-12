package com.pmd_failure_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class NaturalLanguageQueryRequest {
    
    @NotBlank(message = "Query is required")
    private String query;
    
    @JsonProperty("conversation_context")
    private String conversationContext;
    
    @JsonProperty("user_id")
    private String userId;
    
    public NaturalLanguageQueryRequest() {}
    
    public NaturalLanguageQueryRequest(String query) {
        this.query = query;
    }
    
    public NaturalLanguageQueryRequest(String query, String conversationContext, String userId) {
        this.query = query;
        this.conversationContext = conversationContext;
        this.userId = userId;
    }
    
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
    
    public String getConversationContext() {
        return conversationContext;
    }
    
    public void setConversationContext(String conversationContext) {
        this.conversationContext = conversationContext;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
}