package com.pmd_failure_bot.common.constants;

/**
 * Constants for response formatting and JSON processing
 */
public final class ResponseFormattingConstants {
    
    // Prevent instantiation
    private ResponseFormattingConstants() {}
    
    // Length Limits
    public static final int MAX_TOTAL_LENGTH = 50000;
    public static final int MAX_CONTENT_LENGTH = 300;
    public static final int MAX_TABLE_ROWS = 100;
    public static final int MAX_JSON_ROWS = 200;
    public static final int MAX_RANKED_RESULTS = 15;
    public static final int MAX_RESULTS_PER_STEP = 2;
    public static final int NUMERIC_SCAN_LIMIT = 50;
    public static final int DIVERSITY_THRESHOLD = 10;
    
    // Confidence Values
    public static final double SUCCESS_CONFIDENCE = 1.0;
    public static final double ERROR_CONFIDENCE = 0.0;
    
    // JSON Formatting
    public static final String JSON_CODE_BLOCK_START = "```json";
    public static final String CODE_BLOCK_DELIMITER = "```";
    public static final int JSON_CODE_BLOCK_START_LENGTH = JSON_CODE_BLOCK_START.length();
    public static final int CODE_BLOCK_DELIMITER_LENGTH = CODE_BLOCK_DELIMITER.length();
}