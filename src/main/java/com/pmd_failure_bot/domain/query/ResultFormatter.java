package com.pmd_failure_bot.domain.query;

import com.pmd_failure_bot.domain.analysis.ErrorAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for formatting query results for presentation
 */
public class ResultFormatter {

    private ResultFormatter() {
        // Private constructor to prevent instantiation
    }

    /**
     * Format database results for LLM consumption with intelligent ranking
     */
    public static String formatResultsForLLM(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "No results found.";
        }
        
        // Apply intelligent ranking and limiting
        List<Map<String, Object>> rankedResults = rankResultsByRelevance(results);
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Total results: %d, showing top %d most relevant:\n\n", 
                                results.size(), rankedResults.size()));
        
        int maxTotalLength = 50000; // Conservative limit for this stage
        int currentLength = sb.length();
        
        for (int i = 0; i < rankedResults.size(); i++) {
            Map<String, Object> row = rankedResults.get(i);
            
            StringBuilder rowSection = new StringBuilder();
            
            // Compact header with essential info
            String stepName = getFieldValue(row, "step_name");
            String caseNumber = getFieldValue(row, "case_number");
            String date = getFieldValue(row, "report_date");
            String hostname = getFieldValue(row, "hostname");
            
            rowSection.append(String.format("## %s | Case %s | %s | %s\n", 
                                          stepName, caseNumber, date, hostname));
            
            // Extract key error messages instead of full content
            Object content = row.get("content");
            if (content != null) {
                String contentStr = content.toString();
                String keyErrors = ErrorAnalyzer.extractKeyErrors(contentStr);
                
                if (!keyErrors.isEmpty()) {
                    rowSection.append("**Key Errors:**\n").append(keyErrors);
                } else {
                    // Fallback to truncated content if no specific errors found
                    int maxContentLength = 300;
                    if (contentStr.length() > maxContentLength) {
                        contentStr = contentStr.substring(0, maxContentLength) + "...";
                    }
                    rowSection.append("Content: ").append(contentStr);
                }
            }
            
            rowSection.append("\n");
            
            // Check if adding this row would exceed total limit
            if (currentLength + rowSection.length() > maxTotalLength) {
                sb.append(String.format("... %d additional results omitted due to token limits\n", 
                                      rankedResults.size() - i));
                break;
            }
            
            sb.append(rowSection);
            currentLength += rowSection.length();
        }
        
        return sb.toString();
    }
    
    /**
     * Rank results by relevance with step name diversity prioritization
     */
    private static List<Map<String, Object>> rankResultsByRelevance(List<Map<String, Object>> results) {
        // For broad queries, prioritize step name diversity to show different types of failures
        if (results.size() > 10) {
            return rankWithStepNameDiversity(results);
        }
        
        // For smaller result sets, use standard relevance ranking
        return results.stream()
            .sorted((r1, r2) -> {
                // Primary: Error relevance score
                int score1 = getResultErrorScore(r1);
                int score2 = getResultErrorScore(r2);
                int scoreCompare = Integer.compare(score2, score1);
                if (scoreCompare != 0) return scoreCompare;
                
                // Secondary: Most recent first
                Object date1 = r1.get("report_date");
                Object date2 = r2.get("report_date");
                if (date1 != null && date2 != null) {
                    return date2.toString().compareTo(date1.toString());
                }
                
                // Tertiary: Content length (more detailed first)
                Object content1 = r1.get("content");
                Object content2 = r2.get("content");
                int len1 = content1 != null ? content1.toString().length() : 0;
                int len2 = content2 != null ? content2.toString().length() : 0;
                return Integer.compare(len2, len1);
            })
            .limit(15)
            .collect(Collectors.toList());
    }
    
    /**
     * Rank results prioritizing step name diversity for broad queries
     */
    private static List<Map<String, Object>> rankWithStepNameDiversity(List<Map<String, Object>> results) {
        // Group by step name and select the best representative from each step
        Map<String, List<Map<String, Object>>> byStepName = results.stream()
            .collect(Collectors.groupingBy(result -> {
                Object stepName = result.get("step_name");
                return stepName != null ? stepName.toString() : "UNKNOWN";
            }));
        
        List<Map<String, Object>> diverseResults = new ArrayList<>();
        
        // Get the best representative from each step name (up to 2 per step for variety)
        byStepName.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size())) // Steps with more failures first
            .forEach(entry -> {
                List<Map<String, Object>> stepResults = entry.getValue().stream()
                    .sorted((r1, r2) -> {
                        // Within each step, rank by error score and recency
                        int score1 = getResultErrorScore(r1);
                        int score2 = getResultErrorScore(r2);
                        int scoreCompare = Integer.compare(score2, score1);
                        if (scoreCompare != 0) return scoreCompare;
                        
                        Object date1 = r1.get("report_date");
                        Object date2 = r2.get("report_date");
                        if (date1 != null && date2 != null) {
                            return date2.toString().compareTo(date1.toString());
                        }
                        return 0;
                    })
                    .limit(2) // Take top 2 from each step for variety
                    .collect(Collectors.toList());
                
                diverseResults.addAll(stepResults);
            });
        
        // Final sort by overall relevance and limit
        return diverseResults.stream()
            .sorted((r1, r2) -> {
                int score1 = getResultErrorScore(r1);
                int score2 = getResultErrorScore(r2);
                return Integer.compare(score2, score1);
            })
            .limit(15)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate error relevance score for a database result
     */
    private static int getResultErrorScore(Map<String, Object> result) {
        Object content = result.get("content");
        if (content == null) return 0;
        
        String contentStr = content.toString().toLowerCase();
        return ErrorAnalyzer.getErrorRelevanceScore(contentStr);
    }
    
    /**
     * Helper method to safely get field values
     */
    private static String getFieldValue(Map<String, Object> row, String fieldName) {
        Object value = row.get(fieldName);
        return value != null ? value.toString() : "N/A";
    }
}