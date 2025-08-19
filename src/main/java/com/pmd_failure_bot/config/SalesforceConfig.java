package com.pmd_failure_bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "salesforce")
public class SalesforceConfig {
    
    private String username;
    private String password;
    private String securityToken;
    private String loginUrl;
    private String apiVersion;
    private int maxAttachmentSize;
    private int maxRetries;
    private int retryDelaySeconds;
    
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