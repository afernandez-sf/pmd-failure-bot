package com.pmd_failure_bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "salesforce")
public class SalesforceConfig {
    
    private String username;
    private String password;
    private String securityToken;
    private String loginUrl;
    private String apiVersion = "v61.0";
    private int maxAttachmentSize = 50 * 1024 * 1024; // 50MB default
    private int maxRetries = 3;
    private int retryDelaySeconds = 2;
    
    public SalesforceConfig() {}
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getSecurityToken() {
        return securityToken;
    }
    
    public void setSecurityToken(String securityToken) {
        this.securityToken = securityToken;
    }
    
    public String getLoginUrl() {
        return loginUrl;
    }
    
    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }
    
    public String getApiVersion() {
        return apiVersion;
    }
    
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }
    
    public int getMaxAttachmentSize() {
        return maxAttachmentSize;
    }
    
    public void setMaxAttachmentSize(int maxAttachmentSize) {
        this.maxAttachmentSize = maxAttachmentSize;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }
    
    public void setRetryDelaySeconds(int retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }
    
    /**
     * Gets the combined password with security token as required by Salesforce
     */
    public String getPasswordWithToken() {
        if (password == null || securityToken == null) {
            throw new IllegalStateException("Both password and security token must be configured");
        }
        return password + "." + securityToken;
    }
    
    /**
     * Validates that all required configuration is present
     */
    public void validate() {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalStateException("Salesforce username is required");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalStateException("Salesforce password is required");
        }
        if (securityToken == null || securityToken.trim().isEmpty()) {
            throw new IllegalStateException("Salesforce security token is required");
        }
        if (loginUrl == null || loginUrl.trim().isEmpty()) {
            throw new IllegalStateException("Salesforce login URL is required");
        }
    }
}