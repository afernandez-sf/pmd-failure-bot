package com.pmd_failure_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

public class QueryRequest {
    
    @JsonProperty("record_id")
    private String recordId;
    
    @JsonProperty("work_id")
    private Integer workId;
    
    @JsonProperty("case_number")
    private Integer caseNumber;
    
    @JsonProperty("step_name")
    private String stepName;
    
    @JsonProperty("attachment_id")
    private String attachmentId;
    
    private String hostname;
    
    @JsonProperty("executor_kerberos_id")
    private String executorKerberosId;
    
    @JsonProperty("requesting_kerberos_id")
    private String requestingKerberosId;
    
    @JsonProperty("report_date")
    private LocalDate reportDate;
    
    private String query;

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public Integer getWorkId() {
        return workId;
    }

    public void setWorkId(Integer workId) {
        this.workId = workId;
    }

    public Integer getCaseNumber() {
        return caseNumber;
    }

    public void setCaseNumber(Integer caseNumber) {
        this.caseNumber = caseNumber;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(String attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getExecutorKerberosId() {
        return executorKerberosId;
    }

    public void setExecutorKerberosId(String executorKerberosId) {
        this.executorKerberosId = executorKerberosId;
    }

    public String getRequestingKerberosId() {
        return requestingKerberosId;
    }

    public void setRequestingKerberosId(String requestingKerberosId) {
        this.requestingKerberosId = requestingKerberosId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
} 