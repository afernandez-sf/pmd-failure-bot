package com.pmd_failure_bot.integration.ai;

import com.pmd_failure_bot.config.LlmGatewayConfig;
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
 * Service for interacting with LLM Gateway
 */
@Service
public class LlmGatewayService implements AIService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmGatewayService.class);
    
    private final LlmGatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public LlmGatewayService(LlmGatewayConfig config) {
        this.config = config;
    }
    
    /**
     * Generate a text response from the LLM
     */
    @Override
    public String generate(String prompt) throws Exception {
        config.validate();
        
        logger.info("Sending request to LLM Gateway...");
        
        // Build the request payload to match LLM Gateway format
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("prompt", prompt);
        requestPayload.put("temperature", config.getTemperature());
        requestPayload.put("max_tokens", config.getMaxTokens());
        requestPayload.put("model", config.getModel());
        
        String jsonPayload = objectMapper.writeValueAsString(requestPayload);
        logger.debug("Request payload: {}", jsonPayload);
        
        // Create HTTP client with timeout
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            String endpoint = config.getGatewayUrl() + "/v1.0/generations";
            HttpPost request = new HttpPost(endpoint);
            
            setCommonHeaders(request);
            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            
            logger.debug("Request headers: Content-Type: {}, X-LLM-Provider: {}, X-Org-Id: {}, Authorization: {}, x-sfdc-core-tenant-id: {}", 
                "application/json", config.getLlmProvider(), config.getOrgId(), 
                config.getAuthScheme() + " ***", config.getTenantId());
            
            return httpClient.execute(request, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes());
                
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    logger.info("Successfully received response from LLM Gateway");
                    logger.debug("Response body: {}", responseBody);
                    
                    JsonNode responseJson = objectMapper.readTree(responseBody);
                    
                    // Handle LLM Gateway generations format
                    if (responseJson.has("generations") && responseJson.get("generations").isArray() &&
                            !responseJson.get("generations").isEmpty()) {
                        JsonNode firstGeneration = responseJson.get("generations").get(0);
                        
                        if (firstGeneration.has("text")) {
                            String text = firstGeneration.get("text").asText();
                            if (text != null && !text.trim().isEmpty()) {
                                return text;
                            }
                            logger.warn("LLM Gateway returned empty text field");
                        }
                        
                        if (firstGeneration.has("content")) {
                            String content = firstGeneration.get("content").asText();
                            if (content != null && !content.trim().isEmpty()) {
                                return content;
                            }
                            logger.warn("LLM Gateway returned empty content field");
                        }
                        
                        logger.warn("LLM Gateway returned generation with no valid text or content");
                    }
                    
                    logger.warn("Unable to parse LLM Gateway response format. Response structure: {}", responseJson.toPrettyString());
                    throw new RuntimeException("LLM Gateway returned unrecognized response format");
                } else {
                    logRequestFailure("LLM Gateway", response.getCode(), response.getReasonPhrase(), endpoint, jsonPayload, responseBody);
                    throw new RuntimeException("LLM Gateway request failed: " + response.getCode() + " " + response.getReasonPhrase() + ". Response: " + responseBody);
                }
            });
        }
    }
    
    /**
     * Generate response with function calling support
     */
    @Override
    public FunctionCallResponse generateWithFunctions(String userMessage, List<Map<String, Object>> tools) throws Exception {
        config.validate();
        
        logger.info("Sending function calling request to LLM Gateway...");
        
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
        
        // Tool configuration - require model to use one of the provided tools (no plain text content)
        Map<String, Object> toolConfig = new HashMap<>();
        toolConfig.put("mode", "any");
        // Build allowed_tools from provided tool definitions
        List<Map<String, Object>> allowedTools = new java.util.ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Object fnObj = tool.get("function");
            if (fnObj instanceof Map) {
                Object nameObj = ((Map<?, ?>) fnObj).get("name");
                if (nameObj != null) {
                    Map<String, Object> allowed = new HashMap<>();
                    allowed.put("type", "function");
                    allowed.put("name", String.valueOf(nameObj));
                    allowedTools.add(allowed);
                }
            }
        }
        toolConfig.put("allowed_tools", allowedTools);
        requestPayload.put("tool_config", toolConfig);
        
        requestPayload.put("model", config.getModel());
        
        String jsonPayload = objectMapper.writeValueAsString(requestPayload);
        logger.debug("Function calling request payload: {}", jsonPayload);
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {

            // Use chat/generations endpoint for function calling
            String endpoint = config.getGatewayUrl() + "/v1.0/chat/generations";
            HttpPost request = new HttpPost(endpoint);
            
            setCommonHeaders(request);
            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            
            return httpClient.execute(request, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes());
                
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    logger.info("Successfully received function calling response from LLM Gateway");
                    logger.debug("Function calling response body: {}", responseBody);
                    
                    // Parse the response to extract function calls or content
                    JsonNode responseJson = objectMapper.readTree(responseBody);
                    return parseFunctionCallResponse(responseJson);
                    
                } else {
                    logRequestFailure("Function calling", response.getCode(), response.getReasonPhrase(), endpoint, jsonPayload, responseBody);
                    throw new RuntimeException("Function calling request failed: " + response.getCode() + " " + response.getReasonPhrase() + ". Response: " + responseBody);
                }
            });
        }
    }

    /**
     * Generate a strictly structured JSON response using Structured Outputs (response_format)
     */
    @Override
    public String generateStructured(String prompt, Map<String, Object> jsonSchema) throws Exception {
        config.validate();

        logger.info("Sending structured output request to LLM Gateway...");

        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("model", config.getModel());
        // Use messages array for chat/generations endpoint
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        requestPayload.put("messages", List.of(userMsg));

        Map<String, Object> generationSettings = new HashMap<>();
        generationSettings.put("max_tokens", config.getMaxTokens());
        generationSettings.put("temperature", config.getTemperature());
        requestPayload.put("generation_settings", generationSettings);

        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_schema");

        Map<String, Object> jsonSchemaWrapper = new HashMap<>(jsonSchema);
        responseFormat.put("json_schema", jsonSchemaWrapper);
        parameters.put("response_format", responseFormat);
        requestPayload.put("parameters", parameters);

        String jsonPayload = objectMapper.writeValueAsString(requestPayload);
        logger.debug("Structured output request payload: {}", jsonPayload);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            String endpoint = config.getGatewayUrl() + "/v1.0/chat/generations";
            HttpPost request = new HttpPost(endpoint);
            setCommonHeaders(request);
            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));

            return httpClient.execute(request, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes());
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    JsonNode responseJson = objectMapper.readTree(responseBody);
                    JsonNode generationDetails = responseJson.get("generation_details");
                    if (generationDetails != null && generationDetails.has("generations")) {
                        JsonNode gens = generationDetails.get("generations");
                        if (gens.isArray() && !gens.isEmpty()) {
                            JsonNode first = gens.get(0);
                            if (first.has("content")) {
                                return first.get("content").asText();
                            }
                            if (first.has("text")) {
                                return first.get("text").asText();
                            }
                        }
                    }
                    // Fallback to raw body
                    logger.warn("Unable to parse structured output response; returning raw body");
                    return responseBody;
                } else {
                    logRequestFailure("Structured output", response.getCode(), response.getReasonPhrase(), endpoint, jsonPayload, responseBody);
                    throw new RuntimeException("Structured output request failed: " + response.getCode() + " " + response.getReasonPhrase());
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
                if (generations.isArray() && !generations.isEmpty()) {
                    JsonNode generation = generations.get(0);
                    
                    // Check if there are tool invocations
                    if (generation.has("tool_invocations") && generation.get("tool_invocations").isArray()) {
                        JsonNode toolInvocations = generation.get("tool_invocations");
                        if (!toolInvocations.isEmpty()) {
                            JsonNode toolInvocation = toolInvocations.get(0);
                            if (toolInvocation.has("function")) {
                                JsonNode function = toolInvocation.get("function");
                                String functionName = function.get("name").asText();
                                String arguments = function.get("arguments").asText();
                                String invocationId = toolInvocation.get("id").asText();
                                
                                return FunctionCallResponse.forCall(functionName, arguments, invocationId);
                            }
                        }
                    }
                    
                    // No function call, return regular content
                    if (generation.has("content")) {
                        return FunctionCallResponse.forContent(generation.get("content").asText());
                    }
                }
            }
            
            logger.warn("Unable to parse function call response format. Response structure: {}", responseJson.toPrettyString());
            return FunctionCallResponse.forContent("Unable to parse response");
            
        } catch (Exception e) {
            logger.error("Error parsing function call response: ", e);
            return FunctionCallResponse.forContent("Error parsing response: " + e.getMessage());
        }
    }
    
    private void setCommonHeaders(HttpPost request) {
        request.setHeader("Content-Type", "application/json");
        request.setHeader("X-LLM-Provider", config.getLlmProvider());
        request.setHeader("X-Org-Id", config.getOrgId());
        request.setHeader("Authorization", config.getAuthScheme() + " " + config.getAuthToken());
        request.setHeader("x-sfdc-core-tenant-id", config.getTenantId());
        
        if (config.getClientFeatureId() != null && !config.getClientFeatureId().isEmpty()) {
            request.setHeader("x-client-feature-id", config.getClientFeatureId());
        }
    }
    
    private void logRequestFailure(String operation, int statusCode, String reasonPhrase, String endpoint, String payload, String responseBody) {
        logger.error("{} request failed with status: {} {}", operation, statusCode, reasonPhrase);
        logger.error("Request URL: {}", endpoint);
        logger.error("Request payload: {}", payload);
        logger.error("Response body: {}", responseBody);
    }
}