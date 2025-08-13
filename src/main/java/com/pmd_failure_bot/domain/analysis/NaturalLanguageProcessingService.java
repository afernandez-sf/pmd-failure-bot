package com.pmd_failure_bot.domain.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.pmd_failure_bot.infrastructure.ai.AIService;
import com.pmd_failure_bot.infrastructure.ai.PromptTemplates;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for natural language processing and parameter extraction
 */
@Service
public class NaturalLanguageProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(NaturalLanguageProcessingService.class);
    
    private final AIService aiService;
    private final PromptTemplates promptTemplates;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public NaturalLanguageProcessingService(AIService aiService, PromptTemplates promptTemplates) {
        this.aiService = aiService;
        this.promptTemplates = promptTemplates;
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
            // Fallback to regex-based extraction
            return fallbackParameterExtraction(naturalLanguageQuery);
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
                queryRequest.setStepName(responseNode.get("step_name").asText());
            }
            
            if (responseNode.has("attachment_id") && !responseNode.get("attachment_id").isNull()) {
                queryRequest.setAttachmentId(responseNode.get("attachment_id").asText());
            }
            
            if (responseNode.has("hostname") && !responseNode.get("hostname").isNull()) {
                queryRequest.setHostname(responseNode.get("hostname").asText());
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
            
            String intent = "query"; // Default intent
            if (responseNode.has("intent") && !responseNode.get("intent").isNull()) {
                intent = responseNode.get("intent").asText();
            }
            
            return new ParameterExtractionResult(queryRequest, confidence, "LLM_EXTRACTION", intent);
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse LLM response as JSON: {}", llmResponse, e);
            return fallbackParameterExtraction(originalQuery);
        }
    }
    
    
    /**
     * Fallback parameter extraction using regex patterns
     */
    private ParameterExtractionResult fallbackParameterExtraction(String text) {
        QueryRequest request = new QueryRequest();
        String originalText = text;
        
        // Pattern for case number: case_number:123456, case:123456, case 123456
        Pattern casePattern = Pattern.compile("(?:case[_\\s]*(?:number)?[:\\s=]?)(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher caseMatcher = casePattern.matcher(text);
        if (caseMatcher.find()) {
            request.setCaseNumber(Integer.parseInt(caseMatcher.group(1)));
            text = text.replaceAll(casePattern.pattern(), "").trim();
        }
        
        // Pattern for step name: step:stepname, step=stepname
        Pattern stepPattern = Pattern.compile("(?:step[:\\s=])([\\w_-]+)", Pattern.CASE_INSENSITIVE);
        Matcher stepMatcher = stepPattern.matcher(text);
        if (stepMatcher.find()) {
            request.setStepName(mapStepNameVariations(stepMatcher.group(1)));
            text = text.replaceAll(stepPattern.pattern(), "").trim();
        }
        
        // Pattern for hostname: host:hostname, server:hostname
        Pattern hostPattern = Pattern.compile("(?:(?:host|server)[:\\s=])([^\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher hostMatcher = hostPattern.matcher(text);
        if (hostMatcher.find()) {
            request.setHostname(hostMatcher.group(1));
            text = text.replaceAll(hostPattern.pattern(), "").trim();
        }
        
        // Pattern for date: date:2024-01-01, yesterday, today
        text = extractDateFromText(text, request);
        
        // The remaining text is the query
        request.setQuery(text.trim().isEmpty() ? originalText : text.trim());
        
        return new ParameterExtractionResult(request, 0.6, "REGEX_FALLBACK");
    }
    
    /**
     * Maps step name variations to known patterns
     */
    private String mapStepNameVariations(String stepName) {
        String upperStep = stepName.toUpperCase();
        
        Map<String, String> stepMappings = Map.of(
            "SSH", "SSH_TO_ALL_HOSTS",
            "GRIDFORCE", "GRIDFORCE_APP_LOG_COPY", 
            "KM", "KM_VALIDATION_RELENG",
            "CREATE_IR", "CREATE_IR_ORGS_TABLE_PRESTO_TGT"
        );
        
        for (Map.Entry<String, String> entry : stepMappings.entrySet()) {
            if (upperStep.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return stepName;
    }
    
    /**
     * Extracts date from text including relative dates
     */
    private String extractDateFromText(String text, QueryRequest request) {
        // Absolute date pattern
        Pattern datePattern = Pattern.compile("(?:date[:\\s=])?(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
        Matcher dateMatcher = datePattern.matcher(text);
        if (dateMatcher.find()) {
            try {
                request.setReportDate(LocalDate.parse(dateMatcher.group(1)));
                return text.replaceAll(datePattern.pattern(), "").trim();
            } catch (DateTimeParseException e) {
                logger.warn("Invalid date format: {}", dateMatcher.group(1));
            }
        }
        
        // Relative date patterns
        LocalDate today = LocalDate.now();
        if (text.toLowerCase().contains("yesterday")) {
            request.setReportDate(today.minusDays(1));
            return text.replaceAll("(?i)yesterday", "").trim();
        }
        
        if (text.toLowerCase().contains("today")) {
            request.setReportDate(today);
            return text.replaceAll("(?i)today", "").trim();
        }
        
        if (text.toLowerCase().contains("last week")) {
            request.setReportDate(today.minusWeeks(1));
            return text.replaceAll("(?i)last week", "").trim();
        }
        
        return text;
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
        }
        
        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod) {
            this(queryRequest, confidence, extractionMethod, "query");
        }
        
        public QueryRequest getQueryRequest() {
            return queryRequest;
        }
        
        public double getConfidence() {
            return confidence;
        }
        
        public String getExtractionMethod() {
            return extractionMethod;
        }
        
        public String getIntent() {
            return intent;
        }
        
        public boolean isImportRequest() {
            return "import".equalsIgnoreCase(intent);
        }
    }
}