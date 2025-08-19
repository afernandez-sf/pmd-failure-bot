package com.pmd_failure_bot.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NaturalLanguageQueryRequest {
    @NotBlank(message = "Query is required")
    private String query;

    @JsonProperty("conversation_context")
    private String conversationContext;

    @JsonProperty("user_id")
    private String userId;
}


