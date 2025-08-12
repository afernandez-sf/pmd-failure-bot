package com.pmd_failure_bot.service;

import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.entity.PmdReport;
import com.pmd_failure_bot.repository.PmdReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final PmdReportRepository pmdReportRepository;
    private final SalesforceLlmGatewayService llmGatewayService;
    private final DatabaseQueryService databaseQueryService;

    @Autowired
    public QueryService(PmdReportRepository pmdReportRepository, SalesforceLlmGatewayService llmGatewayService, 
                       DatabaseQueryService databaseQueryService) {
        this.pmdReportRepository = pmdReportRepository;
        this.llmGatewayService = llmGatewayService;
        this.databaseQueryService = databaseQueryService;
    }

    public QueryResponse processQuery(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("Query is required");
        }
        
        // If this is a natural language query (no structured filters), use DatabaseQueryService
        if (isAllFiltersEmpty(request)) {
            return processNaturalLanguageQuery(request, startTime);
        }
        
        // Otherwise use the existing structured query logic
        return processStructuredQuery(request, startTime);
    }
    
    /**
     * Process natural language queries using function calling and dynamic SQL generation
     */
    private QueryResponse processNaturalLanguageQuery(QueryRequest request, long startTime) {
        try {
            DatabaseQueryService.DatabaseQueryResult result = 
                databaseQueryService.processNaturalLanguageQuery(request.getQuery());
            
            if (result.isSuccessful()) {
                // Convert results to work item links (extract record_id and work_id from results)
                List<QueryResponse.ReportInfo> reportPaths = result.getResults().stream()
                    .map(row -> {
                        String recordId = row.get("record_id") != null ? row.get("record_id").toString() : "N/A";
                        String workId = row.get("work_id") != null ? row.get("work_id").toString() : "N/A";
                        return new QueryResponse.ReportInfo(recordId, workId);
                    })
                    .distinct() // Remove duplicates
                    .collect(Collectors.toList());
                
                long executionTimeMs = System.currentTimeMillis() - startTime;
                
                return new QueryResponse(
                    result.getNaturalLanguageResponse(),
                    reportPaths,
                    LocalDateTime.now(),
                    executionTimeMs
                );
            } else {
                throw new RuntimeException(result.getErrorMessage());
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to process natural language query: " + e.getMessage(), e);
        }
    }
    
    /**
     * Process structured queries using the existing repository methods
     */
    private QueryResponse processStructuredQuery(QueryRequest request, long startTime) {
        // Prepare step name for "starts with" matching by adding wildcard
        String stepNamePattern = request.getStepName();
        if (stepNamePattern != null) {
            stepNamePattern = stepNamePattern + "%";
        }
        
        List<PmdReport> reports;
        if (request.getReportDate() != null) {
            reports = pmdReportRepository.findByFiltersWithDate(
                    request.getRecordId(),
                    request.getWorkId(),
                    request.getCaseNumber(),
                    stepNamePattern,
                    request.getAttachmentId(),
                    request.getHostname(),
                    request.getReportDate()
            );
        } else {
            reports = pmdReportRepository.findByFiltersWithoutDate(
                    request.getRecordId(),
                    request.getWorkId(),
                    request.getCaseNumber(),
                    stepNamePattern,
                    request.getAttachmentId(),
                    request.getHostname()
            );
        }

        String llmContext = prepareContextFromReports(reports);
        
        List<QueryResponse.ReportInfo> reportPaths = reports.stream()
                .map(report -> new QueryResponse.ReportInfo(
                        report.getRecordId() != null ? report.getRecordId() : "N/A",
                        report.getWorkId() != null ? report.getWorkId() : "N/A"
                ))
                .collect(Collectors.toList());

        String promptTemplate = """
                <instructions>
                Read the following log content inside <context></context> XML tags, and then answer the question inside <question></question> XML tags based on the context. Be specific and provide details. Provide your answer in plain text, without any formatting. Respond "Unsure about answer." if not sure about the answer.
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
        
        return new QueryResponse(llmResponse, reportPaths, LocalDateTime.now(), executionTimeMs);
    }

    private String prepareContextFromReports(List<PmdReport> reports) {
        if (reports.isEmpty()) {
            return "No PMD reports found matching the specified criteria. Please verify the search parameters and try again.";
        }

        // Apply intelligent limiting and ranking
        List<PmdReport> limitedReports = limitAndRankReports(reports);
        
        StringBuilder context = new StringBuilder();
        context.append(String.format("Found %d PMD report(s) total, showing top %d most relevant:\n\n", 
                                    reports.size(), limitedReports.size()));
        
        int maxContextLength = 200000; // Conservative limit to stay well under 272k tokens
        int currentLength = context.length();
        
        for (int i = 0; i < limitedReports.size(); i++) {
            PmdReport report = limitedReports.get(i);
            
            // Build report section
            StringBuilder reportSection = new StringBuilder();
            reportSection.append(String.format("=== REPORT %d ===\n", i + 1));
            reportSection.append(String.format("Record ID: %s\n", report.getRecordId() != null ? report.getRecordId() : "N/A"));
            reportSection.append(String.format("Work ID: %s\n", report.getWorkId() != null ? report.getWorkId() : "N/A"));
            reportSection.append(String.format("Case Number: %s\n", report.getCaseNumber() != null ? report.getCaseNumber() : "N/A"));
            reportSection.append(String.format("Step Name: %s\n", report.getStepName()));
            reportSection.append(String.format("Attachment ID: %s\n", report.getAttachmentId() != null ? report.getAttachmentId() : "N/A"));
            reportSection.append(String.format("Report Date: %s\n", report.getReportDate() != null ? report.getReportDate() : "N/A"));
            reportSection.append(String.format("Hostname: %s\n", report.getHostname() != null ? report.getHostname() : "N/A"));
            reportSection.append("\n--- LOG CONTENT ---\n");
            
            // Truncate content if needed
            String content = report.getContent() != null ? report.getContent() : "No content available";
            if (content.length() > 10000) {
                content = content.substring(0, 10000) + "\n[... TRUNCATED DUE TO LENGTH ...]";
            }
            reportSection.append(content);
            reportSection.append("\n\n");
            
            // Check if adding this report would exceed the limit
            if (currentLength + reportSection.length() > maxContextLength) {
                context.append(String.format("\n[... TRUNCATED: %d additional reports omitted due to token limits ...]\n", 
                                            limitedReports.size() - i));
                break;
            }
            
            context.append(reportSection);
            currentLength += reportSection.length();
        }
        
        return context.toString();
    }
    
    /**
     * Limit and rank reports by relevance with step name diversity prioritization
     */
    private List<PmdReport> limitAndRankReports(List<PmdReport> reports) {
        // For broad queries, prioritize step name diversity to show different types of failures
        if (reports.size() > 10) {
            return rankReportsWithStepNameDiversity(reports);
        }
        
        // For smaller result sets, use standard relevance ranking
        return reports.stream()
            .sorted((r1, r2) -> {
                // Primary: Most recent first
                int dateCompare = 0;
                if (r1.getReportDate() != null && r2.getReportDate() != null) {
                    dateCompare = r2.getReportDate().compareTo(r1.getReportDate());
                }
                if (dateCompare != 0) return dateCompare;
                
                // Secondary: Reports with error/failure keywords first
                int errorScore1 = getErrorRelevanceScore(r1);
                int errorScore2 = getErrorRelevanceScore(r2);
                int errorCompare = Integer.compare(errorScore2, errorScore1);
                if (errorCompare != 0) return errorCompare;
                
                // Tertiary: Longer content first (more detailed)
                String content1 = r1.getContent() != null ? r1.getContent() : "";
                String content2 = r2.getContent() != null ? r2.getContent() : "";
                return Integer.compare(content2.length(), content1.length());
            })
            .limit(20)
            .collect(Collectors.toList());
    }
    
    /**
     * Rank reports prioritizing step name diversity for broad queries
     */
    private List<PmdReport> rankReportsWithStepNameDiversity(List<PmdReport> reports) {
        // Group by step name and select the best representative from each step
        Map<String, List<PmdReport>> byStepName = reports.stream()
            .collect(Collectors.groupingBy(report -> 
                report.getStepName() != null ? report.getStepName() : "UNKNOWN"));
        
        List<PmdReport> diverseReports = new ArrayList<>();
        
        // Get the best representative from each step name (up to 2 per step for variety)
        byStepName.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size())) // Steps with more failures first
            .forEach(entry -> {
                List<PmdReport> stepReports = entry.getValue().stream()
                    .sorted((r1, r2) -> {
                        // Within each step, rank by error score and recency
                        int errorScore1 = getErrorRelevanceScore(r1);
                        int errorScore2 = getErrorRelevanceScore(r2);
                        int scoreCompare = Integer.compare(errorScore2, errorScore1);
                        if (scoreCompare != 0) return scoreCompare;
                        
                        if (r1.getReportDate() != null && r2.getReportDate() != null) {
                            return r2.getReportDate().compareTo(r1.getReportDate());
                        }
                        return 0;
                    })
                    .limit(2) // Take top 2 from each step for variety
                    .collect(Collectors.toList());
                
                diverseReports.addAll(stepReports);
            });
        
        // Final sort by overall relevance and limit
        return diverseReports.stream()
            .sorted((r1, r2) -> {
                int errorScore1 = getErrorRelevanceScore(r1);
                int errorScore2 = getErrorRelevanceScore(r2);
                return Integer.compare(errorScore2, errorScore1);
            })
            .limit(20)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate error relevance score based on content keywords
     */
    private int getErrorRelevanceScore(PmdReport report) {
        if (report.getContent() == null) return 0;
        
        String content = report.getContent().toLowerCase();
        int score = 0;
        
        // High priority error indicators
        if (content.contains("error")) score += 10;
        if (content.contains("failed")) score += 10;
        if (content.contains("failure")) score += 10;
        if (content.contains("exception")) score += 8;
        if (content.contains("timeout")) score += 6;
        if (content.contains("denied")) score += 6;
        if (content.contains("refused")) score += 6;
        
        // Medium priority indicators
        if (content.contains("warning")) score += 3;
        if (content.contains("retry")) score += 2;
        if (content.contains("abort")) score += 5;
        
        return score;
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