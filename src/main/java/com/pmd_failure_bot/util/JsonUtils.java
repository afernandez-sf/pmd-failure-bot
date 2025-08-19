package com.pmd_failure_bot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class JsonUtils {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String JSON_CODE_BLOCK_START = "```json";
    private static final String CODE_BLOCK_DELIMITER = "```";
    private static final int JSON_CODE_BLOCK_START_LENGTH = JSON_CODE_BLOCK_START.length();
    private static final int CODE_BLOCK_DELIMITER_LENGTH = CODE_BLOCK_DELIMITER.length();

    public static String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "{}";
        }
        
        // Try to extract from ```json code blocks first
        String extracted = extractFromJsonCodeBlock(response);
        if (extracted != null) {
            return extracted;
        }
        
        // Try to extract from generic ``` code blocks
        extracted = extractFromGenericCodeBlock(response);
        if (extracted != null) {
            return extracted;
        }
        
        // Try to extract JSON object or array from the response
        extracted = extractJsonFromText(response);
        if (extracted != null) {
            return extracted;
        }
        
        log.warn("Could not extract valid JSON from response: {}", response);
        return "{}";
    }
    
    private static String extractFromJsonCodeBlock(String response) {
        if (!response.contains(JSON_CODE_BLOCK_START)) {
            return null;
        }
        
        int jsonStart = response.indexOf(JSON_CODE_BLOCK_START) + JSON_CODE_BLOCK_START_LENGTH;
        int jsonEnd = response.indexOf(CODE_BLOCK_DELIMITER, jsonStart);
        
        if (jsonEnd > jsonStart) {
            String content = response.substring(jsonStart, jsonEnd).trim();
            return isValidJson(content) ? content : null;
        }
        
        return null;
    }
    
    private static String extractFromGenericCodeBlock(String response) {
        if (!response.contains(CODE_BLOCK_DELIMITER)) {
            return null;
        }
        
        int start = response.indexOf(CODE_BLOCK_DELIMITER) + CODE_BLOCK_DELIMITER_LENGTH;
        int end = response.indexOf(CODE_BLOCK_DELIMITER, start);
        
        if (end > start) {
            String content = response.substring(start, end).trim();
            if ((content.startsWith("{") || content.startsWith("[")) && isValidJson(content)) {
                return content;
            }
        }
        
        return null;
    }
    
    private static String extractJsonFromText(String response) {
        // Try to find JSON object
        String objectJson = findCompleteJsonObject(response);
        if (isValidJson(objectJson)) {
            return objectJson;
        }
        
        // Try to find JSON array
        String arrayJson = findCompleteJsonArray(response);
        if (isValidJson(arrayJson)) {
            return arrayJson;
        }
        
        return null;
    }
    
    private static String findCompleteJsonObject(String response) {
        return findCompleteJsonStructure(response, '{', '}');
    }
    
    private static String findCompleteJsonArray(String response) {
        return findCompleteJsonStructure(response, '[', ']');
    }
    
    private static String findCompleteJsonStructure(String response, char openChar, char closeChar) {
        int startIndex = response.indexOf(openChar);
        if (startIndex == -1) {
            return null;
        }
        
        int bracketCount = 0;
        int endIndex = -1;
        boolean inString = false;
        boolean escaped = false;
        
        for (int i = startIndex; i < response.length(); i++) {
            char c = response.charAt(i);
            
            if (escaped) {
                escaped = false;
                continue;
            }
            
            if (c == '\\') {
                escaped = true;
                continue;
            }
            
            if (c == '"') {
                inString = !inString;
                continue;
            }
            
            if (!inString) {
                if (c == openChar) {
                    bracketCount++;
                } else if (c == closeChar) {
                    bracketCount--;
                    if (bracketCount == 0) {
                        endIndex = i;
                        break;
                    }
                }
            }
        }
        
        if (endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }
        
        return null;
    }
    
    private static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null;
        } catch (Exception e) {
            return false;
        }
    }
}
