package com.pmd_failure_bot.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LogImportRequest {
    @JsonProperty("case_number")
    private Integer caseNumber;

    @JsonProperty("step_name")
    private String stepName;

    public LogImportRequest() {}

    public LogImportRequest(Integer caseNumber, String stepName) {
        this.caseNumber = caseNumber;
        this.stepName = stepName;
    }

    public Integer getCaseNumber() { return caseNumber; }
    public void setCaseNumber(Integer caseNumber) { this.caseNumber = caseNumber; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public boolean isValid() {
        return (caseNumber != null) || (stepName != null && !stepName.trim().isEmpty());
    }
}


