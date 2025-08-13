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
              .append("- hostname: Target hostname or server name (if mentioned)\n")
              .append("- report_date: Date in YYYY-MM-DD format (if mentioned, including relative dates like \"yesterday\", \"last week\")\n")
              .append("- query: The refined natural language question to ask about the logs\n")
              .append("- intent: Either 'query' (asking questions about logs) or 'import' (requesting to import/fetch/pull logs)\n")
              .append("- confidence: Your confidence level (0.0 to 1.0) in the parameter extraction\n\n")
              .append("Guidelines:\n")
              .append("1. Only extract parameters that are explicitly mentioned or strongly implied\n")
              .append("2. For dates, convert relative terms to actual dates (today is ").append(currentDate).append(")\n")
              .append("3. For step names, match partial names to known patterns (e.g., \"SSH\" -> \"SSH_TO_ALL_HOSTS\")\n")
              .append("4. For case numbers, extract only numeric values\n")
              .append("5. The query field should be the main question being asked, cleaned of parameter information\n")
              .append("6. For intent: use 'import' for requests to fetch/import/pull/download logs, 'query' for asking questions about existing logs\n")
              .append("7. Set confidence based on how clearly the parameters were stated\n")
              .append("8. If no parameters are found, return null for those fields\n")
              .append("9. Return ONLY valid JSON, no other text\n\n")
              .append("Example query: \"What went wrong with case 123456's SSH deployment yesterday?\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 123456, \"step_name\": \"SSH_TO_ALL_HOSTS\", \"attachment_id\": null, \"hostname\": null, \"report_date\": \"2024-01-15\", \"query\": \"What went wrong with deployment\", \"intent\": \"query\", \"confidence\": 0.9}\n\n")
              .append("Example import: \"Import logs for case 567890\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 567890, \"step_name\": null, \"attachment_id\": null, \"hostname\": null, \"report_date\": null, \"query\": \"Import logs\", \"intent\": \"import\", \"confidence\": 0.95}\n")
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
            "Guidelines:\n" +
            "- Focus on identifying specific error patterns, root causes, and failure points\n" +
            "- Highlight commonalities across multiple failures if present\n" +
            "- Reference specific case numbers, hostnames, or step names from the data when relevant\n" +
            "- Prioritize explaining WHAT went wrong and WHY it failed\n" +
            "- When analyzing steps, note if certain step types fail more frequently\n" +
            "- Be technical and precise with error details that would help engineers troubleshoot\n" +
            "- Provide your answer in plain text, without any formatting\n" +
            "- Respond \"Insufficient data to determine root cause.\" if the logs don't contain enough information\n" +
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
}