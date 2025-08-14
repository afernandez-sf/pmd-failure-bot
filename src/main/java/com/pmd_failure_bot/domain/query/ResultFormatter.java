package com.pmd_failure_bot.domain.query;

import com.pmd_failure_bot.domain.analysis.ErrorAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * Format database results for LLM consumption with two complementary views:
     * - RESULTS_TABLE: human-readable, ranked or tabular depending on presence of content
     * - RESULTS_JSON: machine-readable summary including numeric_totals for aggregates
     */
    public static String formatResultsForLLM(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "No results found.";
        }

        boolean hasContent = results.stream().anyMatch(r -> r.containsKey("content"));

        StringBuilder table = new StringBuilder();
        StringBuilder json = new StringBuilder();

        if (hasContent) {
            // Apply intelligent ranking and limiting for verbose log content
            List<Map<String, Object>> rankedResults = rankResultsByRelevance(results);
            table.append(String.format("Total results: %d, showing top %d most relevant:\n\n",
                    results.size(), rankedResults.size()));

            int maxTotalLength = 50000; // Conservative limit for this stage
            int currentLength = table.length();

            for (int i = 0; i < rankedResults.size(); i++) {
                Map<String, Object> row = rankedResults.get(i);

                StringBuilder rowSection = new StringBuilder();

                String stepName = getFieldValue(row, "step_name");
                String caseNumber = getFieldValue(row, "case_number");
                String date = getFieldValue(row, "report_date");
                String hostname = getFieldValue(row, "datacenter");

                rowSection.append(String.format("## %s | Case %s | %s | %s\n",
                        stepName, caseNumber, date, hostname));

                Object content = row.get("content");
                if (content != null) {
                    String contentStr = content.toString();
                    String keyErrors = ErrorAnalyzer.extractKeyErrors(contentStr);

                    if (!keyErrors.isEmpty()) {
                        rowSection.append("**Key Errors:**\n").append(keyErrors);
                    } else {
                        int maxContentLength = 300;
                        if (contentStr.length() > maxContentLength) {
                            contentStr = contentStr.substring(0, maxContentLength) + "...";
                        }
                        rowSection.append("Content: ").append(contentStr);
                    }
                }

                rowSection.append("\n");

                if (currentLength + rowSection.length() > maxTotalLength) {
                    table.append(String.format("... %d additional results omitted due to token limits\n",
                            rankedResults.size() - i));
                    break;
                }

                table.append(rowSection);
                currentLength += rowSection.length();
            }
        } else {
            // Aggregated/tabular data (no content). Show compact table-like listing
            List<String> columns = collectColumns(results);
            table.append(String.format("Total rows: %d\n\n", results.size()));
            table.append(String.join(" | ", columns)).append("\n");
            table.append(columns.stream().map(c -> "---").collect(Collectors.joining(" | "))).append("\n");
            int shown = 0;
            for (Map<String, Object> row : results) {
                if (shown >= 100) { // cap rows in the table view
                    table.append(String.format("... %d additional rows omitted\n", results.size() - shown));
                    break;
                }
                List<String> values = new ArrayList<>();
                for (String col : columns) {
                    Object v = row.get(col);
                    values.add(v == null ? "" : v.toString());
                }
                table.append(String.join(" | ", values)).append("\n");
                shown++;
            }
            table.append("\n");
        }

        // Build machine-readable JSON summary, excluding heavy content fields
        json.append("{\n");
        List<String> columns = collectColumns(results);
        json.append("  \"columns\": [")
            .append(columns.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")))
            .append("],\n");
        json.append("  \"row_count\": ").append(results.size()).append(",\n");
        // rows (trimmed to 200)
        json.append("  \"rows\": [\n");
        int rowLimit = Math.min(results.size(), 200);
        for (int i = 0; i < rowLimit; i++) {
            Map<String, Object> row = results.get(i);
            json.append("    {");
            boolean first = true;
            for (String col : columns) {
                if ("content".equals(col)) continue; // exclude heavy content
                if (!first) json.append(", ");
                json.append("\"").append(col).append("\": ");
                Object val = row.get(col);
                if (val == null) {
                    json.append("null");
                } else if (val instanceof Number || isBooleanString(val)) {
                    json.append(val.toString());
                } else {
                    json.append("\"").append(escapeJson(val.toString())).append("\"");
                }
                first = false;
            }
            json.append("}");
            if (i < rowLimit - 1) json.append(",");
            json.append("\n");
        }
        if (results.size() > rowLimit) {
            json.append("    {\"note\": \"rows truncated\"}\n");
        }
        json.append("  ],\n");

        Map<String, Number> totals = computeNumericTotals(results);
        json.append("  \"numeric_totals\": {");
        int idx = 0;
        for (Map.Entry<String, Number> e : totals.entrySet()) {
            if (idx++ > 0) json.append(", ");
            json.append("\"").append(e.getKey()).append("\": ").append(e.getValue().toString());
        }
        json.append("}\n");
        json.append("}\n");

        StringBuilder out = new StringBuilder();
        out.append(table);
        out.append("\n");
        out.append("```").append("json").append("\n");
        out.append(json);
        out.append("```");
        return out.toString();
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

    private static List<String> collectColumns(List<Map<String, Object>> results) {
        Set<String> cols = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : results) {
            cols.addAll(row.keySet());
        }
        // Keep a stable, meaningful order: common fields first
        List<String> preferred = List.of("step_name", "report_date", "case_number", "datacenter", "record_id", "work_id", "attachment_id");
        List<String> ordered = new ArrayList<>();
        for (String p : preferred) if (cols.contains(p)) ordered.add(p);
        for (String c : cols) if (!ordered.contains(c)) ordered.add(c);
        return ordered;
    }

    private static Map<String, Number> computeNumericTotals(List<Map<String, Object>> results) {
        Map<String, Number> totals = new java.util.LinkedHashMap<>();
        if (results.isEmpty()) return totals;
        // Identify numeric columns by scanning first 50 rows
        Set<String> numericCols = new java.util.LinkedHashSet<>();
        int scan = Math.min(50, results.size());
        for (int i = 0; i < scan; i++) {
            for (Map.Entry<String, Object> e : results.get(i).entrySet()) {
                if (e.getValue() instanceof Number && !"id".equalsIgnoreCase(e.getKey())) {
                    numericCols.add(e.getKey());
                }
            }
        }
        Map<String, Double> sums = new java.util.LinkedHashMap<>();
        for (String col : numericCols) sums.put(col, 0.0);
        for (Map<String, Object> row : results) {
            for (String col : numericCols) {
                Object v = row.get(col);
                if (v instanceof Number) {
                    sums.put(col, sums.get(col) + ((Number) v).doubleValue());
                }
            }
        }
        // Cast back to integer if all values are integers
        for (String col : sums.keySet()) {
            boolean allInts = true;
            for (Map<String, Object> row : results) {
                Object v = row.get(col);
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (d != Math.rint(d)) { allInts = false; break; }
                }
            }
            double total = sums.get(col);
            totals.put(col, allInts ? (long) Math.rint(total) : total);
        }
        return totals;
    }

    private static boolean isBooleanString(Object val) {
        String s = val.toString().toLowerCase();
        return "true".equals(s) || "false".equals(s);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}