package com.pmd_failure_bot.common.constants;

import java.util.regex.Pattern;

/**
 * Constants for error analysis and pattern matching
 */
public final class AnalysisConstants {
    
    // Prevent instantiation
    private AnalysisConstants() {}
    
    // Context & Limits
    public static final int CONTEXT_LINES_BEFORE = 3;
    public static final int TOTAL_CHAR_LIMIT = 8000;
    
    // Regex Patterns
    public static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    public static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}.*");
}