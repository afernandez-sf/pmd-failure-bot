package com.pmd_failure_bot.web.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
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
    

    @Data
    @NoArgsConstructor
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

        public ProcessedRecord(String attachmentId, String attachmentName, String recordId, String workId, Integer caseNumber,
                               String stepName, int logsProcessed, String status, String error) {
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
    }

    public LogImportResponse(String message, int totalAttachments, int processedAttachments, int skippedAttachments, int successfulLogs,
                             int failedLogs, List<ProcessedRecord> processedRecords) {
        this.message = message;
        this.totalAttachments = totalAttachments;
        this.processedAttachments = processedAttachments;
        this.skippedAttachments = skippedAttachments;
        this.successfulLogs = successfulLogs;
        this.failedLogs = failedLogs;
        this.processedRecords = processedRecords;
    }
}


