package com.pmd_failure_bot.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.web.dto.request.QueryRequest;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.pmd_failure_bot.integration.ai.AIService;
import com.pmd_failure_bot.integration.ai.PromptTemplates;
import com.pmd_failure_bot.common.util.JsonUtils;
import com.pmd_failure_bot.common.util.StepNameNormalizer;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for natural language processing and parameter extraction
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaturalLanguageProcessingService {

    private final AIService aiService;
    private final PromptTemplates promptTemplates;
    private final StepNameNormalizer stepNameNormalizer;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            Map<String, Object> schema = buildParameterExtractionSchema();

            String llmResponse = aiService.generateStructured(extractionPrompt, schema);
            return parseParameterExtractionResponse(llmResponse, naturalLanguageQuery);

        } catch (Exception e) {
            log.error("Error extracting parameters from query: {}", naturalLanguageQuery, e);
            return new ParameterExtractionResult(new QueryRequest(), 0.0, "LLM_ERROR");
        }
    }

    /**
     * Builds the JSON schema for parameter extraction
     */
    private Map<String, Object> buildParameterExtractionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("name", "extracted_parameters");
        schema.put("strict", true);
        
        Map<String, Object> schemaDef = new HashMap<>();
        schemaDef.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("record_id", Map.of("type", "string"));
        properties.put("work_id", Map.of("type", "string"));
        properties.put("case_number", Map.of("type", "integer"));
        properties.put("step_name", Map.of("type", "string"));
        properties.put("attachment_id", Map.of("type", "string"));
        properties.put("datacenter", Map.of("type", "string"));
        properties.put("report_date", Map.of("type", "string"));
        properties.put("query", Map.of("type", "string"));
        properties.put("intent", Map.of("type", "string", "enum", List.of("import","metrics","analysis")));
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        properties.put("is_relevant", Map.of("type", "boolean"));
        properties.put("irrelevant_reason", Map.of("type", "string"));
        
        schemaDef.put("properties", properties);
        schemaDef.put("additionalProperties", false);
        schema.put("schema", schemaDef);
        
        return schema;
    }

    /**
     * Parses the LLM response to extract parameters
     */
    private ParameterExtractionResult parseParameterExtractionResponse(String llmResponse, String originalQuery) {
        try {
            String jsonResponse = JsonUtils.extractJsonFromResponse(llmResponse);
            JsonNode responseNode = objectMapper.readTree(jsonResponse);

            QueryRequest queryRequest = new QueryRequest();

            // Extract each parameter
            extractStringField(responseNode, "record_id", queryRequest::setRecordId);
            extractStringField(responseNode, "work_id", queryRequest::setWorkId);
            extractIntField(responseNode, "case_number", queryRequest::setCaseNumber);
            extractStringField(responseNode, "attachment_id", queryRequest::setAttachmentId);
            extractStringField(responseNode, "datacenter", queryRequest::setDatacenter);
            
            if (hasNonNullValue(responseNode, "step_name")) {
                String normalized = stepNameNormalizer.normalize(responseNode.get("step_name").asText());
                queryRequest.setStepName(normalized);
            }

            if (hasNonNullValue(responseNode, "report_date")) {
                try {
                    queryRequest.setReportDate(LocalDate.parse(responseNode.get("report_date").asText()));
                } catch (DateTimeParseException e) {
                    log.warn("Invalid date format in LLM response: {}", responseNode.get("report_date").asText());
                }
            }

            if (hasNonNullValue(responseNode, "query")) {
                queryRequest.setQuery(responseNode.get("query").asText());
            } else {
                queryRequest.setQuery(originalQuery);
            }

            double confidence = extractDoubleField(responseNode, "confidence", 0.8);
            String intent = extractStringField(responseNode, "intent");
            boolean isRelevant = extractBooleanField(responseNode, "is_relevant", true);
            String irrelevantReason = extractStringField(responseNode, "irrelevant_reason");

            return new ParameterExtractionResult(queryRequest, confidence, "LLM_EXTRACTION", intent, isRelevant, irrelevantReason);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM response as JSON: {}", llmResponse, e);
            return new ParameterExtractionResult(new QueryRequest(), 0.0, "LLM_PARSE_ERROR");
        }
    }

    // Helper methods for JSON extraction
    private boolean hasNonNullValue(JsonNode node, String fieldName) {
        return node.has(fieldName) && !node.get(fieldName).isNull();
    }

    private void extractStringField(JsonNode node, String fieldName, java.util.function.Consumer<String> setter) {
        if (hasNonNullValue(node, fieldName)) {
            setter.accept(node.get(fieldName).asText());
        }
    }

    private String extractStringField(JsonNode node, String fieldName) {
        return hasNonNullValue(node, fieldName) ? node.get(fieldName).asText() : null;
    }

    private void extractIntField(JsonNode node, String fieldName, java.util.function.Consumer<Integer> setter) {
        if (hasNonNullValue(node, fieldName)) {
            setter.accept(node.get(fieldName).asInt());
        }
    }

    private double extractDoubleField(JsonNode node, String fieldName, double defaultValue) {
        return hasNonNullValue(node, fieldName) ? node.get(fieldName).asDouble() : defaultValue;
    }

    private boolean extractBooleanField(JsonNode node, String fieldName, boolean defaultValue) {
        return hasNonNullValue(node, fieldName) ? node.get(fieldName).asBoolean(defaultValue) : defaultValue;
    }

    /**
     * Result class for parameter extraction
     */
    @Getter
    @AllArgsConstructor
    public static class ParameterExtractionResult {
        private final QueryRequest queryRequest;
        private final double confidence;
        private final String extractionMethod;
        private final String intent;
        private final boolean relevant;
        private final String irrelevantReason;

        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod) {
            this(queryRequest, confidence, extractionMethod, null, true, null);
        }

        public boolean isImportRequest() {
            return "import".equalsIgnoreCase(intent);
        }
    }
}
