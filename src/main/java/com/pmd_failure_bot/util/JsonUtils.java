package com.pmd_failure_bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonUtils {
    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);
    private JsonUtils() {}

    public static String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "{}";
        }
        if (response.contains("```json")) {
            int jsonStart = response.indexOf("```json") + 7;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                String content = response.substring(start, end).trim();
                if (content.startsWith("{") || content.startsWith("[")) {
                    return content;
                }
            }
        }
        int startBrace = response.indexOf('{');
        int endBrace = response.lastIndexOf('}');
        if (startBrace >= 0 && endBrace > startBrace) {
            return response.substring(startBrace, endBrace + 1);
        }
        logger.warn("Could not extract JSON from response: {}", response);
        return response;
    }
}


