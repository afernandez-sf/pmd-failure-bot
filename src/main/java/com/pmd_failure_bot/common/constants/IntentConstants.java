package com.pmd_failure_bot.common.constants;

/**
 * Constants for intent classification and response modes
 */
public final class IntentConstants {
    
    // Prevent instantiation
    private IntentConstants() {}
    
    // Intent Types
    public static final String INTENT_IMPORT = "import";
    public static final String INTENT_METRICS = "metrics";
    public static final String INTENT_ANALYSIS = "analysis";
    
    // Response Modes
    public static final String RESPONSE_MODE_METRICS = "metrics";
    public static final String RESPONSE_MODE_ANALYSIS = "analysis";
}