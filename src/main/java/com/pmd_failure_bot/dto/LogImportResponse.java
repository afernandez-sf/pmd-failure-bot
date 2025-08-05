package com.pmd_failure_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public class LogImportResponse {
    
    private String message;
    
    @JsonProperty("total_attachments")
    private int totalAttachments;
    
    @JsonProperty("processed_attachments")
    private int processedAttachments;
    
    @JsonProperty("skipped_attachments")
    private int skippedAttachments;
    
    @JsonProperty("successful_logs")
    private int successfulLogs;
    
    @JsonProperty("failed_logs")
    private int failedLogs;
    
    @JsonProperty("processed_records")
    private List<ProcessedRecord> processedRecords;
    
    @JsonProperty("execution_time_ms")
    private long executionTimeMs;
    
    @JsonProperty("executed_at")
    private LocalDateTime executedAt;
    
    public static class ProcessedRecord {
        @JsonProperty("attachment_id")
        private String attachmentId;
        
        @JsonProperty("attachment_name")
        private String attachmentName;
        
        @JsonProperty("record_id")
        private String recordId;
        
        @JsonProperty("work_id")
        private String workId;
        
        @JsonProperty("case_number")
        private Integer caseNumber;
        
        @JsonProperty("step_name")
        private String stepName;
        
        @JsonProperty("logs_processed")
        private int logsProcessed;
        
        private String status;
        private String error;
        
        public ProcessedRecord() {}
        
        public ProcessedRecord(String attachmentId, String attachmentName, String recordId, 
                             String workId, Integer caseNumber, String stepName, 
                             int logsProcessed, String status, String error) {
            this.attachmentId = attachmentId;
            this.attachmentName = attachmentName;
            this.recordId = recordId;
            this.workId = workId;
            this.caseNumber = caseNumber;
            this.stepName = stepName;
            this.logsProcessed = logsProcessed;
            this.status = status;
            this.error = error;
        }
        
        // Getters and setters
        public String getAttachmentId() { return attachmentId; }
        public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
        
        public String getAttachmentName() { return attachmentName; }
        public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }
        
        public String getRecordId() { return recordId; }
        public void setRecordId(String recordId) { this.recordId = recordId; }
        
        public String getWorkId() { return workId; }
        public void setWorkId(String workId) { this.workId = workId; }
        
        public Integer getCaseNumber() { return caseNumber; }
        public void setCaseNumber(Integer caseNumber) { this.caseNumber = caseNumber; }
        
        public String getStepName() { return stepName; }
        public void setStepName(String stepName) { this.stepName = stepName; }
        
        public int getLogsProcessed() { return logsProcessed; }
        public void setLogsProcessed(int logsProcessed) { this.logsProcessed = logsProcessed; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
    
    public LogImportResponse() {}
    
    public LogImportResponse(String message, int totalAttachments, int processedAttachments, 
                           int skippedAttachments, int successfulLogs, int failedLogs,
                           List<ProcessedRecord> processedRecords, long executionTimeMs) {
        this.message = message;
        this.totalAttachments = totalAttachments;
        this.processedAttachments = processedAttachments;
        this.skippedAttachments = skippedAttachments;
        this.successfulLogs = successfulLogs;
        this.failedLogs = failedLogs;
        this.processedRecords = processedRecords;
        this.executionTimeMs = executionTimeMs;
        this.executedAt = LocalDateTime.now();
    }
    
    // Getters and setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public int getTotalAttachments() { return totalAttachments; }
    public void setTotalAttachments(int totalAttachments) { this.totalAttachments = totalAttachments; }
    
    public int getProcessedAttachments() { return processedAttachments; }
    public void setProcessedAttachments(int processedAttachments) { this.processedAttachments = processedAttachments; }
    
    public int getSkippedAttachments() { return skippedAttachments; }
    public void setSkippedAttachments(int skippedAttachments) { this.skippedAttachments = skippedAttachments; }
    
    public int getSuccessfulLogs() { return successfulLogs; }
    public void setSuccessfulLogs(int successfulLogs) { this.successfulLogs = successfulLogs; }
    
    public int getFailedLogs() { return failedLogs; }
    public void setFailedLogs(int failedLogs) { this.failedLogs = failedLogs; }
    
    public List<ProcessedRecord> getProcessedRecords() { return processedRecords; }
    public void setProcessedRecords(List<ProcessedRecord> processedRecords) { this.processedRecords = processedRecords; }
    
    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}