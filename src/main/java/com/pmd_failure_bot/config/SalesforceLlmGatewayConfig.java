package com.pmd_failure_bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "salesforce.llm.gateway")
public class SalesforceLlmGatewayConfig {
    
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
    
    public SalesforceLlmGatewayConfig() {}

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getAuthScheme() {
        return authScheme;
    }

    public void setAuthScheme(String authScheme) {
        this.authScheme = authScheme;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientFeatureId() {
        return clientFeatureId;
    }

    public void setClientFeatureId(String clientFeatureId) {
        this.clientFeatureId = clientFeatureId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

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