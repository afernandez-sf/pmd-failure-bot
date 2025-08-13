package com.pmd_failure_bot.infrastructure.salesforce;

import com.pmd_failure_bot.config.SalesforceConfig;
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
    private final CloseableHttpClient httpClient;
    
    private String sessionId;
    private String instanceUrl;
    
    
    @Autowired
    public SalesforceService(SalesforceConfig salesforceConfig) {
        this.salesforceConfig = salesforceConfig;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClients.createDefault();
    }
    
    /**
     * Login to Salesforce and obtain session ID and instance URL
     */
    public void login() throws Exception {
        salesforceConfig.validate();
        
        logger.info("Logging into Salesforce...");
        
        HttpPost loginPost = new HttpPost(salesforceConfig.getLoginUrl() + "/services/Soap/u/" + 
                                         salesforceConfig.getApiVersion().replace("v", ""));
        
        String soapBody = String.format("""
            <?xml version="1.0" encoding="utf-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" 
                             xmlns:urn="urn:partner.soap.sforce.com">
                <soapenv:Header/>
                <soapenv:Body>
                    <urn:login>
                        <urn:username>%s</urn:username>
                        <urn:password>%s</urn:password>
                    </urn:login>
                </soapenv:Body>
            </soapenv:Envelope>
            """, salesforceConfig.getUsername(), salesforceConfig.getPasswordWithToken());
        
        loginPost.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(soapBody, 
                            org.apache.hc.core5.http.ContentType.create("text/xml", "UTF-8")));
        loginPost.setHeader("SOAPAction", "login");
        
        String response = httpClient.execute(loginPost, httpResponse -> {
            return new String(httpResponse.getEntity().getContent().readAllBytes());
        });
        
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
			WHERE Subject__c LIKE 'Step: %s%%Status: FAILED%%'
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
			WHERE WorkId_and_Subject__c LIKE '%%Status: FAILED%%Case: %d%%'
			""", caseNumber);

        logger.info("Querying failed attachments for case: {}", caseNumber);
        return executeQuery(query);
    }
    
    /**
     * Download attachment by ID
     */
    public byte[] downloadAttachment(String attachmentId) throws Exception {
        ensureLoggedIn();
        
        // Always use REST blob retrieval for attachments
        return downloadAttachmentByRest(attachmentId);
    }
    
    
    
    /**
     * Download attachment using REST API
     */
    private byte[] downloadAttachmentByRest(String attachmentId) throws Exception {
        String url = instanceUrl + "/services/data/" + salesforceConfig.getApiVersion() + 
                    "/sobjects/Attachment/" + attachmentId + "/Body";
        
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + sessionId);
        getRequest.setHeader("Accept", "application/octet-stream");
        
        return httpClient.execute(getRequest, response -> {
            if (response.getCode() == 200) {
                return response.getEntity().getContent().readAllBytes();
            } else {
                throw new RuntimeException("HTTP " + response.getCode() + ": " + 
                                         response.getReasonPhrase());
            }
        });
    }
    
    /**
     * Execute a SOQL query against Salesforce
     */
    private List<Map<String, Object>> executeQuery(String query) throws Exception {
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String url = instanceUrl + "/services/data/" + salesforceConfig.getApiVersion() + 
                    "/query/?q=" + encodedQuery;
        
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + sessionId);
        getRequest.setHeader("Accept", "application/json");
        
        String responseBody = httpClient.execute(getRequest, response -> {
            if (response.getCode() == 200) {
                return new String(response.getEntity().getContent().readAllBytes());
            } else {
                throw new RuntimeException("Query failed with HTTP " + response.getCode() + ": " + 
                                         response.getReasonPhrase());
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
            // Parse work_id, case_number, step_name, and hostname from Subject format
            // Example: "W-15540393: Step: SSH_TO_ALL_HOSTS_EU49_DR, Status: FAILED, Case: 58989945 - [PMD/IR@2024-Apr-20 PST]: Maintenance tasks for target EU49 EU51 NA241, Host: ops0-release1-2-am3 -- Caffeine task log attached"
            Pattern workPattern = Pattern.compile("(W-\\d+)");
            Pattern casePattern = Pattern.compile("Case[:\\s]*(\\d+)");
            Pattern stepPattern = Pattern.compile("Step[:\\s]+([^,\\s]+)");
            Pattern hostPattern = Pattern.compile("Host[:\\s]+([^\\s-]+-[^\\s-]+-[^\\s-]+-([^\\s-]+))");
            
            Matcher workMatcher = workPattern.matcher(subject);
            Matcher caseMatcher = casePattern.matcher(subject);
            Matcher stepMatcher = stepPattern.matcher(subject);
            Matcher hostMatcher = hostPattern.matcher(subject);
            
            if (workMatcher.find()) {
                metadata.put("work_id", workMatcher.group(1)); // Store full W-XXXXXX format
            }
            if (caseMatcher.find()) {
                metadata.put("case_number", Integer.parseInt(caseMatcher.group(1)));
            }
            if (stepMatcher.find()) {
                metadata.put("step_name", stepMatcher.group(1)); // Store step name exactly as shown
            }
            if (hostMatcher.find()) {
                String hostnameSuffix = hostMatcher.group(2); // Extract just the suffix (e.g., "am3")
                metadata.put("hostname", hostnameSuffix);
            }
        }
        
        metadata.put("record_id", record.get("Id"));
        return metadata;
    }
}