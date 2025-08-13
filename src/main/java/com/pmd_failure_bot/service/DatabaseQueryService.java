package com.pmd_failure_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DatabaseQueryService {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseQueryService.class);
    
    private final SalesforceLlmGatewayService llmGatewayService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public DatabaseQueryService(SalesforceLlmGatewayService llmGatewayService, JdbcTemplate jdbcTemplate) {
        this.llmGatewayService = llmGatewayService;
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Process a natural language query by generating SQL and executing it
     */
    public DatabaseQueryResult processNaturalLanguageQuery(String userQuery) {
        try {
            // Define the database query function
            List<Map<String, Object>> tools = createDatabaseQueryTools();
            
            // Call LLM with function calling
            SalesforceLlmGatewayService.FunctionCallResponse response = 
                llmGatewayService.generateResponseWithFunctions(userQuery, tools);
            
            if (response.isFunctionCall() && "query_pmd_logs".equals(response.getFunctionName())) {
                // Parse the function arguments
                JsonNode args = objectMapper.readTree(response.getArguments());
                String sql = args.get("sql_query").asText();
                
                logger.info("🔍 FUNCTION CALLING SUCCESS! Generated SQL query: {}", sql);
                System.out.println("📊 Generated SQL: " + sql);
                
                // Validate and execute the SQL
                if (isValidReadOnlyQuery(sql)) {
                    List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

                    // Remove internal 'id' field from results if present
                    for (Map<String, Object> row : results) {
                        row.remove("id");
                    }
                    
                    // Now call LLM again to generate a natural language response
                    String naturalLanguageResponse = generateNaturalLanguageResponse(userQuery, sql, results);
                    
                    return new DatabaseQueryResult(true, results, sql, naturalLanguageResponse, null);
                } else {
                    return new DatabaseQueryResult(false, null, sql, null, "Generated query is not a valid read-only SELECT statement");
                }
            } else {
                // LLM didn't call the function, return the content as is
                return new DatabaseQueryResult(false, null, null, response.getContent(), "No database query generated");
            }
            
        } catch (Exception e) {
            logger.error("Error processing natural language query: ", e);
            return new DatabaseQueryResult(false, null, null, null, "Error processing query: " + e.getMessage());
        }
    }
    
    /**
     * Create the database query function definition
     */
    private List<Map<String, Object>> createDatabaseQueryTools() {
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        
        Map<String, Object> function = new HashMap<>();
        function.put("name", "query_pmd_logs");
        function.put("description", "Query the PMD failure logs database to find information about deployment failures, errors, and issues. " +
                                   "The database contains logs from various deployment steps with details about failures, timestamps, hostnames, and error content.");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> sqlQuery = new HashMap<>();
        sqlQuery.put("type", "string");
        sqlQuery.put("description", "A valid PostgreSQL SELECT query to search the pmd_failure_logs table. " +
                                   "Available columns: record_id, work_id, case_number, step_name, attachment_id, hostname, content (TEXT), report_date. " +
                                   "Do NOT select the internal 'id' column in results. " +
                                   "IMPORTANT: For step_name searches, always use ILIKE with % wildcards since step names have pod suffixes (e.g., 'STOP_APPS_NA123'). " +
                                   "Example: WHERE step_name ILIKE '%STOP_APPS%' or WHERE step_name ILIKE '%SSH_TO_ALL_HOSTS%'. " +
                                   "For date filtering use: report_date = 'YYYY-MM-DD' or report_date >= 'YYYY-MM-DD'. " +
                                   "Common step prefixes: GRIDFORCE_APP_LOG_COPY, SSH_TO_ALL_HOSTS, SETUP_BROKER_NEW_PRI, DB_POST_VALIDATION, STOP_APPS. " +
                                   "Always use LIMIT to avoid returning too many results (max 20).");
        
        properties.put("sql_query", sqlQuery);
        parameters.put("properties", properties);
        parameters.put("required", List.of("sql_query"));
        
        function.put("parameters", parameters);
        tool.put("function", function);
        
        return List.of(tool);
    }
    
    /**
     * Validate that the query is a safe read-only SELECT statement
     */
    private boolean isValidReadOnlyQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        
        String normalizedSql = sql.trim().toLowerCase();
        
        // Must start with SELECT
        if (!normalizedSql.startsWith("select")) {
            return false;
        }
        
        // Must contain the table name
        if (!normalizedSql.contains("pmd_failure_logs")) {
            return false;
        }
        
        // Should not contain dangerous keywords
        String[] dangerousKeywords = {
            "insert", "update", "delete", "drop", "create", "alter", "truncate", 
            "grant", "revoke", "exec", "execute", "sp_", "xp_"
        };
        
        for (String keyword : dangerousKeywords) {
            if (normalizedSql.contains(keyword)) {
                return false;
            }
        }
        
        // Should have a reasonable LIMIT
        if (!normalizedSql.contains("limit")) {
            logger.warn("Query without LIMIT clause: {}", sql);
            // We could auto-add a limit, but for safety let's require it
            return false;
        }
        
        return true;
    }
    
    /**
     * Generate a natural language response based on the query results
     */
    private String generateNaturalLanguageResponse(String originalQuery, String sql, List<Map<String, Object>> results) {
        try {
            String prompt = String.format(
                "<instructions>\n" +
                "You are an expert database analyst specialized in analyzing PMD deployment failure logs. Review the database results inside <context></context> XML tags, and answer the question inside <question></question> XML tags.\n\n" +
                "Guidelines:\n" +
                "- Focus on identifying specific error patterns, root causes, and failure points\n" +
                "- Highlight commonalities across multiple failures if present\n" +
                "- Reference specific case numbers, hostnames, or step names from the data when relevant\n" +
                "- Prioritize explaining WHAT went wrong and WHY it failed\n" +
                "- When analyzing steps, note if certain step types fail more frequently\n" +
                "- Be technical and precise with error details that would help engineers troubleshoot\n" +
                "- Provide your answer in plain text, without any formatting\n" +
                "- Respond \"Insufficient data to determine root cause.\" if the logs don't contain enough information\n" +
                "</instructions>\n\n" +
                "<context>\n" +
                "SQL Query: %s\n\n" +
                "Results (%d rows):\n%s\n" +
                "</context>\n\n" +
                "<question>\n" +
                "%s\n" +
                "</question>",
                sql,
                results.size(),
                formatResultsForLLM(results),
                originalQuery
            );
            
            return llmGatewayService.generateResponse(prompt);
            
        } catch (Exception e) {
            logger.error("Error generating natural language response: ", e);
            return String.format("Found %d matching records, but couldn't generate summary: %s", 
                                results.size(), e.getMessage());
        }
    }
    
    /**
     * Format database results for LLM consumption
     */
    private String formatResultsForLLM(List<Map<String, Object>> results) {
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
                String keyErrors = extractKeyErrors(contentStr);
                
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
    private List<Map<String, Object>> rankResultsByRelevance(List<Map<String, Object>> results) {
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
    private List<Map<String, Object>> rankWithStepNameDiversity(List<Map<String, Object>> results) {
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
    private int getResultErrorScore(Map<String, Object> result) {
        Object content = result.get("content");
        if (content == null) return 0;
        
        String contentStr = content.toString().toLowerCase();
        return getErrorRelevanceScore(contentStr);
    }
    
    /**
     * Check if content has error keywords
     */
    private boolean hasErrorKeywords(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return lower.contains("error") || lower.contains("failed") || 
               lower.contains("failure") || lower.contains("exception");
    }
    
    /**
     * Calculate error relevance score based on content keywords
     */
    private int getErrorRelevanceScore(String content) {
        if (content == null) return 0;
        
        int score = 0;
        
        // High priority error indicators
        if (content.contains("error")) score += 10;
        if (content.contains("failed")) score += 10;
        if (content.contains("failure")) score += 10;
        if (content.contains("exception")) score += 8;
        if (content.contains("timeout")) score += 6;
        if (content.contains("denied")) score += 6;
        if (content.contains("refused")) score += 6;
        
        // Medium priority indicators
        if (content.contains("warning")) score += 3;
        if (content.contains("retry")) score += 2;
        if (content.contains("abort")) score += 5;
        
        return score;
    }
    
    private void appendField(StringBuilder sb, String fieldName, Object value) {
        if (value != null) {
            sb.append("  ").append(fieldName).append(": ").append(value).append("\n");
        }
    }
    
    /**
     * Result class for database queries
     */
    public static class DatabaseQueryResult {
        private final boolean successful;
        private final List<Map<String, Object>> results;
        private final String sqlQuery;
        private final String naturalLanguageResponse;
        private final String errorMessage;
        
        public DatabaseQueryResult(boolean successful, List<Map<String, Object>> results, 
                                 String sqlQuery, String naturalLanguageResponse, String errorMessage) {
            this.successful = successful;
            this.results = results;
            this.sqlQuery = sqlQuery;
            this.naturalLanguageResponse = naturalLanguageResponse;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccessful() { return successful; }
        public List<Map<String, Object>> getResults() { return results; }
        public String getSqlQuery() { return sqlQuery; }
        public String getNaturalLanguageResponse() { return naturalLanguageResponse; }
        public String getErrorMessage() { return errorMessage; }
        public int getResultCount() { return results != null ? results.size() : 0; }
    }
    
    /**
     * Helper method to safely get field values
     */
    private String getFieldValue(Map<String, Object> row, String fieldName) {
        Object value = row.get(fieldName);
        return value != null ? value.toString() : "N/A";
    }
    
    /**
     * Extract key error messages from log content
     */
    private String extractKeyErrors(String content) {
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
}