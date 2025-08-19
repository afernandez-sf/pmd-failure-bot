package com.pmd_failure_bot.web.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class NaturalLanguageQueryResponse {
    private String answer;
    
    @JsonProperty("extracted_parameters")
    private QueryRequest extractedParameters;
    
    @JsonProperty("reports_found")
    private List<QueryResponse.ReportInfo> reportsFound;
    
    @JsonProperty("parameter_extraction_confidence")
    private Double parameterExtractionConfidence;
    
    @JsonProperty("conversation_id")
    private String conversationId;

    public NaturalLanguageQueryResponse(String answer, QueryRequest extractedParameters, List<QueryResponse.ReportInfo> reportsFound,
                                        Double parameterExtractionConfidence) {
        this.answer = answer;
        this.extractedParameters = extractedParameters;
        this.reportsFound = reportsFound;
        this.parameterExtractionConfidence = parameterExtractionConfidence;
    }
}


