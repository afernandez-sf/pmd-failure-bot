package com.pmd_failure_bot.common.constants;

/**
 * Constants for Slack integration
 */
public final class SlackConstants {
    
    // Prevent instantiation
    private SlackConstants() {}
    
    // Channels & Patterns
    public static final String DIRECT_MESSAGE_CHANNEL_PREFIX = "D";
    public static final String MENTION_PATTERN = "<@[A-Z0-9]+>";
    
    // Reactions
    public static final String PROCESSING_REACTION = "eyes";
    public static final String SUCCESS_REACTION = "white_check_mark";
    public static final String ERROR_REACTION = "x";
    public static final String BLOCKED_REACTION = "no_entry";
    public static final String IMPORT_PROCESSING_REACTION = "arrows_counterclockwise";
    
    // Threading
    public static final int THREAD_POOL_SIZE = 12;
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    
    // Multi-line Messages
    public static final String IRRELEVANT_QUERY_MESSAGE = """
            This request doesn't look related to PMD failure logs or deployments.
            
            *Try one of these examples:*
            • What went wrong with case 123456?
            • Explain SSH_TO_ALL_HOSTS failures from last week
            • How many GRIDFORCE_APP_LOG_COPY failures in May 2025?""";
            
    public static final String NO_INTENT_ERROR_MESSAGE = """
            *Failed to extract intent*: I couldn't determine whether you want metrics or analysis from your query.
            
            *Try phrasing like:*
            • 'How many ...' for counts/metrics
            • 'Explain ...' or 'What went wrong ...' for analysis""";
            
    public static final String USAGE_EXAMPLES = """
            *Try natural language queries like:*
            • `What went wrong with case 123456?`
            • `Show me SSH failures from yesterday`
            • `Why did the GridForce deployment fail on CS58?`""";
}