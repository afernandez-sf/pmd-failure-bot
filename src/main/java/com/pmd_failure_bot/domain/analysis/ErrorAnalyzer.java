package com.pmd_failure_bot.domain.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Comparator;

/**
 * Utility class for analyzing errors in log content
 */
public class ErrorAnalyzer {

    /**
     * Common error patterns to look for in log content
     */
    private static final List<String> ERROR_PATTERNS = List.of(
        "\\bERROR\\b",
        "\\[ERROR\\]",
        "\\bFATAL\\b",
        "\\bFAILED\\b",
        "Refusing to execute",
        "Unable to get",
        "Unable to retrieve",
        "Unable to start",
        "connection error",
        "maximum retries reached",
        "Oracle not available",
        "exception",
        "timeout",
        "denied",
        "refused",
        "abort",
        "warning",
        "retry"
    );

    private ErrorAnalyzer() {
        // Private constructor to prevent instantiation
    }

    /**
     * Calculate error relevance score based on content keywords
     */
    public static int getErrorRelevanceScore(String content) {
        if (content == null) return 0;
        
        String lowerContent = content.toLowerCase();
        int score = 0;
        
        // High priority error indicators
        if (lowerContent.contains("error")) score += 10;
        if (lowerContent.contains("failed")) score += 10;
        if (lowerContent.contains("failure")) score += 10;
        if (lowerContent.contains("exception")) score += 8;
        if (lowerContent.contains("timeout")) score += 6;
        if (lowerContent.contains("denied")) score += 6;
        if (lowerContent.contains("refused")) score += 6;
        
        // Medium priority indicators
        if (lowerContent.contains("warning")) score += 3;
        if (lowerContent.contains("retry")) score += 2;
        if (lowerContent.contains("abort")) score += 5;
        
        return score;
    }

    /**
     * Extract key error messages from log content
     */
    public static String extractKeyErrors(String content) {
        if (content == null) return "";
        
        StringBuilder errors = new StringBuilder();
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            // Look for key error indicators
            if (lowerLine.contains("fatal") || 
                lowerLine.contains("error") ||
                lowerLine.contains("failed") ||
                lowerLine.contains("permission denied") ||
                lowerLine.contains("connection") && lowerLine.contains("error") ||
                lowerLine.contains("exception")) {
                
                // Truncate very long error lines
                String errorLine = line.length() > 120 ? line.substring(0, 120) + "..." : line;
                errors.append("- ").append(errorLine.trim()).append("\n");
                
                // Limit to first 5 key errors to keep it concise
                if (errors.toString().split("\n").length >= 5) break;
            }
        }
        
        return errors.toString();
    }
    
    /**
     * Extract error context from log content
     */
    public static String extractErrorContext(List<String> lines) {
        List<Pattern> errorPatterns = ERROR_PATTERNS.stream()
            .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
            .toList();
        
        Set<Integer> errorIndices = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern pattern : errorPatterns) {
                if (pattern.matcher(line).find()) {
                    errorIndices.add(i);
                    break;
                }
            }
        }
        
        if (errorIndices.isEmpty()) {
            return "";
        }
        
        // Create ranges with context
        List<int[]> ranges = new ArrayList<>();
        int contextLines = 3;
        
        for (int errorIdx : errorIndices.stream().sorted().toList()) {
            int start = Math.max(0, errorIdx - contextLines);
            int end = Math.min(lines.size(), errorIdx + contextLines + 1);
            ranges.add(new int[]{start, end});
        }
        
        // Merge overlapping ranges
        List<int[]> mergedRanges = new ArrayList<>();
        for (int[] range : ranges) {
            if (mergedRanges.isEmpty() || range[0] > mergedRanges.get(mergedRanges.size() - 1)[1]) {
                mergedRanges.add(range);
            } else {
                int[] lastRange = mergedRanges.get(mergedRanges.size() - 1);
                lastRange[1] = Math.max(lastRange[1], range[1]);
            }
        }
        
        // Extract lines from merged ranges
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < mergedRanges.size(); i++) {
            if (i > 0) {
                context.append("\n--- ERROR CONTEXT SEPARATOR ---\n");
            }
            int[] range = mergedRanges.get(i);
            for (int j = range[0]; j < range[1]; j++) {
                context.append(lines.get(j)).append("\n");
            }
        }
        
        return context.toString();
    }
}