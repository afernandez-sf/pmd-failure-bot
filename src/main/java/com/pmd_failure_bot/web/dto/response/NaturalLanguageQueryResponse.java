package com.pmd_failure_bot.web.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import java.time.LocalDateTime;
import java.util.List;

public class NaturalLanguageQueryResponse {
    private String answer;
    @JsonProperty("extracted_parameters")
    private QueryRequest extractedParameters;
    @JsonProperty("reports_found")
    private List<QueryResponse.ReportInfo> reportsFound;
    @JsonProperty("parameter_extraction_confidence")
    private Double parameterExtractionConfidence;
    @JsonProperty("executed_at")
    private LocalDateTime executedAt;
    @JsonProperty("execution_time_ms")
    private Long executionTimeMs;
    @JsonProperty("conversation_id")
    private String conversationId;

    public NaturalLanguageQueryResponse() {}

    public NaturalLanguageQueryResponse(String answer, QueryRequest extractedParameters,
                                        List<QueryResponse.ReportInfo> reportsFound,
                                        Double parameterExtractionConfidence,
                                        LocalDateTime executedAt, Long executionTimeMs) {
        this.answer = answer;
        this.extractedParameters = extractedParameters;
        this.reportsFound = reportsFound;
        this.parameterExtractionConfidence = parameterExtractionConfidence;
        this.executedAt = executedAt;
        this.executionTimeMs = executionTimeMs;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public QueryRequest getExtractedParameters() { return extractedParameters; }
    public void setExtractedParameters(QueryRequest extractedParameters) { this.extractedParameters = extractedParameters; }
    public List<QueryResponse.ReportInfo> getReportsFound() { return reportsFound; }
    public void setReportsFound(List<QueryResponse.ReportInfo> reportsFound) { this.reportsFound = reportsFound; }
    public Double getParameterExtractionConfidence() { return parameterExtractionConfidence; }
    public void setParameterExtractionConfidence(Double parameterExtractionConfidence) { this.parameterExtractionConfidence = parameterExtractionConfidence; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
}


