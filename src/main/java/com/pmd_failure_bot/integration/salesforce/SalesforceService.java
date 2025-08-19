package com.pmd_failure_bot.integration.salesforce;

import com.pmd_failure_bot.config.SalesforceConfig;
import com.pmd_failure_bot.common.util.StepNameNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for interacting with Salesforce API
 */
@Service
public class SalesforceService {
    
    private static final Logger logger = LoggerFactory.getLogger(SalesforceService.class);
    
    private final SalesforceConfig salesforceConfig;
    private final ObjectMapper objectMapper;
    private final StepNameNormalizer stepNameNormalizer;
    private final CloseableHttpClient httpClient;
    
    private String sessionId;
    private String instanceUrl;
    
    
    @Autowired
    public SalesforceService(SalesforceConfig salesforceConfig, StepNameNormalizer stepNameNormalizer) {
        this.salesforceConfig = salesforceConfig;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClients.createDefault();
        this.stepNameNormalizer = stepNameNormalizer;
    }
    
    /**
     * Login to Salesforce and obtain session ID and instance URL
     */
    public void login() throws Exception {
        salesforceConfig.validate();
        
        logger.info("Logging into Salesforce...");
        
        HttpPost loginPost = new HttpPost(salesforceConfig.getLoginUrl() + "/services/Soap/u/" + salesforceConfig.getApiVersion().replace("v", ""));
        
        String soapBody = String.format("""
            <?xml version="1.0" encoding="utf-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:urn="urn:partner.soap.sforce.com">
                <soapenv:Header/>
                <soapenv:Body>
                    <urn:login>
                        <urn:username>%s</urn:username>
                        <urn:password>%s</urn:password>
                    </urn:login>
                </soapenv:Body>
            </soapenv:Envelope>
            """, salesforceConfig.getUsername(), salesforceConfig.getPasswordWithToken());
        
        loginPost.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(soapBody, org.apache.hc.core5.http.ContentType.create("text/xml", "UTF-8")));
        loginPost.setHeader("SOAPAction", "login");
        
        String response = httpClient.execute(loginPost, httpResponse -> new String(httpResponse.getEntity().getContent().readAllBytes()));
        
        // Parse session ID and server URL from SOAP response
        Pattern sessionIdPattern = Pattern.compile("<sessionId>([^<]+)</sessionId>");
        Pattern serverUrlPattern = Pattern.compile("<serverUrl>([^<]+)</serverUrl>");
        
        Matcher sessionMatcher = sessionIdPattern.matcher(response);
        Matcher serverMatcher = serverUrlPattern.matcher(response);
        
        if (sessionMatcher.find() && serverMatcher.find()) {
            this.sessionId = sessionMatcher.group(1);
            String serverUrl = serverMatcher.group(1);
            this.instanceUrl = serverUrl.substring(0, serverUrl.indexOf("/services"));
            logger.info("Successfully logged into Salesforce");
        } else {
            throw new Exception("Failed to login to Salesforce: " + response);
        }
    }
    
    /**
     * Query failed step attachments by step name
     */
    public List<Map<String, Object>> queryFailedStepAttachments(String stepName) throws Exception {
        ensureLoggedIn();
        
        if (stepName == null || !stepName.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("Invalid step name: " + stepName);
        }
        
		String query = String.format("""
			SELECT Id, WorkId_and_Subject__c,
			(
				SELECT Id, Name, BodyLength, ContentType, LastModifiedDate
				FROM Attachments
			)
			FROM ADM_Work__c 
			WHERE Subject__c LIKE 'Step: %s%%Status: FAILED%%PMD/IR%%'
			""", stepName);
        
        logger.info("Querying failed step: {}", stepName);
        return executeQuery(query);
    }
    
    /**
     * Query failed step attachments by case number
     */
    public List<Map<String, Object>> queryFailedAttachmentsByCaseNumber(Integer caseNumber) throws Exception {
        ensureLoggedIn();

        if (caseNumber == null || caseNumber <= 0) {
            throw new IllegalArgumentException("Invalid case number: " + caseNumber);
        }
        
		String query = String.format("""
			SELECT Id, WorkId_and_Subject__c,
			(
				SELECT Id, Name, BodyLength, ContentType, LastModifiedDate
				FROM Attachments
			)
			FROM ADM_Work__c 
			WHERE WorkId_and_Subject__c LIKE '%%Status: FAILED%%Case: %d%%PMD/IR%%'
			""", caseNumber);

        logger.info("Querying failed attachments for case: {}", caseNumber);
        return executeQuery(query);
    }
    
    /**
     * Download attachment by ID
     */
    public byte[] downloadAttachment(String attachmentId) throws Exception {
        ensureLoggedIn();
        return downloadAttachmentByRest(attachmentId);
    }
    
    /**
     * Download attachment using REST API
     */
    private byte[] downloadAttachmentByRest(String attachmentId) throws Exception {
        String url = instanceUrl + "/services/data/" + salesforceConfig.getApiVersion() + "/sobjects/Attachment/" + attachmentId + "/Body";
        
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + sessionId);
        getRequest.setHeader("Accept", "application/octet-stream");
        
        return httpClient.execute(getRequest, response -> {
            if (response.getCode() == 200) {
                return response.getEntity().getContent().readAllBytes();
            } else {
                throw new RuntimeException("HTTP " + response.getCode() + ": " + response.getReasonPhrase());
            }
        });
    }
    
    /**
     * Execute a SOQL query against Salesforce
     */
    private List<Map<String, Object>> executeQuery(String query) throws Exception {
        String encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = instanceUrl + "/services/data/" + salesforceConfig.getApiVersion() + "/query/?q=" + encodedQuery;
        
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + sessionId);
        getRequest.setHeader("Accept", "application/json");
        
        String responseBody = httpClient.execute(getRequest, response -> {
            if (response.getCode() == 200) {
                return new String(response.getEntity().getContent().readAllBytes());
            } else {
                throw new RuntimeException("Query failed with HTTP " + response.getCode() + ": " + response.getReasonPhrase());
            }
        });
        
        JsonNode jsonResponse = objectMapper.readTree(responseBody);
        JsonNode records = jsonResponse.get("records");
        
        List<Map<String, Object>> results = new ArrayList<>();
        if (records != null && records.isArray()) {
            for (JsonNode record : records) {
                @SuppressWarnings("unchecked")
                Map<String, Object> recordMap = objectMapper.convertValue(record, Map.class);
                results.add(recordMap);
            }
        }
        
        logger.info("Query returned {} records", results.size());
        return results;
    }
    
    /**
     * Ensure that we're logged in, and login if not
     */
    private void ensureLoggedIn() throws Exception {
        if (sessionId == null || instanceUrl == null) {
            login();
        }
    }
    
    /**
     * Extract metadata from Salesforce record
     */
    public Map<String, Object> extractSalesforceMetadata(Map<String, Object> record) {
        Map<String, Object> metadata = new HashMap<>();
        
        String subject = (String) record.get("WorkId_and_Subject__c");
        if (subject != null) {
            // Parse work_id, case_number, step_name, and datacenter from Subject format
            Pattern workPattern = Pattern.compile("(W-\\d+)");
            Pattern casePattern = Pattern.compile("Case[:\\s]*(\\d+)");
            Pattern stepPattern = Pattern.compile("Step[:\\s]+([^,\\s]+)");
            Pattern datacenterPattern = Pattern.compile("Host[:\\s]+([^\\s-]+-[^\\s-]+-[^\\s-]+-([^\\s-]+))");
            
            Matcher workMatcher = workPattern.matcher(subject);
            Matcher caseMatcher = casePattern.matcher(subject);
            Matcher stepMatcher = stepPattern.matcher(subject);
            Matcher datacenterMatcher = datacenterPattern.matcher(subject);
            
            if (workMatcher.find()) {
                metadata.put("work_id", workMatcher.group(1));
            }
            if (caseMatcher.find()) {
                metadata.put("case_number", Integer.parseInt(caseMatcher.group(1)));
            }
            if (stepMatcher.find()) {
                String rawStep = stepMatcher.group(1);
                String normalized = stepNameNormalizer.normalize(rawStep);
                metadata.put("step_name", normalized);
            }
            if (datacenterMatcher.find()) {
                String datacenterSuffix = datacenterMatcher.group(2);
                metadata.put("datacenter", datacenterSuffix);
            }
        }
        
        metadata.put("record_id", record.get("Id"));
        return metadata;
    }
}