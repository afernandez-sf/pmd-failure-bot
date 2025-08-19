package com.pmd_failure_bot.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.integration.ai.AIService;
import com.pmd_failure_bot.integration.ai.PromptTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseQueryService {

    private static final String FUNCTION_METRICS = "metrics_pmd_logs";
    private static final String FUNCTION_ANALYSIS = "analyze_pmd_logs";
    private static final String FUNCTION_QUERY = "query_pmd_logs";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 300;

    private final AIService aiService;
    private final JdbcTemplate jdbcTemplate;
    private final PromptTemplates promptTemplates;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatabaseQueryResult processNaturalLanguageQuery(String userQuery, String preferredIntent) {
        try {
            List<Map<String, Object>> tools = determineTools(preferredIntent);
            AIService.FunctionCallResponse response = aiService.generateWithFunctions(userQuery, tools);
            return processQueryResponse(userQuery, response);
        } catch (Exception e) {
            log.error("Error processing natural language query: ", e);
            return new DatabaseQueryResult(false, null, null, null, "Error processing query: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> determineTools(String preferredIntent) {
        if (preferredIntent != null) {
            String intent = preferredIntent.toLowerCase(Locale.ROOT);
            if ("metrics".equals(intent)) return createMetricsTools();
            if ("analysis".equals(intent)) return createAnalysisTools();
        }
        return createDatabaseQueryTools();
    }

    private DatabaseQueryResult processQueryResponse(String userQuery, AIService.FunctionCallResponse response) throws Exception {
        if (response.isFunctionCall() && isValidFunctionName(response.getFunctionName())) {
            JsonNode args = objectMapper.readTree(response.getArguments());
            String sql = args.get("sql_query").asText();
            log.info("🔍 FUNCTION CALLING SUCCESS! Generated SQL query: {}", sql);
            
            if (isValidReadOnlyQuery(sql)) {
                List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
                for (Map<String, Object> row : results) {
                    row.remove("id");
                }
                if (FUNCTION_ANALYSIS.equals(response.getFunctionName())) {
                    decodeContentColumn(results);
                }
                String nl = generateNaturalLanguageResponse(userQuery, sql, results, response.getFunctionName());
                return new DatabaseQueryResult(true, results, sql, nl, null);
            } else {
                return new DatabaseQueryResult(false, null, sql, null, "Generated query is not a valid read-only SELECT statement");
            }
        } else {
            return new DatabaseQueryResult(false, null, null, response.getContent(), "No database query generated");
        }
    }

    private boolean isValidFunctionName(String functionName) {
        return FUNCTION_METRICS.equals(functionName) || FUNCTION_ANALYSIS.equals(functionName) || FUNCTION_QUERY.equals(functionName);
    }

    private List<Map<String, Object>> createDatabaseQueryTools() {
        return List.of(createMetricsTool(), createAnalysisTool());
    }

    private Map<String, Object> createMetricsTool() {
        return createFunctionTool(
            FUNCTION_METRICS,
            "Generate a read-only SELECT for counts, breakdowns, and trends over pmd_failure_logs. Include a numeric aggregate (e.g., COUNT(*) AS failures). Choose the GROUP BY dimension that matches the question intent (e.g., step_name for \"different errors\", date for \"by month/day\", datacenter when asked). If the user asks for months, group by date_trunc('month', report_date) AS month and present month. Do NOT select the heavy 'content' column. MUST include LIMIT clause with value <= 500.",
            "PostgreSQL SELECT over pmd_failure_logs with aggregates and GROUP BY as needed. Columns available: record_id, work_id, case_number, step_name, attachment_id, datacenter, report_date. Do NOT select 'id' or 'content'. REQUIRED: Must end with LIMIT clause (e.g., LIMIT 500)."
        );
    }

    private Map<String, Object> createAnalysisTool() {
        return createFunctionTool(
            FUNCTION_ANALYSIS,
            "Generate a read-only SELECT to fetch representative failure logs (including 'content') for explanatory analysis. Select fields: record_id, work_id, step_name, case_number, datacenter, report_date, content. Filter by step_name/date/case when provided. Order by recency or relevance. LIMIT <= 10.",
            "PostgreSQL SELECT over pmd_failure_logs selecting record_id, work_id, step_name, case_number, datacenter, report_date, content. Do NOT select 'id'. LIMIT <= 10."
        );
    }

    private Map<String, Object> createFunctionTool(String name, String description, String sqlDescription) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        
        Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("strict", true);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("required", List.of("sql_query"));
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> sqlProperty = new HashMap<>();
        sqlProperty.put("type", "string");
        sqlProperty.put("description", sqlDescription);
        properties.put("sql_query", sqlProperty);
        parameters.put("properties", properties);
        
        function.put("parameters", parameters);
        tool.put("function", function);
        
        return tool;
    }

    private List<Map<String, Object>> createMetricsTools() {
        return List.of(createMetricsTool());
    }

    private List<Map<String, Object>> createAnalysisTools() {
        return List.of(createAnalysisTool());
    }

    private boolean isValidReadOnlyQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) return false;
        String normalizedSql = sql.trim().toLowerCase();
        if (!normalizedSql.startsWith("select")) return false;
        if (!normalizedSql.contains("pmd_failure_logs")) return false;
        String[] dangerous = {"insert","update","delete","drop","create","alter","truncate","grant","revoke","exec","execute","sp_","xp_"};
        for (String k : dangerous) {
            if (normalizedSql.contains(k)) return false;
        }
        if (!normalizedSql.contains("limit")) {
            log.warn("Query without LIMIT clause: {}", sql);
            return false;
        }
        return true;
    }

    private String generateNaturalLanguageResponse(String originalQuery, String sql, List<Map<String, Object>> results, String functionName) {
        try {
            String formatted = formatResultsForLLM(results);
            String prompt = FUNCTION_ANALYSIS.equals(functionName) ? 
                promptTemplates.nlErrorSummary(originalQuery, sql, formatted) : 
                promptTemplates.nlSummary(originalQuery, sql, formatted, results.size());
            String response = generateWithRetry(prompt);
            
            // Ensure we never return null or empty response
            if (response == null || response.trim().isEmpty()) {
                log.warn("LLM returned null/empty response, using fallback");
                return generateFallbackResponse(originalQuery, results, functionName);
            }
            
            return response;
        } catch (Exception e) {
            log.error("Error generating natural language response: ", e);
            return generateFallbackResponse(originalQuery, results, functionName);
        }
    }
    
    private String generateFallbackResponse(String originalQuery, List<Map<String, Object>> results, String functionName) {
        if (FUNCTION_ANALYSIS.equals(functionName)) {
            return String.format("Found %d failure records for analysis. Unfortunately, I couldn't generate a detailed summary at this time.", results.size());
        } else {
            // For metrics queries, try to extract basic info from results
            if (results.isEmpty()) {
                return "No matching records found for your query.";
            }
            
            // Try to extract the count from first result
            Map<String, Object> firstResult = results.get(0);
            String countInfo = "";
            for (Map.Entry<String, Object> entry : firstResult.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    countInfo = String.format("Found %s: %s", entry.getKey(), entry.getValue());
                    break;
                }
            }
            
            if (countInfo.isEmpty()) {
                return String.format("Found %d matching records. Unfortunately, I couldn't generate a detailed summary at this time.", results.size());
            } else {
                return String.format("%s. Unfortunately, I couldn't generate a detailed summary at this time.", countInfo);
            }
        }
    }

    private String generateWithRetry(String prompt) throws Exception {
        int attempts = 0;
        long backoff = INITIAL_BACKOFF_MS;
        Exception lastException = null;
        
        log.debug("Generating natural language response with prompt: {}", prompt);
        
        while (attempts < MAX_RETRY_ATTEMPTS) {
            try {
                String response = aiService.generate(prompt);
                log.debug("LLM response (attempt {}): {}", attempts + 1, response);
                
                if (response == null || response.trim().isEmpty()) {
                    log.warn("LLM returned null or empty response on attempt {}", attempts + 1);
                    throw new RuntimeException("LLM returned null or empty response");
                }
                
                return response;
            } catch (Exception e) {
                log.warn("LLM generation failed on attempt {}: {}", attempts + 1, e.getMessage());
                lastException = e;
                attempts++;
                if (attempts >= MAX_RETRY_ATTEMPTS) break;
                
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff *= 2;
            }
        }
        
        log.error("All LLM generation attempts failed after {} tries", MAX_RETRY_ATTEMPTS);
        throw lastException;
    }

    private String formatResultsForLLM(List<Map<String, Object>> results) {
        return ResultFormatter.formatResultsForLLM(results);
    }

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
            byte[] bytes;
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
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes); java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais)) {
                byte[] decompressed = gis.readAllBytes();
                return new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception notGzip) {
                try {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                catch (Exception e2) {
                    log.warn("Failed to decode content bytes; returning empty string: {}", e2.getMessage());
                    return "";
                }
            }
        } catch (Exception e) {
            log.warn("Error decoding content column: {}", e.getMessage());
            return "";
        }
    }

    public record DatabaseQueryResult(boolean successful, List<Map<String, Object>> results, String sqlQuery, String naturalLanguageResponse, String errorMessage) {
        public int getResultCount() {
            return results != null ? results.size() : 0;
        }
    }
}


