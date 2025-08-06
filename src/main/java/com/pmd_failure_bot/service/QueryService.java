package com.pmd_failure_bot.service;

import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.entity.PmdReport;
import com.pmd_failure_bot.repository.PmdReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final PmdReportRepository pmdReportRepository;
    private final SalesforceLlmGatewayService llmGatewayService;

    @Autowired
    public QueryService(PmdReportRepository pmdReportRepository, SalesforceLlmGatewayService llmGatewayService) {
        this.pmdReportRepository = pmdReportRepository;
        this.llmGatewayService = llmGatewayService;
    }

    public QueryResponse processQuery(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("Query is required");
        }
        
        // Ensure at least one filter field is provided to limit the search scope
        if (isAllFiltersEmpty(request)) {
            throw new IllegalArgumentException("At least one filter field must be provided (step_name, work_id, case_number, record_id, attachment_id, hostname, or report_date)");
        }

        List<PmdReport> reports = pmdReportRepository.findByFilters(
                request.getRecordId(),
                request.getWorkId(),
                request.getCaseNumber(),
                request.getStepName(),
                request.getAttachmentId(),
                request.getHostname(),
                request.getReportDate()
        );

        String llmContext = prepareContextFromReports(reports);
        
        List<QueryResponse.ReportInfo> reportPaths = reports.stream()
                .map(report -> new QueryResponse.ReportInfo(
                        report.getAttachmentId() != null ? report.getAttachmentId() : "N/A"
                ))
                .collect(Collectors.toList());

        String promptTemplate = """
                <instructions>
                Read the following log content inside <context></context> XML tags, and then answer the question inside <question></question> XML tags based on the context. Respond "Unsure about answer" if not sure about the answer.
                </instructions>
                
                <context>
                {context}
                </context>

                <question>
                {query}
                </question>
                """;

        // Replace placeholders in the prompt template
        String finalPrompt = promptTemplate
                .replace("{context}", llmContext)
                .replace("{query}", request.getQuery());

        String llmResponse;
        try {
            llmResponse = llmGatewayService.generateResponse(finalPrompt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate LLM response: " + e.getMessage(), e);
        }
        
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
            context.append("\n--- LOG CONTENT ---\n");
            context.append(report.getContent() != null ? report.getContent() : "No content available");
            context.append("\n\n");
        }
        
        return context.toString();
    }
    
    private boolean isAllFiltersEmpty(QueryRequest request) {
        return (request.getRecordId() == null || request.getRecordId().trim().isEmpty()) &&
               (request.getWorkId() == null || request.getWorkId().trim().isEmpty()) &&
               request.getCaseNumber() == null &&
               (request.getStepName() == null || request.getStepName().trim().isEmpty()) &&
               (request.getAttachmentId() == null || request.getAttachmentId().trim().isEmpty()) &&
               (request.getHostname() == null || request.getHostname().trim().isEmpty()) &&
               request.getReportDate() == null;
    }
} 