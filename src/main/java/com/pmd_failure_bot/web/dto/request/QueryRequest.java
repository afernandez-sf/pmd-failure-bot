package com.pmd_failure_bot.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

@Data
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
}


