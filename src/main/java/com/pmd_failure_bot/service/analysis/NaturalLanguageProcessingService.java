package com.pmd_failure_bot.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.pmd_failure_bot.common.util.StepNameNormalizer;
import com.pmd_failure_bot.integration.ai.AIService;
import com.pmd_failure_bot.integration.ai.PromptTemplates;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Service for natural language processing and parameter extraction
 */
@Service
public class NaturalLanguageProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(NaturalLanguageProcessingService.class);

    private final AIService aiService;
    private final PromptTemplates promptTemplates;
    private final StepNameNormalizer stepNameNormalizer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public NaturalLanguageProcessingService(AIService aiService, PromptTemplates promptTemplates, StepNameNormalizer stepNameNormalizer) {
        this.aiService = aiService;
        this.promptTemplates = promptTemplates;
        this.stepNameNormalizer = stepNameNormalizer;
    }

    /**
     * Extracts structured parameters from natural language query using LLM
     */
    public ParameterExtractionResult extractParameters(String naturalLanguageQuery, String conversationContext) {
        try {
            String extractionPrompt = promptTemplates.parameterExtraction(
                naturalLanguageQuery,
                conversationContext,
                LocalDate.now().toString()
            );
            String llmResponse = aiService.generate(extractionPrompt);

            return parseParameterExtractionResponse(llmResponse, naturalLanguageQuery);

        } catch (Exception e) {
            logger.error("Error extracting parameters from query: {}", naturalLanguageQuery, e);
            return new ParameterExtractionResult(new QueryRequest(), 0.0, "LLM_ERROR");
        }
    }

    /**
     * Detects if the natural language query is requesting an import operation
     */
    public boolean isImportRequest(String naturalLanguageQuery) {
        String query = naturalLanguageQuery.toLowerCase();
        return query.contains("import") || query.contains("pull") || query.contains("fetch") ||
               query.contains("get logs") || query.contains("download") || query.contains("load");
    }

    /**
     * Parses the LLM response to extract parameters
     */
    private ParameterExtractionResult parseParameterExtractionResponse(String llmResponse, String originalQuery) {
        try {
            // Clean the response to extract JSON
            String jsonResponse = JsonUtils.extractJsonFromResponse(llmResponse);
            JsonNode responseNode = objectMapper.readTree(jsonResponse);

            QueryRequest queryRequest = new QueryRequest();

            // Extract each parameter
            if (responseNode.has("record_id") && !responseNode.get("record_id").isNull()) {
                queryRequest.setRecordId(responseNode.get("record_id").asText());
            }

            if (responseNode.has("work_id") && !responseNode.get("work_id").isNull()) {
                queryRequest.setWorkId(responseNode.get("work_id").asText());
            }

            if (responseNode.has("case_number") && !responseNode.get("case_number").isNull()) {
                queryRequest.setCaseNumber(responseNode.get("case_number").asInt());
            }

            if (responseNode.has("step_name") && !responseNode.get("step_name").isNull()) {
                String normalized = stepNameNormalizer.normalize(responseNode.get("step_name").asText());
                queryRequest.setStepName(normalized);
            }

            if (responseNode.has("attachment_id") && !responseNode.get("attachment_id").isNull()) {
                queryRequest.setAttachmentId(responseNode.get("attachment_id").asText());
            }

            if (responseNode.has("hostname") && !responseNode.get("hostname").isNull()) {
                queryRequest.setDatacenter(responseNode.get("hostname").asText());
            }

            if (responseNode.has("report_date") && !responseNode.get("report_date").isNull()) {
                try {
                    queryRequest.setReportDate(LocalDate.parse(responseNode.get("report_date").asText()));
                } catch (DateTimeParseException e) {
                    logger.warn("Invalid date format in LLM response: {}", responseNode.get("report_date").asText());
                }
            }

            if (responseNode.has("query") && !responseNode.get("query").isNull()) {
                queryRequest.setQuery(responseNode.get("query").asText());
            } else {
                queryRequest.setQuery(originalQuery);
            }

            double confidence = 0.8; // Default confidence
            if (responseNode.has("confidence") && !responseNode.get("confidence").isNull()) {
                confidence = responseNode.get("confidence").asDouble();
            }

            String intent = "metrics"; // Default intent for queries is metrics
            if (responseNode.has("intent") && !responseNode.get("intent").isNull()) {
                intent = responseNode.get("intent").asText();
            }
            // response_mode (metrics vs analysis) for 'query' intent
            String responseMode = null;
            if (responseNode.has("response_mode") && !responseNode.get("response_mode").isNull()) {
                responseMode = responseNode.get("response_mode").asText();
            }

            return new ParameterExtractionResult(queryRequest, confidence, "LLM_EXTRACTION", intent, responseMode);

        } catch (JsonProcessingException e) {
            logger.error("Failed to parse LLM response as JSON: {}", llmResponse, e);
            return new ParameterExtractionResult(new QueryRequest(), 0.0, "LLM_PARSE_ERROR");
        }
    }

    /**
     * Result class for parameter extraction
     */
    public static class ParameterExtractionResult {
        private final QueryRequest queryRequest;
        private final double confidence;
        private final String extractionMethod;
        private final String intent;

        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod, String intent) {
            this.queryRequest = queryRequest;
            this.confidence = confidence;
            this.extractionMethod = extractionMethod;
            this.intent = intent != null ? intent : "query"; // Default to query
            this.responseMode = null;
        }

        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod, String intent, String responseMode) {
            this.queryRequest = queryRequest;
            this.confidence = confidence;
            this.extractionMethod = extractionMethod;
            this.intent = intent != null ? intent : "query";
            this.responseMode = responseMode;
        }

        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod) {
            this.queryRequest = queryRequest;
            this.confidence = confidence;
            this.extractionMethod = extractionMethod;
            this.intent = "metrics";
            this.responseMode = null;
        }

        public QueryRequest getQueryRequest() { return queryRequest; }
        public double getConfidence() { return confidence; }
        public String getExtractionMethod() { return extractionMethod; }
        public String getIntent() { return intent; }
        public boolean isImportRequest() { return "import".equalsIgnoreCase(intent); }
        private final String responseMode; // "metrics" or "analysis" for query intent
        public String getResponseMode() { return responseMode; }
    }
}
