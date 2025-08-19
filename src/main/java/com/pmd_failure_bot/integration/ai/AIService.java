package com.pmd_failure_bot.integration.ai;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Interface for AI service interactions
 */
public interface AIService {
    /**
     * Generate text based on a prompt
     */
    String generate(String prompt) throws Exception;

    /**
     * Generate content or function call using provided tools
     */
    FunctionCallResponse generateWithFunctions(String userMessage, List<Map<String, Object>> tools) throws Exception;

    /**
     * Generate a strictly structured JSON string according to the provided JSON schema
     */
    String generateStructured(String prompt, Map<String, Object> jsonSchema) throws Exception;

    /**
     * Response class for function calling operations
     */
    @Getter
    class FunctionCallResponse {
        private final boolean functionCall;
        private final String functionName;
        private final String arguments;
        private final String invocationId;
        private final String content;

        protected static FunctionCallResponse forCall(String functionName, String arguments, String invocationId) {
            return new FunctionCallResponse(true, functionName, arguments, invocationId, null);
        }

        protected static FunctionCallResponse forContent(String content) {
            return new FunctionCallResponse(false, null, null, null, content);
        }

        private FunctionCallResponse(boolean functionCall, String functionName, String arguments, String invocationId, String content) {
            this.functionCall = functionCall;
            this.functionName = functionName;
            this.arguments = arguments;
            this.invocationId = invocationId;
            this.content = content;
        }
    }
}