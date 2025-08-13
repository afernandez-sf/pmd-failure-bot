package com.pmd_failure_bot.infrastructure.salesforce;

import com.pmd_failure_bot.config.SalesforceLlmGatewayConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Service for interacting with the Salesforce LLM Gateway
 */
@Service
public class SalesforceLlmGatewayService {
    
    private static final Logger logger = LoggerFactory.getLogger(SalesforceLlmGatewayService.class);
    
    private final SalesforceLlmGatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    


    @Autowired
    public SalesforceLlmGatewayService(SalesforceLlmGatewayConfig config) {
        this.config = config;
    }
    
    /**
     * Generate a text response from the LLM
     */
    public String generateResponse(String prompt) throws Exception {
        config.validate();
        
        logger.info("Sending request to Salesforce LLM Gateway...");
        
        // Build the request payload to match Salesforce LLM Gateway format
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("prompt", prompt);
        requestPayload.put("temperature", config.getTemperature());
        requestPayload.put("max_tokens", config.getMaxTokens());
        requestPayload.put("model", config.getModel());
        
        String jsonPayload = objectMapper.writeValueAsString(requestPayload);
        logger.debug("Request payload: {}", jsonPayload);
        
        // Create HTTP client with timeout
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            
            // Create the POST request
            String endpoint = config.getGatewayUrl() + "/v1.0/generations";
            HttpPost request = new HttpPost(endpoint);
            
            // Set headers
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-LLM-Provider", config.getLlmProvider());
            request.setHeader("X-Org-Id", config.getOrgId());
            request.setHeader("Authorization", config.getAuthScheme() + " " + config.getAuthToken());
            request.setHeader("x-sfdc-core-tenant-id", config.getTenantId());
            
            if (config.getClientFeatureId() != null && !config.getClientFeatureId().isEmpty()) {
                request.setHeader("x-client-feature-id", config.getClientFeatureId());
            }
            
            // Set the request body
            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            
            logger.debug("Request headers: Content-Type: {}, X-LLM-Provider: {}, X-Org-Id: {}, Authorization: {}, x-sfdc-core-tenant-id: {}", 
                "application/json", config.getLlmProvider(), config.getOrgId(), 
                config.getAuthScheme() + " ***", config.getTenantId());
            
            // Execute the request
            return httpClient.execute(request, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes());
                
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    logger.info("Successfully received response from LLM Gateway");
                    logger.debug("Response body: {}", responseBody);
                    
                    // Parse the response to extract the generated text
                    JsonNode responseJson = objectMapper.readTree(responseBody);
                    
                    // Handle Salesforce LLM Gateway response format
                    // Check for generations array (Gemini format)
                    if (responseJson.has("generations") && responseJson.get("generations").isArray() && 
                        responseJson.get("generations").size() > 0) {
                        JsonNode firstGeneration = responseJson.get("generations").get(0);
                        
                        // Check for text field in generation
                        if (firstGeneration.has("text")) {
                            return firstGeneration.get("text").asText();
                        }
                    }
                    
                    // Check for choices array (OpenAI format)
                    if (responseJson.has("choices") && responseJson.get("choices").isArray() && 
                        responseJson.get("choices").size() > 0) {
                        JsonNode firstChoice = responseJson.get("choices").get(0);
                        
                        // Check for text field in choice
                        if (firstChoice.has("text")) {
                            return firstChoice.get("text").asText();
                        }
                        
                        // Check for message content
                        if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                            return firstChoice.get("message").get("content").asText();
                        }
                        
                        // Check for completion field
                        if (firstChoice.has("completion")) {
                            return firstChoice.get("completion").asText();
                        }
                    }
                    
                    // Check for direct fields in response
                    if (responseJson.has("text")) {
                        return responseJson.get("text").asText();
                    }
                    
                    if (responseJson.has("content")) {
                        return responseJson.get("content").asText();
                    }
                    
                    if (responseJson.has("completion")) {
                        return responseJson.get("completion").asText();
                    }
                    
                    // Log the response structure for debugging
                    logger.warn("Unable to parse LLM response format. Response structure: {}", responseJson.toPrettyString());
                    return responseBody;
                    
                } else {
                    logger.error("LLM Gateway request failed with status: {} {}", response.getCode(), response.getReasonPhrase());
                    logger.error("Request URL: {}", endpoint);
                    logger.error("Request payload: {}", jsonPayload);
                    logger.error("Response body: {}", responseBody);
                    throw new RuntimeException("LLM Gateway request failed: " + response.getCode() + " " + response.getReasonPhrase() + ". Response: " + responseBody);
                }
            });
        }
    }
    
    /**
     * Generate response with function calling support
     */
    public FunctionCallResponse generateResponseWithFunctions(String userMessage, List<Map<String, Object>> tools) throws Exception {
        config.validate();
        
        logger.info("Sending function calling request to Salesforce LLM Gateway...");
        
        // Build the messages array for chat completion
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        
        // Build the request payload for chat/generations endpoint
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("messages", List.of(userMsg));
        
        // Generation settings
        Map<String, Object> generationSettings = new HashMap<>();
        generationSettings.put("max_tokens", config.getMaxTokens());
        generationSettings.put("temperature", config.getTemperature());
        generationSettings.put("parameters", new HashMap<>());
        requestPayload.put("generation_settings", generationSettings);
        
        // Add tools
        requestPayload.put("tools", tools);
        
        // Tool configuration - force the model to use tools
        Map<String, Object> toolConfig = new HashMap<>();
        toolConfig.put("mode", "auto"); // Let model decide when to use tools
        requestPayload.put("tool_config", toolConfig);
        
        requestPayload.put("model", config.getModel());
        
        String jsonPayload = objectMapper.writeValueAsString(requestPayload);
        logger.debug("Function calling request payload: {}", jsonPayload);
        
        // Create HTTP client with timeout
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            
            // Use chat/generations endpoint for function calling
            String endpoint = config.getGatewayUrl() + "/v1.0/chat/generations";
            HttpPost request = new HttpPost(endpoint);
            
            // Set headers
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-LLM-Provider", config.getLlmProvider());
            request.setHeader("X-Org-Id", config.getOrgId());
            request.setHeader("Authorization", config.getAuthScheme() + " " + config.getAuthToken());
            request.setHeader("x-sfdc-core-tenant-id", config.getTenantId());
            
            if (config.getClientFeatureId() != null && !config.getClientFeatureId().isEmpty()) {
                request.setHeader("x-client-feature-id", config.getClientFeatureId());
            }
            
            // Set the request body
            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            
            // Execute the request
            return httpClient.execute(request, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes());
                
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    logger.info("Successfully received function calling response from LLM Gateway");
                    logger.debug("Function calling response body: {}", responseBody);
                    
                    // Parse the response to extract function calls or content
                    JsonNode responseJson = objectMapper.readTree(responseBody);
                    return parseFunctionCallResponse(responseJson);
                    
                } else {
                    logger.error("Function calling request failed with status: {} {}", response.getCode(), response.getReasonPhrase());
                    logger.error("Request URL: {}", endpoint);
                    logger.error("Request payload: {}", jsonPayload);
                    logger.error("Response body: {}", responseBody);
                    throw new RuntimeException("Function calling request failed: " + response.getCode() + " " + response.getReasonPhrase() + ". Response: " + responseBody);
                }
            });
        }
    }
    
    private FunctionCallResponse parseFunctionCallResponse(JsonNode responseJson) {
        try {
            // Navigate to generation_details.generations[0]
            JsonNode generationDetails = responseJson.get("generation_details");
            if (generationDetails != null && generationDetails.has("generations")) {
                JsonNode generations = generationDetails.get("generations");
                if (generations.isArray() && generations.size() > 0) {
                    JsonNode generation = generations.get(0);
                    
                    // Check if there are tool invocations
                    if (generation.has("tool_invocations") && generation.get("tool_invocations").isArray()) {
                        JsonNode toolInvocations = generation.get("tool_invocations");
                        if (toolInvocations.size() > 0) {
                            JsonNode toolInvocation = toolInvocations.get(0);
                            if (toolInvocation.has("function")) {
                                JsonNode function = toolInvocation.get("function");
                                String functionName = function.get("name").asText();
                                String arguments = function.get("arguments").asText();
                                String invocationId = toolInvocation.get("id").asText();
                                
                                return new FunctionCallResponse(functionName, arguments, invocationId);
                            }
                        }
                    }
                    
                    // No function call, return regular content
                    if (generation.has("content")) {
                        return new FunctionCallResponse(generation.get("content").asText());
                    }
                }
            }
            
            logger.warn("Unable to parse function call response format. Response structure: {}", responseJson.toPrettyString());
            return new FunctionCallResponse("Unable to parse response");
            
        } catch (Exception e) {
            logger.error("Error parsing function call response: ", e);
            return new FunctionCallResponse("Error parsing response: " + e.getMessage());
        }
    }
    
    /**
     * Response class for function calling
     */
    public static class FunctionCallResponse {
        private final boolean isFunctionCall;
        private final String functionName;
        private final String arguments;
        private final String invocationId;
        private final String content;
        
        // Constructor for function call
        public FunctionCallResponse(String functionName, String arguments, String invocationId) {
            this.isFunctionCall = true;
            this.functionName = functionName;
            this.arguments = arguments;
            this.invocationId = invocationId;
            this.content = null;
        }
        
        // Constructor for regular content
        public FunctionCallResponse(String content) {
            this.isFunctionCall = false;
            this.functionName = null;
            this.arguments = null;
            this.invocationId = null;
            this.content = content;
        }
        
        public boolean isFunctionCall() { return isFunctionCall; }
        public String getFunctionName() { return functionName; }
        public String getArguments() { return arguments; }
        public String getInvocationId() { return invocationId; }
        public String getContent() { return content; }
    }
}