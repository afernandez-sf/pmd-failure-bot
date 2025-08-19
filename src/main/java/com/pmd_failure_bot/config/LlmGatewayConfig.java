package com.pmd_failure_bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm.gateway")
public class LlmGatewayConfig {
    
    private String gatewayUrl;
    private String orgId;
    private String llmProvider;
    private String authScheme;
    private String authToken;
    private String tenantId;
    private String clientFeatureId;
    private String model;
    private double temperature;
    private int maxTokens;
    private int timeoutSeconds;

    public void validate() {
        if (gatewayUrl == null || gatewayUrl.isEmpty()) {
            throw new IllegalArgumentException("Salesforce LLM Gateway URL is required");
        }
        if (orgId == null || orgId.isEmpty()) {
            throw new IllegalArgumentException("Salesforce Org ID is required");
        }
        if (authToken == null || authToken.isEmpty()) {
            throw new IllegalArgumentException("Salesforce LLM Gateway auth token is required");
        }
        if (tenantId == null || tenantId.isEmpty()) {
            throw new IllegalArgumentException("Salesforce Tenant ID is required");
        }
    }
}