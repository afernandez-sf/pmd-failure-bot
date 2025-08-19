package com.pmd_failure_bot.common.constants;

/**
 * Constants for database operations and queries
 */
public final class DatabaseConstants {
    
    // Prevent instantiation
    private DatabaseConstants() {}
    
    // Function Names
    public static final String FUNCTION_METRICS = "metrics_pmd_logs";
    public static final String FUNCTION_ANALYSIS = "analyze_pmd_logs";
    public static final String FUNCTION_QUERY = "query_pmd_logs";
    
    // Retry Configuration
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long INITIAL_BACKOFF_MS = 300L;
}