package com.pmd_failure_bot.infrastructure.ai;

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
              .append("You are an expert at extracting structured parameters from natural language queries about deployment failures and PMD logs.\n\n")
              .append("Extract the following parameters from the user's query and return them as a JSON object:\n")
              .append("- record_id: Salesforce record identifier (if mentioned)\n")
              .append("- work_id: GUS work item identifier (if mentioned)\n")
              .append("- case_number: Support case number (integers only, if mentioned)\n")
              .append("- step_name: Deployment step name (if mentioned - examples: SSH_TO_ALL_HOSTS, GRIDFORCE_APP_LOG_COPY, KM_VALIDATION_RELENG, CREATE_IR_ORGS_TABLE_PRESTO_TGT)\n")
              .append("- attachment_id: Salesforce attachment ID (if mentioned)\n")
              .append("- datacenter: Target datacenter or host suffix (if mentioned)\n")
              .append("- report_date: Date in YYYY-MM-DD format (if mentioned, including relative dates like \"yesterday\", \"last week\")\n")
              .append("- query: The refined natural language question to ask about the logs\n")
              .append("- intent: One of 'import', 'metrics', or 'analysis'\n")
              .append("- confidence: Your confidence level (0.0 to 1.0) in the parameter extraction\n\n")
              .append("Guidelines:\n")
              .append("1. Only extract parameters that are explicitly mentioned or strongly implied\n")
              .append("2. For dates, convert relative terms to actual dates (today is ").append(currentDate).append(")\n")
              .append("3. For step names, match partial names to known patterns (e.g., \"SSH\" -> \"SSH_TO_ALL_HOSTS\")\n")
              .append("4. For case numbers, extract only numeric values\n")
              .append("5. The query field should be the main question being asked, cleaned of parameter information\n")
              .append("6. For intent: \n")
              .append("   - 'import' for requests to fetch/import/pull/download logs\n")
              .append("   - 'metrics' for counts, breakdowns, or trends over time\n")
              .append("   - 'analysis' to explain errors, root cause, or analyze logs\n")
              .append("7. Set confidence based on how clearly the parameters were stated\n")
              .append("8. If no parameters are found, return null for those fields\n")
              .append("9. Return ONLY valid JSON, no other text\n\n")
              .append("Example query: \"What went wrong with case 123456's SSH deployment yesterday?\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 123456, \"step_name\": \"SSH_TO_ALL_HOSTS\", \"attachment_id\": null, \"datacenter\": null, \"report_date\": \"2024-01-15\", \"query\": \"What went wrong with deployment\", \"intent\": \"analysis\", \"confidence\": 0.9}\n\n")
              .append("Example import: \"Import logs for case 567890\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 567890, \"step_name\": null, \"attachment_id\": null, \"datacenter\": null, \"report_date\": null, \"query\": \"Import logs\", \"intent\": \"import\", \"confidence\": 0.95}\n")
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
     * Creates a prompt for generating natural language summaries of query results
     */
    public String nlSummary(String originalQuery, String sql, String formattedResults, int resultCount) {
        return String.format(
            "<instructions>\n" +
            "You are an expert database analyst specialized in analyzing PMD deployment failure logs. Review the database results inside <context></context> XML tags, and answer the question inside <question></question> XML tags.\n\n" +
            "Context structure: You are given a compact data summary and a small JSON blob. Use them to compute numbers, but DO NOT mention internal data structure names (JSON, tables, columns, field names, or SQL) in your answer. Keep answers short for Slack.\n\n" +
            "Rules for answering:\n" +
            "- If the user asks for counts (e.g., 'how many', 'number of', 'count'), compute totals from the machine-readable data, not from the number of rows shown.\n" +
            "  * If there are per-group counts (e.g., counts by day or by step), sum those values to produce totals.\n" +
            "  * Provide a concise breakdown (e.g., by step) when the user asks for \"different failures\". Keep answers short and conversational for Slack.\n" +
            "- Provide concise answers; for pure counts, respond with the exact number and a short clarification (e.g., '11 GRIDFORCE_APP_LOG_COPY failures in May 2025').\n" +
            "- For analysis questions, summarize patterns in plain language and avoid referencing internal data structure names.\n" +
            "- Do NOT infer counts from the number of rows; a single row may represent an aggregate across many items.\n" +
            "- Do NOT include next steps, follow-ups, suggestions, or requests for more data. Only answer the question asked.\n" +
            "- Provide your answer in plain text, without any formatting.\n" +
            "- Respond 'Insufficient data to determine root cause.' if the logs don't contain enough information.\n" +
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
     * Creates a prompt for explaining errors from log content (analysis mode)
     */
    public String nlErrorSummary(String originalQuery, String sql, String formattedResults, int resultCount) {
        return String.format(
            "<instructions>\n" +
            "You are an expert in diagnosing deployment failures. Read the provided context and explain the likely causes and key error messages in plain language.\n\n" +
            "Guidelines:\n" +
            "- Focus on the most informative error lines and patterns (fatal, exception, timeout, denied/refused, connection errors).\n" +
            "- Group similar issues together; avoid listing many near-duplicates.\n" +
            "- Reference the step and date when helpful, but do not mention internal implementation details.\n" +
            "- Keep the answer short and actionable for Slack.\n" +
            "- Include a short list of links to the work items used in your analysis when IDs are present. Use the work ID as the link text, and the URL as https://gus.lightning.force.com/lightning/r/ADM_Work__c/{record_id}/view (format: <https://gus.lightning.force.com/lightning/r/ADM_Work__c/{record_id}/view|{work_id}>).\n" +
            "- If the data is insufficient, say so briefly.\n" +
            "- Do NOT include next steps, follow-ups, suggestions, troubleshooting checklists, or requests for additional information. Only explain what happened and the likely causes.\n" +
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