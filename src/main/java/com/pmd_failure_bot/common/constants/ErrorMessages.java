package com.pmd_failure_bot.common.constants;

/**
 * Constants for error messages across the application
 */
public final class ErrorMessages {
    
    // Prevent instantiation
    private ErrorMessages() {}
    
    // Generic Messages
    public static final String GENERIC_ERROR_MESSAGE = "Sorry, I encountered an error processing your request. Please try again later.";
    public static final String GENERAL_ERROR_MESSAGE = "I'm sorry, I encountered an error while processing your query. Please try again later or contact support if the issue persists.";
    public static final String UNEXPECTED_ERROR_MESSAGE = "I encountered an unexpected error while processing your query. Please try again or contact support.";
    
    // Import Messages
    public static final String IMPORT_ERROR_MESSAGE = "I encountered an error while processing your import request. Please try again later or contact support if the issue persists.";
    public static final String MISSING_PARAMS_MESSAGE = "I need either a case number or step name to import logs. For example: 'Import logs for case 123456' or 'Pull logs from SSH_TO_ALL_HOSTS step'";
    
    // Intent Extraction
    public static final String INTENT_EXTRACTION_FAILURE_MESSAGE = "I couldn't determine whether you want metrics or analysis from your query. Please try rephrasing with clearer intent like 'How many...' for counts or 'Explain...' for analysis.";
    
    // Format Templates
    public static final String VALIDATION_ERROR_FORMAT = "I couldn't understand your query: %s. Please try rephrasing or provide more specific details.";
    public static final String PROCESSING_ERROR_FORMAT = "I encountered an error while processing your query: %s";
    public static final String SUCCESS_FORMAT = "✅ Import completed for %s!\n📊 Processed %d/%d attachments (%d skipped)\n📝 Imported %d logs successfully (%d failed)\n💡 You can now query with: 'What issues occurred in %s?'";
    public static final String NO_ATTACHMENTS_FORMAT = "No failed attachments found for %s. There may be no failures to import, or they might already be processed.";
    public static final String IMPORT_FAILED_FORMAT = "❌ Import failed for %s: %s\nPlease try again or contact support.";
}