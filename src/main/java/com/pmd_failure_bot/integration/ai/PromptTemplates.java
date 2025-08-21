package com.pmd_failure_bot.integration.ai;

import org.springframework.stereotype.Component;

/**
 * Templates for LLM prompts
 */
@Component
public class PromptTemplates {

    /**
     * Creates a prompt for parameter extraction from natural language queries
     */
    public String parameterExtraction(String query, String conversationContext, String currentDate) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("<instructions>\n")
              .append("You extract structured parameters for questions about PMD deployment failures and logs.\n\n")
              .append("Return a single JSON object with these fields (use null when unknown):\n")
              .append("- record_id (string)\n")
              .append("- work_id (string)\n")
              .append("- case_number (integer)\n")
              .append("- step_name (string; e.g., SSH_TO_ALL_HOSTS, GRIDFORCE_APP_LOG_COPY)\n")
              .append("- attachment_id (string)\n")
              .append("- datacenter (string)\n")
              .append("- report_date (YYYY-MM-DD)\n")
              .append("- query (string; the core question with parameters removed)\n")
              .append("- intent (string; one of: import, metrics, analysis)\n")
              .append("- confidence (number 0.0–1.0)\n")
              .append("- is_relevant (boolean; true if about PMD/logs/deployments)\n")
              .append("- irrelevant_reason (string; short reason when is_relevant=false)\n\n")
              .append("Guidelines:\n")
              .append("1) Extract only what is stated or strongly implied.\n")
              .append("2) Resolve relative dates to actual dates (today: ").append(currentDate).append(").\n")
              .append("3) Normalize partial step names to canonical names when clear.\n")
              .append("4) Case numbers are integers; strip non-digits.\n")
              .append("5) For intent: 'import' = fetch logs, 'metrics' = counts/breakdowns/trends, 'analysis' = explain errors/root cause.\n")
              .append("6) If the user asks to 'explain' or 'why', prefer intent=analysis. For 'how many' or 'count', prefer metrics.\n")
              .append("7) Always include intent; set confidence based on clarity.\n")
              .append("8) If not about PMD/logs/deployments, set is_relevant=false and give a brief irrelevant_reason.\n")
              .append("9) Return ONLY JSON, no extra text.\n\n")
              .append("Example query: \"What went wrong with case 123456's SSH deployment yesterday?\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 123456, \"step_name\": \"SSH_TO_ALL_HOSTS\", \"attachment_id\": null, \"datacenter\": null, \"report_date\": \"2024-01-15\", \"query\": \"What went wrong with deployment\", \"intent\": \"analysis\", \"confidence\": 0.9, \"is_relevant\": true, \"irrelevant_reason\": null}\n\n")
              .append("Example import: \"Import logs for case 567890\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 567890, \"step_name\": null, \"attachment_id\": null, \"datacenter\": null, \"report_date\": null, \"query\": \"Import logs\", \"intent\": \"import\", \"confidence\": 0.95, \"is_relevant\": true, \"irrelevant_reason\": null}\n")
              .append("</instructions>\n\n");

        if (conversationContext != null && !conversationContext.trim().isEmpty()) {
            prompt.append("<conversation_context>\n")
                  .append(conversationContext)
                  .append("\n</conversation_context>\n\n");
        }

        prompt.append("<user_query>\n")
              .append(query)
              .append("\n</user_query>\n");

        return prompt.toString();
    }

    /**
     * Metrics summary prompt (counts, breakdowns, trends) for Slack-ready answers
     */
    public String nlSummary(String originalQuery, String sql, String formattedResults, int resultCount) {
        return String.format(
            "<instructions>\n" +
            "You answer metrics questions about PMD failure logs for Slack. Use the data in <context> to answer the <question>.\n\n" +
            "Output (plain text):\n" +
            "- Line 1: Direct numeric total with scope (include step/date/datacenter if present).\n" +
            "- Following lines: Provide a breakdown using the most informative dimensions present in the data and relevant to the question (e.g., step_name, report_date, datacenter, case_number, work_id).\n" +
            "- If there is no matching data, output exactly: No matching data found.\n\n" +
            "Rules:\n" +
            "- Compute totals from the aggregates in the data, not from number of rows.\n" +
            "- Avoid internal details (SQL, column names, JSON keys).\n" +
            "- Do not invent filters or ranges not present in the question or data.\n" +
            "</instructions>\n\n" +
            "<context>\n" +
            "SQL Query: %s\n\n" +
            "Results (%d rows):\n%s\n" +
            "</context>\n\n" +
            "<question>\n" +
            "%s\n" +
            "</question>",
            sql,
            resultCount,
            formattedResults,
            originalQuery
        );
    }

    /**
     * Analysis prompt (explain errors/root cause) for Slack-ready answers
     */
    public String nlErrorSummary(String originalQuery, String sql, String formattedResults) {
        return String.format(
            "<instructions>\n" +
            "You explain PMD failure logs in plain language for Slack. Use the data in <context> to answer the <question>.\n\n" +
            "Output structure (exactly this order):\n" +
            "1) Paragraph 1 — Concise summary (1–3 sentences) of what happened, including step and date if available.\n" +
            "2) Key error lines and patterns: provide a bulleted list of 2–6 of the most relevant error messages/patterns, each with a brief description in plain language and short direct quotes (e.g., • Connection timeout - 'connection timed out after 30 seconds').\n" +
            "3) Paragraph 3 — Diagnosis: likely cause(s), scope, and any notable contributing factors. No action items.\n" +
            "Then, on new lines, end with a list titled 'Work items:' followed by one item per line using this exact link format when record_id and work_id are present: <https://gus.lightning.force.com/lightning/r/ADM_Work__c/{record_id}/view|{work_id}>.\n\n" +
            "Style rules:\n" +
            "- Plain text only (no markdown headings).\n" +
            "- Avoid internal implementation details and SQL/column names.\n" +
            "- If evidence is insufficient, say: 'Insufficient data to determine root cause.'\n" +
            "</instructions>\n\n" +
            "<context>\n" +
            "SQL Query: %s\n\n" +
            "%s\n" +
            "</context>\n\n" +
            "<question>\n" +
            "%s\n" +
            "</question>",
            sql,
            formattedResults,
            originalQuery
        );
    }
}