package com.pmd_failure_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public class QueryRequest {
    
    @JsonProperty("record_id")
    private String recordId;
    
    @JsonProperty("work_id")
    private String workId;
    
    @JsonProperty("case_number")
    private Integer caseNumber;
    
    @JsonProperty("step_name")
    private String stepName;
    
    @JsonProperty("attachment_id")
    private String attachmentId;
    
    @JsonProperty("datacenter")
    private String datacenter;
    

    
    @JsonProperty("report_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate reportDate;
    
    private String query;

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getWorkId() {
        return workId;
    }

    public void setWorkId(String workId) {
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

    public String getDatacenter() {
        return datacenter;
    }

    public void setDatacenter(String datacenter) {
        this.datacenter = datacenter;
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