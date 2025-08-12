package com.pmd_failure_bot.service;

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

@Service
public class SalesforceLlmGatewayService {
    
    private static final Logger logger = LoggerFactory.getLogger(SalesforceLlmGatewayService.class);
    
    private final SalesforceLlmGatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public SalesforceLlmGatewayService(SalesforceLlmGatewayConfig config) {
        this.config = config;
    }
    
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
}