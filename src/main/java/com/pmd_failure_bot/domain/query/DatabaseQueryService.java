package com.pmd_failure_bot.domain.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            
            if (response.isFunctionCall() && ("metrics_pmd_logs".equals(response.getFunctionName()) || "analyze_pmd_logs".equals(response.getFunctionName()) || "query_pmd_logs".equals(response.getFunctionName()))) {
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

                    // Decode BYTEA 'content' to text for analysis tool
                    if ("analyze_pmd_logs".equals(response.getFunctionName())) {
                        decodeContentColumn(results);
                    }
                    
                    // Call LLM again to generate a natural language response
                    String naturalLanguageResponse = generateNaturalLanguageResponse(userQuery, sql, results, response.getFunctionName());
                    
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
     * Process a natural language query with a preferred intent for routing ("metrics" or "analysis").
     */
    public DatabaseQueryResult processNaturalLanguageQuery(String userQuery, String preferredIntent) {
        try {
            List<Map<String, Object>> tools;
            if (preferredIntent != null) {
                String intent = preferredIntent.toLowerCase(Locale.ROOT);
                if ("metrics".equals(intent)) {
                    tools = createMetricsTools();
                } else if ("analysis".equals(intent)) {
                    tools = createAnalysisTools();
                } else {
                    tools = createDatabaseQueryTools();
                }
            } else {
                tools = createDatabaseQueryTools();
            }

            AIService.FunctionCallResponse response = aiService.generateWithFunctions(userQuery, tools);

            if (response.isFunctionCall()) {
                JsonNode args = objectMapper.readTree(response.getArguments());
                String sql = args.get("sql_query").asText();

                logger.info("🔍 FUNCTION CALLING SUCCESS! Generated SQL query: {}", sql);

                if (isValidReadOnlyQuery(sql)) {
                    List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
                    for (Map<String, Object> row : results) {
                        row.remove("id");
                    }

                    // Decode BYTEA 'content' to text for analysis tool
                    if ("analyze_pmd_logs".equals(response.getFunctionName())) {
                        decodeContentColumn(results);
                    }

                    String naturalLanguageResponse = generateNaturalLanguageResponse(userQuery, sql, results, response.getFunctionName());
                    return new DatabaseQueryResult(true, results, sql, naturalLanguageResponse, null);
                } else {
                    return new DatabaseQueryResult(false, null, sql, null, "Generated query is not a valid read-only SELECT statement");
                }
            } else {
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
        // Metrics / aggregation tool
        Map<String, Object> metricsTool = new HashMap<>();
        metricsTool.put("type", "function");
        Map<String, Object> metricsFn = new HashMap<>();
        metricsFn.put("name", "metrics_pmd_logs");
        metricsFn.put("description", "Generate a read-only SELECT for counts, breakdowns, and trends over pmd_failure_logs. Prefer GROUP BY step_name (and/or report_date) when the user asks about different failures or a breakdown. Include an aggregated numeric column (e.g., COUNT(*) AS failures). Do NOT select the heavy 'content' column. Use LIMIT <= 500.");
        Map<String, Object> metricsParams = new HashMap<>();
        metricsParams.put("type", "object");
        Map<String, Object> metricsProps = new HashMap<>();
        Map<String, Object> metricsSql = new HashMap<>();
        metricsSql.put("type", "string");
        metricsSql.put("description", "PostgreSQL SELECT over pmd_failure_logs with aggregates and GROUP BY as needed. Columns available: record_id, work_id, case_number, step_name, attachment_id, datacenter, report_date. Do NOT select 'id' or 'content'.");
        metricsProps.put("sql_query", metricsSql);
        metricsParams.put("properties", metricsProps);
        metricsParams.put("required", List.of("sql_query"));
        metricsFn.put("parameters", metricsParams);
        metricsTool.put("function", metricsFn);

        // Analysis tool (retrieve representative error content for explanation)
        Map<String, Object> analysisTool = new HashMap<>();
        analysisTool.put("type", "function");
        Map<String, Object> analysisFn = new HashMap<>();
        analysisFn.put("name", "analyze_pmd_logs");
        analysisFn.put("description", "Generate a read-only SELECT to fetch representative failure logs (including 'content') for explanatory analysis. Select fields: record_id, work_id, step_name, case_number, datacenter, report_date, content. Filter by step_name/date/case when provided. Order by recency or relevance. LIMIT <= 10.");
        Map<String, Object> analysisParams = new HashMap<>();
        analysisParams.put("type", "object");
        Map<String, Object> analysisProps = new HashMap<>();
        Map<String, Object> analysisSql = new HashMap<>();
        analysisSql.put("type", "string");
        analysisSql.put("description", "PostgreSQL SELECT over pmd_failure_logs selecting record_id, work_id, step_name, case_number, datacenter, report_date, content. Do NOT select 'id'. LIMIT <= 10.");
        analysisProps.put("sql_query", analysisSql);
        analysisParams.put("properties", analysisProps);
        analysisParams.put("required", List.of("sql_query"));
        analysisFn.put("parameters", analysisParams);
        analysisTool.put("function", analysisFn);

        return List.of(metricsTool, analysisTool);
    }

    private List<Map<String, Object>> createMetricsTools() {
        return List.of(createDatabaseQueryTools().get(0));
    }

    private List<Map<String, Object>> createAnalysisTools() {
        return List.of(createDatabaseQueryTools().get(1));
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
    private String generateNaturalLanguageResponse(String originalQuery, String sql, List<Map<String, Object>> results, String functionName) {
        try {
            String formatted = formatResultsForLLM(results);
            String prompt = "analyze_pmd_logs".equals(functionName)
                ? promptTemplates.nlErrorSummary(originalQuery, sql, formatted, results.size())
                : promptTemplates.nlSummary(originalQuery, sql, formatted, results.size());
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
     * Decode and decompress BYTEA 'content' values to UTF-8 strings for analysis queries.
     */
    private void decodeContentColumn(List<Map<String, Object>> results) {
        for (Map<String, Object> row : results) {
            if (row.containsKey("content")) {
                Object raw = row.get("content");
                String decoded = tryDecodeContent(raw);
                row.put("content", decoded);
            }
        }
    }

    private String tryDecodeContent(Object value) {
        if (value == null) return "";
        try {
            byte[] bytes = null;
            if (value instanceof byte[]) {
                bytes = (byte[]) value;
            } else if (value instanceof java.sql.Blob) {
                java.sql.Blob b = (java.sql.Blob) value;
                bytes = b.getBytes(1, (int) b.length());
            } else if (value instanceof String) {
                return (String) value;
            } else {
                return String.valueOf(value);
            }

            if (bytes == null || bytes.length == 0) return "";

            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
                 java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais)) {
                byte[] decompressed = gis.readAllBytes();
                return new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception notGzip) {
                try {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e2) {
                    logger.warn("Failed to decode content bytes; returning empty string: {}", e2.getMessage());
                    return "";
                }
            }
        } catch (Exception e) {
            logger.warn("Error decoding content column: {}", e.getMessage());
            return "";
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
}