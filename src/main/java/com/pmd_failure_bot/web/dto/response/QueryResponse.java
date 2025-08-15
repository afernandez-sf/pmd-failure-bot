package com.pmd_failure_bot.web.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public class QueryResponse {
    private String llmResponse;
    private List<ReportInfo> reports;
    private LocalDateTime executedAt;
    private Long executionTimeMs;

    public static class ReportInfo {
        private String path;
        private String workId;
        public ReportInfo(String path) { this.path = path; }
        public ReportInfo(String path, String workId) { this.path = path; this.workId = workId; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getWorkId() { return workId; }
        public void setWorkId(String workId) { this.workId = workId; }
    }

    public QueryResponse(String llmResponse, List<ReportInfo> reports, LocalDateTime executedAt, Long executionTimeMs) {
        this.llmResponse = llmResponse;
        this.reports = reports;
        this.executedAt = executedAt;
        this.executionTimeMs = executionTimeMs;
    }
    public String getLlmResponse() { return llmResponse; }
    public void setLlmResponse(String llmResponse) { this.llmResponse = llmResponse; }
    public List<ReportInfo> getReports() { return reports; }
    public void setReports(List<ReportInfo> reports) { this.reports = reports; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
}


