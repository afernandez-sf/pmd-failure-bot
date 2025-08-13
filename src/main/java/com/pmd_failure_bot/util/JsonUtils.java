package com.pmd_failure_bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for JSON processing
 */
public class JsonUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);
    
    private JsonUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Extracts JSON from LLM response that might contain markdown code blocks or other text
     */
    public static String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "{}";
        }
        
        // First, try to extract from markdown code blocks
        if (response.contains("```json")) {
            int jsonStart = response.indexOf("```json") + 7; // Skip "```json"
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        
        // Second, try with just "```" (language unspecified)
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                String content = response.substring(start, end).trim();
                // Check if content looks like JSON (starts with { or [)
                if (content.startsWith("{") || content.startsWith("[")) {
                    return content;
                }
            }
        }
        
        // Fallback: Find JSON object in the response
        int startBrace = response.indexOf('{');
        int endBrace = response.lastIndexOf('}');
        
        if (startBrace >= 0 && endBrace > startBrace) {
            return response.substring(startBrace, endBrace + 1);
        }
        
        logger.warn("Could not extract JSON from response: {}", response);
        return response;
    }
}