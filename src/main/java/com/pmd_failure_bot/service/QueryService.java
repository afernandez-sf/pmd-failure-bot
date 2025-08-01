package com.pmd_failure_bot.service;

import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.entity.PmdReport;
import com.pmd_failure_bot.repository.PmdReportRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final PmdReportRepository pmdReportRepository;
    private final ChatClient chatClient;

    @Autowired
    public QueryService(PmdReportRepository pmdReportRepository, ChatClient.Builder chatClientBuilder) {
        this.pmdReportRepository = pmdReportRepository;
        this.chatClient = chatClientBuilder.build();
    }

    public QueryResponse processQuery(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("Query is required");
        }
        
        // Ensure at least one filter field is provided to limit the search scope
        if (isAllFiltersEmpty(request)) {
            throw new IllegalArgumentException("At least one filter field must be provided (step_name, work_id, case_number, record_id, attachment_id, hostname, executor_kerberos_id, requesting_kerberos_id, or report_date)");
        }

        List<PmdReport> reports = pmdReportRepository.findByFilters(
                request.getRecordId(),
                request.getWorkId(),
                request.getCaseNumber(),
                request.getStepName(),
                request.getAttachmentId(),
                request.getHostname(),
                request.getExecutorKerberosId(),
                request.getRequestingKerberosId(),
                request.getReportDate()
        );

        String llmContext = prepareContextFromReports(reports);
        
        List<QueryResponse.ReportInfo> reportPaths = reports.stream()
                .map(report -> new QueryResponse.ReportInfo(
                        report.getAttachmentId() != null ? report.getAttachmentId() : "N/A"
                ))
                .collect(Collectors.toList());

        String promptTemplate = """
                You are a <persona>Senior Pod Migration and Decommission Specialist</persona> with deep expertise in analyzing system logs, infrastructure operations, and troubleshooting complex deployment issues.
                
                Your responses should be COMPREHENSIVE, DETAILED, and THOROUGH. Always provide:
                1. **Detailed Analysis**: Break down the information systematically
                2. **Context and Background**: Explain relevant technical context
                3. **Step-by-Step Explanations**: When applicable, provide detailed procedures
                4. **Potential Issues and Solutions**: Identify problems and suggest specific remediation steps
                5. **Best Practices**: Include relevant operational recommendations
                6. **Summary**: Conclude with clear, actionable insights
                
                If you do not know the answer to a question, you truthfully say that you do not know and explain what information would be needed to provide a complete answer.
                
                <documents>
                {context}
                </documents>
                
                Based on the provided PMD (Pod Migration and Decommission) reports above, provide a comprehensive, detailed response that thoroughly addresses the question. Your answer should ONLY be drawn from the provided search results above, never include answers outside of the search results provided.
                
                Ensure your response is:
                - **Comprehensive**: Cover all relevant aspects of the question
                - **Detailed**: Provide specific examples, error messages, timestamps, and technical details from the logs
                - **Well-structured**: Use clear headings, bullet points, and logical organization
                - **Actionable**: Include specific next steps or recommendations where appropriate
                - **Thorough**: Don't provide brief answers - elaborate on findings and their implications
                
                <question>
                {query}
                </question>
                
                Provide your detailed analysis below:
                """;

        PromptTemplate template = new PromptTemplate(promptTemplate);
        Prompt prompt = template.create(Map.of(
                "context", llmContext,
                "query", request.getQuery()
        ));

        String llmResponse = chatClient.prompt(prompt).call().content();
        
        long executionTimeMs = System.currentTimeMillis() - startTime;
        
        return new QueryResponse(llmResponse, reportPaths, null, executionTimeMs);
    }

    private String prepareContextFromReports(List<PmdReport> reports) {
        if (reports.isEmpty()) {
            return "No PMD reports found matching the specified criteria. Please verify the search parameters and try again.";
        }

        StringBuilder context = new StringBuilder();
        context.append(String.format("Found %d PMD report(s) matching the criteria:\n\n", reports.size()));
        
        for (int i = 0; i < reports.size(); i++) {
            PmdReport report = reports.get(i);
            context.append(String.format("=== REPORT %d ===\n", i + 1));
            context.append(String.format("Record ID: %s\n", report.getRecordId() != null ? report.getRecordId() : "N/A"));
            context.append(String.format("Work ID: %s\n", report.getWorkId() != null ? report.getWorkId() : "N/A"));
            context.append(String.format("Case Number: %s\n", report.getCaseNumber() != null ? report.getCaseNumber() : "N/A"));
            context.append(String.format("Step Name: %s\n", report.getStepName()));
            context.append(String.format("Attachment ID: %s\n", report.getAttachmentId() != null ? report.getAttachmentId() : "N/A"));
            context.append(String.format("Report Date: %s\n", report.getReportDate() != null ? report.getReportDate() : "N/A"));
            context.append(String.format("Hostname: %s\n", report.getHostname() != null ? report.getHostname() : "N/A"));
            context.append(String.format("Executor Kerberos ID: %s\n", report.getExecutorKerberosId() != null ? report.getExecutorKerberosId() : "N/A"));
            context.append(String.format("Requesting Kerberos ID: %s\n", report.getRequestingKerberosId() != null ? report.getRequestingKerberosId() : "N/A"));
            context.append("\n--- LOG CONTENT ---\n");
            context.append(report.getContent() != null ? report.getContent() : "No content available");
            context.append("\n\n");
        }
        
        return context.toString();
    }
    
    private boolean isAllFiltersEmpty(QueryRequest request) {
        return (request.getRecordId() == null || request.getRecordId().trim().isEmpty()) &&
               request.getWorkId() == null &&
               request.getCaseNumber() == null &&
               (request.getStepName() == null || request.getStepName().trim().isEmpty()) &&
               (request.getAttachmentId() == null || request.getAttachmentId().trim().isEmpty()) &&
               (request.getHostname() == null || request.getHostname().trim().isEmpty()) &&
               (request.getExecutorKerberosId() == null || request.getExecutorKerberosId().trim().isEmpty()) &&
               (request.getRequestingKerberosId() == null || request.getRequestingKerberosId().trim().isEmpty()) &&
               request.getReportDate() == null;
    }
} 