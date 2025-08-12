package com.pmd_failure_bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.dto.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NaturalLanguageProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(NaturalLanguageProcessingService.class);
    
    private final SalesforceLlmGatewayService llmGatewayService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public NaturalLanguageProcessingService(SalesforceLlmGatewayService llmGatewayService) {
        this.llmGatewayService = llmGatewayService;
    }
    
    /**
     * Extracts structured parameters from natural language query using LLM
     */
    public ParameterExtractionResult extractParameters(String naturalLanguageQuery, String conversationContext) {
        try {
            String extractionPrompt = buildParameterExtractionPrompt(naturalLanguageQuery, conversationContext);
            String llmResponse = llmGatewayService.generateResponse(extractionPrompt);
            
            return parseParameterExtractionResponse(llmResponse, naturalLanguageQuery);
            
        } catch (Exception e) {
            logger.error("Error extracting parameters from query: {}", naturalLanguageQuery, e);
            // Fallback to regex-based extraction
            return fallbackParameterExtraction(naturalLanguageQuery);
        }
    }
    
    /**
     * Builds the prompt for parameter extraction
     */
    private String buildParameterExtractionPrompt(String query, String conversationContext) {
        StringBuilder prompt = new StringBuilder();
        
        String currentDate = LocalDate.now().toString();
        
        prompt.append("<instructions>\n")
              .append("You are an expert at extracting structured parameters from natural language queries about deployment failures and PMD logs.\n\n")
              .append("Extract the following parameters from the user's query and return them as a JSON object:\n")
              .append("- record_id: Salesforce record identifier (if mentioned)\n")
              .append("- work_id: GUS work item identifier (if mentioned)\n")
              .append("- case_number: Support case number (integers only, if mentioned)\n")
              .append("- step_name: Deployment step name (if mentioned - examples: SSH_TO_ALL_HOSTS, GRIDFORCE_APP_LOG_COPY, KM_VALIDATION_RELENG, CREATE_IR_ORGS_TABLE_PRESTO_TGT)\n")
              .append("- attachment_id: Salesforce attachment ID (if mentioned)\n")
              .append("- hostname: Target hostname or server name (if mentioned)\n")
              .append("- report_date: Date in YYYY-MM-DD format (if mentioned, including relative dates like \"yesterday\", \"last week\")\n")
              .append("- query: The refined natural language question to ask about the logs\n")
              .append("- confidence: Your confidence level (0.0 to 1.0) in the parameter extraction\n\n")
              .append("Guidelines:\n")
              .append("1. Only extract parameters that are explicitly mentioned or strongly implied\n")
              .append("2. For dates, convert relative terms to actual dates (today is ").append(currentDate).append(")\n")
              .append("3. For step names, match partial names to known patterns (e.g., \"SSH\" -> \"SSH_TO_ALL_HOSTS\")\n")
              .append("4. For case numbers, extract only numeric values\n")
              .append("5. The query field should be the main question being asked, cleaned of parameter information\n")
              .append("6. Set confidence based on how clearly the parameters were stated\n")
              .append("7. If no parameters are found, return null for those fields\n")
              .append("8. Return ONLY valid JSON, no other text\n\n")
              .append("Example input: \"What went wrong with case 123456's SSH deployment yesterday?\"\n")
              .append("Example output: {\"record_id\": null, \"work_id\": null, \"case_number\": 123456, \"step_name\": \"SSH_TO_ALL_HOSTS\", \"attachment_id\": null, \"hostname\": null, \"report_date\": \"2024-01-15\", \"query\": \"What went wrong with deployment\", \"confidence\": 0.9}\n")
              .append("</instructions>\n\n");
        
        if (conversationContext != null && !conversationContext.trim().isEmpty()) {
            prompt.append("<conversation_context>\n")
                  .append(conversationContext)
                  .append("\n</conversation_context>\n\n");
        }
        
        prompt.append("<user_query>\n")
              .append(query)
              .append("\n</user_query>\n");
        
        return prompt.toString();
    }
    
    /**
     * Parses the LLM response to extract parameters
     */
    private ParameterExtractionResult parseParameterExtractionResponse(String llmResponse, String originalQuery) {
        try {
            // Clean the response to extract JSON
            String jsonResponse = extractJsonFromResponse(llmResponse);
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
            
            return new ParameterExtractionResult(queryRequest, confidence, "LLM_EXTRACTION");
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse LLM response as JSON: {}", llmResponse, e);
            return fallbackParameterExtraction(originalQuery);
        }
    }
    
    /**
     * Extracts JSON from LLM response that might contain markdown code blocks or other text
     */
    private String extractJsonFromResponse(String response) {
        // First, try to extract from markdown code blocks
        if (response.contains("```json")) {
            int jsonStart = response.indexOf("```json") + 7; // Skip "```json"
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                String jsonContent = response.substring(jsonStart, jsonEnd).trim();
                return jsonContent;
            }
        }
        
        // Fallback: Find JSON object in the response
        int startBrace = response.indexOf('{');
        int endBrace = response.lastIndexOf('}');
        
        if (startBrace >= 0 && endBrace > startBrace) {
            return response.substring(startBrace, endBrace + 1);
        }
        
        return response;
    }
    
    /**
     * Fallback parameter extraction using regex patterns (similar to current SlackService)
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
        
        public ParameterExtractionResult(QueryRequest queryRequest, double confidence, String extractionMethod) {
            this.queryRequest = queryRequest;
            this.confidence = confidence;
            this.extractionMethod = extractionMethod;
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
    }
}