package com.pmd_failure_bot.service.analysis;

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

    private static final int MAX_ERROR_BLOCKS = 5;
    private static final int CONTEXT_LINES_BEFORE = 3;
    private static final int MAX_CONTINUATION_LINES = 12;
    private static final int TOTAL_CHAR_LIMIT = 8000;

    private ErrorAnalyzer() {}

    public static int getErrorRelevanceScore(String content) {
        if (content == null) return 0;
        String lowerContent = content.toLowerCase();
        int score = 0;
        if (lowerContent.contains("error")) score += 10;
        if (lowerContent.contains("failed")) score += 10;
        if (lowerContent.contains("failure")) score += 10;
        if (lowerContent.contains("exception")) score += 8;
        if (lowerContent.contains("timeout")) score += 6;
        if (lowerContent.contains("denied")) score += 6;
        if (lowerContent.contains("refused")) score += 6;
        if (lowerContent.contains("warning")) score += 3;
        if (lowerContent.contains("retry")) score += 2;
        if (lowerContent.contains("abort")) score += 5;
        return score;
    }

    public static String extractKeyErrors(String content) {
        if (content == null) return "";
        StringBuilder errors = new StringBuilder();
        String[] lines = content.split("\n");
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            if (lowerLine.contains("fatal") ||
                lowerLine.contains("error") ||
                lowerLine.contains("failed") ||
                lowerLine.contains("permission denied") ||
                lowerLine.contains("connection") && lowerLine.contains("error") ||
                lowerLine.contains("exception")) {
                String errorLine = line.length() > 120 ? line.substring(0, 120) + "..." : line;
                errors.append("- ").append(errorLine.trim()).append("\n");
                if (errors.toString().split("\n").length >= 5) break;
            }
        }
        return errors.toString();
    }

    public static String extractErrorContext(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        class Trigger { final Pattern p; final int w; Trigger(String r, int w){ this.p = Pattern.compile(r, Pattern.CASE_INSENSITIVE); this.w = w; } }
        List<Trigger> triggers = List.of(
            new Trigger("\\bFATAL\\b", 5),
            new Trigger("\\[ERROR\\]|\\berror\\b|exception", 4),
            new Trigger("\\bFAILED\\b|Refusing to execute|Oracle not available", 4),
            new Trigger("timeout|denied|refused|connection error|Unable to (get|retrieve|start)", 3)
        );
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
            int start = Math.max(0, i - CONTEXT_LINES_BEFORE);
            int end = Math.min(n, i + 1);
            int extra = 0;
            while (end < n && extra < MAX_CONTINUATION_LINES) {
                String next = lines.get(end);
                if (looksLikeContinuation(next)) { end++; extra++; } else { break; }
            }
            int score = weight * 100000 + i;
            blocks.add(new Block(start, end, score, i));
        }
        if (blocks.isEmpty()) return "";
        blocks.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<Block> merged = new ArrayList<>();
        Block cur = blocks.get(0);
        for (int k = 1; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            if (b.start() <= cur.end() + 1) {
                cur = new Block(cur.start(), Math.max(cur.end(), b.end()), Math.max(cur.score(), b.score()), Math.max(cur.index(), b.index()));
            } else {
                merged.add(cur); cur = b;
            }
        }
        merged.add(cur);
        merged.sort((a, b) -> Integer.compare(b.score(), a.score()));
        int maxBlocks = MAX_ERROR_BLOCKS;
        int totalCharLimit = TOTAL_CHAR_LIMIT;
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seenSnippets = new java.util.HashSet<>();
        int used = 0; int added = 0;
        for (Block b : merged) {
            if (added >= maxBlocks) break;
            String snippet = slice(lines, b.start(), b.end());
            String normalized = normalizeSnippet(snippet);
            if (normalized.isBlank() || seenSnippets.contains(normalized)) continue;
            if (used + snippet.length() > totalCharLimit) break;
            if (out.length() > 0) out.append("\n--- ERROR CONTEXT SEPARATOR ---\n");
            out.append(snippet);
            used += snippet.length(); added++; seenSnippets.add(normalized);
        }
        return out.toString();
    }

    private static boolean looksLikeContinuation(String line) {
        if (line == null) return false;
        String s = line;
        if (s.startsWith("\t") || s.startsWith("    ") || s.startsWith("  ")) return true;
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*") || s.matches("^\\d{2}:\\d{2}:\\d{2}.*")) return false;
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
            if (t.toLowerCase().contains("not registered with synner")) t = "Not registered with Synner";
            if (t.toLowerCase().contains("2fa is required") && t.toLowerCase().contains("prompt_user")) t = "2FA is required and Synner code not available";
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }
}


