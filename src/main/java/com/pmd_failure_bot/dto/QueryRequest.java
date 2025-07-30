package com.pmd_failure_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

public class QueryRequest {
    
    @JsonProperty("file_path")
    private String filePath;
    
    @JsonProperty("executor_kerberos_id")
    private String executorKerberosId;
    
    @JsonProperty("report_date")
    private LocalDate reportDate;
    
    @JsonProperty("report_id")
    private String reportId;
    
    @JsonProperty("step_name")
    private String stepName;
    
    @JsonProperty("worker_process_group_id")
    private String workerProcessGroupId;
    
    private String hostname;
    
    @JsonProperty("requesting_kerberos_id")
    private String requestingKerberosId;
    
    private String query;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getExecutorKerberosId() {
        return executorKerberosId;
    }

    public void setExecutorKerberosId(String executorKerberosId) {
        this.executorKerberosId = executorKerberosId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getWorkerProcessGroupId() {
        return workerProcessGroupId;
    }

    public void setWorkerProcessGroupId(String workerProcessGroupId) {
        this.workerProcessGroupId = workerProcessGroupId;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getRequestingKerberosId() {
        return requestingKerberosId;
    }

    public void setRequestingKerberosId(String requestingKerberosId) {
        this.requestingKerberosId = requestingKerberosId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
} 