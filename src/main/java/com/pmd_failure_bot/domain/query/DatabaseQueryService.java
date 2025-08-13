package com.pmd_failure_bot.domain.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.domain.analysis.ErrorAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pmd_failure_bot.infrastructure.ai.AIService;
import com.pmd_failure_bot.infrastructure.ai.PromptTemplates;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for database query processing and result formatting
 */
@Service
public class DatabaseQueryService {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseQueryService.class);
    
    private final AIService aiService;
    private final JdbcTemplate jdbcTemplate;
    private final PromptTemplates promptTemplates;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public DatabaseQueryService(AIService aiService, JdbcTemplate jdbcTemplate, PromptTemplates promptTemplates) {
        this.aiService = aiService;
        this.jdbcTemplate = jdbcTemplate;
        this.promptTemplates = promptTemplates;
    }
    
    /**
     * Process a natural language query by generating SQL and executing it
     */
    public DatabaseQueryResult processNaturalLanguageQuery(String userQuery) {
        try {
            // Define the database query function
            List<Map<String, Object>> tools = createDatabaseQueryTools();
            
            // Call LLM with function calling
            AIService.FunctionCallResponse response = aiService.generateWithFunctions(userQuery, tools);
            
            if (response.isFunctionCall() && "query_pmd_logs".equals(response.getFunctionName())) {
                // Parse the function arguments
                JsonNode args = objectMapper.readTree(response.getArguments());
                String sql = args.get("sql_query").asText();
                
                logger.info("🔍 FUNCTION CALLING SUCCESS! Generated SQL query: {}", sql);
                
                // Validate and execute the SQL
                if (isValidReadOnlyQuery(sql)) {
                    List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
                    
                    // Remove internal 'id' field from results if present
                    for (Map<String, Object> row : results) {
                        row.remove("id");
                    }
                    
                    // Call LLM again to generate a natural language response
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
            String prompt = promptTemplates.nlSummary(originalQuery, sql, formatResultsForLLM(results), results.size());
            return aiService.generate(prompt);
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
        return ResultFormatter.formatResultsForLLM(results);
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
}