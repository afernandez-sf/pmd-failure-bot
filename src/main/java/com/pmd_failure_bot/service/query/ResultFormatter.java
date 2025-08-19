package com.pmd_failure_bot.service.query;

import com.pmd_failure_bot.service.analysis.ErrorAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for formatting query results for presentation
 */
public class ResultFormatter {

    private static final int MAX_TOTAL_LENGTH = 50000;
    private static final int MAX_CONTENT_LENGTH = 300;
    private static final int MAX_TABLE_ROWS = 100;
    private static final int MAX_JSON_ROWS = 200;
    private static final int MAX_RANKED_RESULTS = 15;
    private static final int MAX_RESULTS_PER_STEP = 2;
    private static final int NUMERIC_SCAN_LIMIT = 50;
    private static final int DIVERSITY_THRESHOLD = 10;

    private ResultFormatter() {}

    public static String formatResultsForLLM(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "No results found.";
        }

        boolean hasContent = results.stream().anyMatch(r -> r != null && r.containsKey("content"));
        List<String> columns = collectColumns(results); // Cache columns collection

        StringBuilder table = new StringBuilder();
        StringBuilder json = new StringBuilder();

        if (hasContent) {
            List<Map<String, Object>> rankedResults = rankResultsByRelevance(results);
            table.append(String.format("Total results: %d, showing top %d most relevant:\n\n",
                    results.size(), rankedResults.size()));

            int currentLength = table.length();

            for (int i = 0; i < rankedResults.size(); i++) {
                Map<String, Object> row = rankedResults.get(i);

                StringBuilder rowSection = new StringBuilder();

                String stepName = getFieldValue(row, "step_name");
                String caseNumber = getFieldValue(row, "case_number");
                String date = getFieldValue(row, "report_date");
                String datacenter = getFieldValue(row, "datacenter");

                rowSection.append(String.format("## %s | Case %s | %s | %s\n",
                        stepName, caseNumber, date, datacenter));

                Object content = row.get("content");
                if (content != null) {
                    String contentStr = content.toString();
                    String keyErrors = ErrorAnalyzer.extractKeyErrors(contentStr);

                    if (!keyErrors.isEmpty()) {
                        rowSection.append("**Key Errors:**\n").append(keyErrors);
                    } else {
                        if (contentStr.length() > MAX_CONTENT_LENGTH) {
                            contentStr = contentStr.substring(0, MAX_CONTENT_LENGTH) + "...";
                        }
                        rowSection.append("Content: ").append(contentStr);
                    }
                }

                rowSection.append("\n");

                if (currentLength + rowSection.length() > MAX_TOTAL_LENGTH) {
                    table.append(String.format("... %d additional results omitted due to token limits\n",
                            rankedResults.size() - i));
                    break;
                }

                table.append(rowSection);
                currentLength += rowSection.length();
            }
        } else {
            table.append(String.format("Total rows: %d\n\n", results.size()));
            table.append(String.join(" | ", columns)).append("\n");
            table.append(columns.stream().map(c -> "---").collect(Collectors.joining(" | "))).append("\n");
            int shown = 0;
            for (Map<String, Object> row : results) {
                if (shown >= MAX_TABLE_ROWS) {
                    table.append(String.format("... %d additional rows omitted\n", results.size() - shown));
                    break;
                }
                List<String> values = new ArrayList<>();
                for (String col : columns) {
                    Object v = row != null ? row.get(col) : null;
                    values.add(v == null ? "" : v.toString());
                }
                table.append(String.join(" | ", values)).append("\n");
                shown++;
            }
            table.append("\n");
        }

        json.append("{\n");
        json.append("  \"columns\": [")
            .append(columns.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")))
            .append("],\n");
        json.append("  \"row_count\": ").append(results.size()).append(",\n");
        json.append("  \"rows\": [\n");
        int rowLimit = Math.min(results.size(), MAX_JSON_ROWS);
        for (int i = 0; i < rowLimit; i++) {
            Map<String, Object> row = results.get(i);
            if (row == null) continue;
            json.append("    {");
            boolean first = true;
            for (String col : columns) {
                if ("content".equals(col)) continue;
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

    private static List<Map<String, Object>> rankResultsByRelevance(List<Map<String, Object>> results) {
        if (results.size() > DIVERSITY_THRESHOLD) {
            return rankWithStepNameDiversity(results);
        }
        return results.stream()
            .sorted(createResultComparator())
            .limit(MAX_RANKED_RESULTS)
            .collect(Collectors.toList());
    }

    private static List<Map<String, Object>> rankWithStepNameDiversity(List<Map<String, Object>> results) {
        Map<String, List<Map<String, Object>>> byStepName = results.stream()
            .collect(Collectors.groupingBy(result -> {
                Object stepName = result.get("step_name");
                return stepName != null ? stepName.toString() : "UNKNOWN";
            }));

        List<Map<String, Object>> diverseResults = new ArrayList<>();

        byStepName.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
            .forEach(entry -> {
                List<Map<String, Object>> stepResults = entry.getValue().stream()
                    .sorted(createResultComparator())
                    .limit(MAX_RESULTS_PER_STEP)
                    .collect(Collectors.toList());
                diverseResults.addAll(stepResults);
            });

        return diverseResults.stream()
            .sorted(createResultComparator())
            .limit(MAX_RANKED_RESULTS)
            .collect(Collectors.toList());
    }

    private static java.util.Comparator<Map<String, Object>> createResultComparator() {
        return (r1, r2) -> {
            if (r1 == null || r2 == null) {
                return r1 == null ? (r2 == null ? 0 : 1) : -1;
            }
            
            int score1 = getResultErrorScore(r1);
            int score2 = getResultErrorScore(r2);
            int scoreCompare = Integer.compare(score2, score1);
            if (scoreCompare != 0) return scoreCompare;
            
            Object date1 = r1.get("report_date");
            Object date2 = r2.get("report_date");
            if (date1 != null && date2 != null) {
                return date2.toString().compareTo(date1.toString());
            }
            
            Object content1 = r1.get("content");
            Object content2 = r2.get("content");
            int len1 = content1 != null ? content1.toString().length() : 0;
            int len2 = content2 != null ? content2.toString().length() : 0;
            return Integer.compare(len2, len1);
        };
    }

    private static int getResultErrorScore(Map<String, Object> result) {
        Object content = result.get("content");
        if (content == null) return 0;
        String contentStr = content.toString().toLowerCase();
        return ErrorAnalyzer.getErrorRelevanceScore(contentStr);
    }

    private static String getFieldValue(Map<String, Object> row, String fieldName) {
        Object value = row.get(fieldName);
        return value != null ? value.toString() : "N/A";
    }

    private static List<String> collectColumns(List<Map<String, Object>> results) {
        Set<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> row : results) {
            if (row != null) {
                cols.addAll(row.keySet());
            }
        }
        List<String> preferred = List.of("step_name", "report_date", "case_number", "datacenter", "record_id", "work_id", "attachment_id");
        List<String> ordered = new ArrayList<>();
        Set<String> addedCols = new HashSet<>();
        
        // Add preferred columns first
        for (String p : preferred) {
            if (cols.contains(p)) {
                ordered.add(p);
                addedCols.add(p);
            }
        }
        
        // Add remaining columns
        for (String c : cols) {
            if (!addedCols.contains(c)) {
                ordered.add(c);
            }
        }
        return ordered;
    }

    private static Map<String, Number> computeNumericTotals(List<Map<String, Object>> results) {
        Map<String, Number> totals = new LinkedHashMap<>();
        if (results == null || results.isEmpty()) return totals;
        
        Set<String> numericCols = getNumericCols(results);
        if (numericCols.isEmpty()) return totals;
        
        Map<String, Double> sums = new LinkedHashMap<>();
        Map<String, Boolean> allInts = new HashMap<>();
        
        // Initialize tracking
        for (String col : numericCols) {
            sums.put(col, 0.0);
            allInts.put(col, true);
        }
        
        // Single pass to compute sums and check if all integers
        for (Map<String, Object> row : results) {
            if (row != null) {
                for (String col : numericCols) {
                    Object v = row.get(col);
                    if (v instanceof Number) {
                        double d = ((Number) v).doubleValue();
                        sums.put(col, sums.get(col) + d);
                        if (allInts.get(col) && d != Math.rint(d)) {
                            allInts.put(col, false);
                        }
                    }
                }
            }
        }
        
        // Convert to final result
        for (String col : numericCols) {
            double total = sums.get(col);
            totals.put(col, allInts.get(col) ? (long) Math.rint(total) : total);
        }
        return totals;
    }

    @NotNull
    private static Set<String> getNumericCols(List<Map<String, Object>> results) {
        Set<String> numericCols = new LinkedHashSet<>();
        int scan = Math.min(NUMERIC_SCAN_LIMIT, results.size());
        for (int i = 0; i < scan; i++) {
            Map<String, Object> result = results.get(i);
            if (result != null) {
                for (Map.Entry<String, Object> e : result.entrySet()) {
                    if (e.getValue() instanceof Number && !"id".equalsIgnoreCase(e.getKey())) {
                        numericCols.add(e.getKey());
                    }
                }
            }
        }
        return numericCols;
    }

    private static boolean isBooleanString(Object val) {
        if (val == null) return false;
        String s = val.toString().toLowerCase();
        return "true".equals(s) || "false".equals(s);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}