package com.pmd_failure_bot.service.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for analyzing errors in log content
 */
public class ErrorAnalyzer {

    private static final int CONTEXT_LINES_BEFORE = 3;
    private static final int TOTAL_CHAR_LIMIT = 8000;
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}.*");

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
        int errorCount = 0;
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            if (lowerLine.contains("fatal") ||
                lowerLine.contains("error") ||
                lowerLine.contains("failed") ||
                lowerLine.contains("permission denied") ||
                lowerLine.contains("exception")) {
                String errorLine = line.length() > 120 ? line.substring(0, 120) + "..." : line;
                errors.append("- ").append(errorLine.trim()).append("\n");
                errorCount++;
                if (errorCount >= 5) break;
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
            int maxLines = Math.min(n, i + 50); // Safeguard against infinite loops
            while (end < maxLines) {
                String next = lines.get(end);
                if (looksLikeContinuation(next)) { end++; } else { break; }
            }
            int score = weight * 100000 + i;
            blocks.add(new Block(start, end, score, i));
        }
        if (blocks.isEmpty()) return "";
        blocks.sort(Comparator.comparingInt(Block::start));
        List<Block> merged = new ArrayList<>();
        Block cur = blocks.get(0);
        for (int k = 1; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            if (b.start() <= cur.end() + 1) {
                // When merging, prefer the higher weight trigger's score and index
                int newScore = Math.max(b.score(), cur.score());
                int newIndex = b.score() > cur.score() ? b.index() : cur.index();
                cur = new Block(cur.start(), Math.max(cur.end(), b.end()), newScore, newIndex);
            } else {
                merged.add(cur); cur = b;
            }
        }
        merged.add(cur);
        merged.sort(Comparator.comparingInt(Block::score).reversed());
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seenSnippets = new java.util.HashSet<>();
        int used = 0;
        for (Block b : merged) {
            String snippet = slice(lines, b.start(), b.end());
            String normalized = normalizeSnippet(snippet);
            if (normalized.isBlank() || seenSnippets.contains(normalized)) continue;
            if (used + snippet.length() > TOTAL_CHAR_LIMIT) break;
            if (!out.isEmpty()) out.append("\n--- ERROR CONTEXT SEPARATOR ---\n");
            out.append(snippet);
            used += snippet.length(); seenSnippets.add(normalized);
        }
        return out.toString();
    }

    private static boolean looksLikeContinuation(String line) {
        if (line == null) return false;
        if (line.startsWith("\t") || line.startsWith("    ") || line.startsWith("  ")) return true;
        return !DATE_PATTERN.matcher(line).matches() && !TIME_PATTERN.matcher(line).matches();
    }

    private static String slice(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < Math.min(end, lines.size()); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String normalizeSnippet(String s) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // Normalize common error patterns for better deduplication
            String lower = t.toLowerCase();
            if (lower.contains("not registered with synner")) {
                t = "Not registered with Synner";
            } else if (lower.contains("2fa is required") && lower.contains("prompt_user")) {
                t = "2FA is required and Synner code not available";
            } else {
                // Remove timestamps and dynamic identifiers for better duplicate detection
                t = t.replaceAll("\\d{4}-\\d{2}-\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}[\\d.]*Z?", "[TIMESTAMP]")
                     .replaceAll("\\b\\d{10,}\\b", "[ID]")
                     .replaceAll("\\bpid\\s*[=:]?\\s*\\d+", "pid=[PID]");
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }
}


