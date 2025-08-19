package com.pmd_failure_bot.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogImportRequest {
    @JsonProperty("case_number")
    private Integer caseNumber;

    @JsonProperty("step_name")
    private String stepName;

    public boolean isValid() {
        return (caseNumber != null) || (stepName != null && !stepName.trim().isEmpty());
    }
}


