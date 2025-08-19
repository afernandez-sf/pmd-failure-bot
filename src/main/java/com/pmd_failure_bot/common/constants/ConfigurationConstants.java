package com.pmd_failure_bot.common.constants;

/**
 * Constants for application configuration
 */
public final class ConfigurationConstants {
    
    // Prevent instantiation
    private ConfigurationConstants() {}
    
    // Environment Variables
    public static final String SLACK_BOT_TOKEN = "SLACK_BOT_TOKEN";
    public static final String SLACK_APP_TOKEN = "SLACK_APP_TOKEN";
    
    // Health Check
    public static final String HEALTH_MESSAGE = "Natural Language Query Tool is operational";
}