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
     * Common error patterns to look for in log content. Note: we exclude low-signal
     * patterns like 'warning' and 'retry' from triggers to avoid noisy contexts.
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
        "abort"
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
     * Extract error context from log content with block selection and de-duplication.
     * Picks up to 5 highest-signal error blocks with surrounding context and limits total size.
     */
    public static String extractErrorContext(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";

        // Build trigger patterns with simple severity weights
        class Trigger { final Pattern p; final int w; Trigger(String r, int w){ this.p = Pattern.compile(r, Pattern.CASE_INSENSITIVE); this.w = w; } }
        List<Trigger> triggers = List.of(
            new Trigger("\\bFATAL\\b", 5),
            new Trigger("\\[ERROR\\]|\\berror\\b|exception", 4),
            new Trigger("\\bFAILED\\b|Refusing to execute|Oracle not available", 4),
            new Trigger("timeout|denied|refused|connection error|Unable to (get|retrieve|start)", 3)
        );

        // Identify candidate error blocks
        record Block(int start, int end, int score, int index) {}
        List<Block> blocks = new ArrayList<>();
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            int weight = 0;
            for (Trigger t : triggers) {
                if (t.p.matcher(line).find()) {
                    weight = Math.max(weight, t.w);
                }
            }
            if (weight == 0) continue;

            int start = Math.max(0, i - 3);
            int end = Math.min(n, i + 1);

            // Extend forward to include continuation/stack lines up to a cap
            int extra = 0;
            while (end < n && extra < 12) {
                String next = lines.get(end);
                if (looksLikeContinuation(next)) {
                    end++;
                    extra++;
                } else {
                    break;
                }
            }

            // Score favors higher severity and recency
            int score = weight * 100000 + i; // later lines (higher i) slightly preferred
            blocks.add(new Block(start, end, score, i));
        }

        if (blocks.isEmpty()) return "";

        // Merge overlapping/adjacent blocks
        blocks.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<Block> merged = new ArrayList<>();
        Block cur = blocks.get(0);
        for (int k = 1; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            if (b.start() <= cur.end() + 1) {
                // merge: take max end and max score/index window
                cur = new Block(cur.start(), Math.max(cur.end(), b.end()), Math.max(cur.score(), b.score()), Math.max(cur.index(), b.index()));
            } else {
                merged.add(cur);
                cur = b;
            }
        }
        merged.add(cur);

        // Sort by score desc, pick top K
        merged.sort((a, b) -> Integer.compare(b.score(), a.score()));
        int maxBlocks = 5;
        int totalCharLimit = 8000;
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seenSnippets = new java.util.HashSet<>();
        int used = 0;
        int added = 0;
        for (Block b : merged) {
            if (added >= maxBlocks) break;
            String snippet = slice(lines, b.start(), b.end());
            String normalized = normalizeSnippet(snippet);
            if (normalized.isBlank() || seenSnippets.contains(normalized)) continue;
            if (used + snippet.length() > totalCharLimit) break;
            if (out.length() > 0) out.append("\n--- ERROR CONTEXT SEPARATOR ---\n");
            out.append(snippet);
            used += snippet.length();
            added++;
            seenSnippets.add(normalized);
        }

        return out.toString();
    }

    private static boolean looksLikeContinuation(String line) {
        if (line == null) return false;
        String s = line;
        // Continuation if indented, or lacks obvious timestamp prefix, or is a stack/trace/detail line
        if (s.startsWith("\t") || s.startsWith("    ") || s.startsWith("  ")) return true;
        // Common timestamp formats (date or time). If it starts like a new timestamp, treat as new block
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*") || s.matches("^\\d{2}:\\d{2}:\\d{2}.*")) return false;
        // Otherwise, allow 1-2 lines that are not timestamps as continuation
        return true;
    }

    private static String slice(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < Math.min(end, lines.size()); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String normalizeSnippet(String s) {
        String[] arr = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : arr) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // Collapse repeating non-informative warnings
            if (t.toLowerCase().contains("not registered with synner")) t = "Not registered with Synner";
            if (t.toLowerCase().contains("2fa is required") && t.toLowerCase().contains("prompt_user")) t = "2FA is required and Synner code not available";
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }
}