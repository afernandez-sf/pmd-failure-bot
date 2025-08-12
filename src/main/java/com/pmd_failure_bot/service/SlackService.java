package com.pmd_failure_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.controller.QueryController;
import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.service.NaturalLanguageProcessingService;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.model.event.AppMentionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class SlackService {

    private static final Logger logger = LoggerFactory.getLogger(SlackService.class);
    private final NaturalLanguageProcessingService nlpService;
    private final QueryService queryService;
    private final App slackApp;
    private final SalesforceService salesforceService;
    private final LogProcessingService logProcessingService;
    
    // Simple cache to prevent duplicate processing of the same message
    private final java.util.Set<String> processedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Value("${slack.bot.channel:pmd-slack-bot}")
    private String botChannelName;

    @Autowired
    public SlackService(NaturalLanguageProcessingService nlpService, QueryService queryService, App slackApp,
                       SalesforceService salesforceService, LogProcessingService logProcessingService) {
        this.nlpService = nlpService;
        this.queryService = queryService;
        this.slackApp = slackApp;
        this.salesforceService = salesforceService;
        this.logProcessingService = logProcessingService;
        initializeSlackHandlers();
    }

    private void initializeSlackHandlers() {
        // Handle app mentions in channels only
        slackApp.event(AppMentionEvent.class, (payload, ctx) -> {
            handleAppMention(payload.getEvent(), ctx);
            return ctx.ack();
        });
    }

    private void handleAppMention(AppMentionEvent event, EventContext ctx) {
        try {
            String text = event.getText();
            String userId = event.getUser();
            String channel = event.getChannel();
            String eventKey = channel + ":" + event.getTs() + ":" + text.hashCode();
            
            // Prevent duplicate processing
            if (processedMessages.contains(eventKey)) {
                logger.debug("Skipping duplicate message: {}", eventKey);
                return;
            }
            processedMessages.add(eventKey);
            
            logger.info("Received mention from user {} in channel {}: {}", userId, channel, text);
            
            // Remove the bot mention from the text
            String cleanedText = text.replaceAll("<@[A-Z0-9]+>", "").trim();
            
            if (cleanedText.isEmpty()) {
                return;
            }
            
            processQueryAndRespond(cleanedText, channel, ctx, userId, event.getTs());
            
        } catch (Exception e) {
            logger.error("Error handling app mention: ", e);
            try {
                ctx.client().chatPostMessage(r -> r
                    .channel(event.getChannel())
                    .threadTs(event.getTs())
                    .text("❌ Sorry, I encountered an error processing your request. Please try again later.")
                );
            } catch (Exception ex) {
                logger.error("Failed to send error message: ", ex);
            }
        }
    }

    private void processQueryAndRespond(String queryText, String channel, Object ctx, String userId, String threadTs) {
        try {
            // Add reaction to show we're processing
            addReaction(channel, threadTs, "eyes", ctx);
            
            // Use natural language processing to extract parameters
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = 
                nlpService.extractParameters(queryText, null);
            
            QueryRequest queryRequest = extractionResult.getQueryRequest();
            logger.info("NLP extracted parameters - Case: {}, Step: {}, Host: {}, Date: {}, Intent: {}, Confidence: {} (Method: {})", 
                       queryRequest.getCaseNumber(), queryRequest.getStepName(), 
                       queryRequest.getHostname(), queryRequest.getReportDate(),
                       extractionResult.getIntent(), extractionResult.getConfidence(), extractionResult.getExtractionMethod());
            
            String formattedResponse;
            
            if (extractionResult.isImportRequest()) {
                // Handle import request
                formattedResponse = processImportRequest(queryRequest, queryText, extractionResult, channel, threadTs);
            } else {
                // Handle query request
                QueryResponse response = queryService.processQuery(queryRequest);
                formattedResponse = formatSlackResponseWithNLP(response, queryText, extractionResult);
            }
            
            sendMessageInThread(channel, formattedResponse, ctx, threadTs);
            
            // Remove processing reaction and add completion reaction
            removeReaction(channel, threadTs, "eyes", ctx);
            addReaction(channel, threadTs, "white_check_mark", ctx);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid query from user {}: {}", userId, e.getMessage());
            String errorMsg = "❌ *Error*: " + e.getMessage() + "\n\n" + 
                             "*💡 Try natural language queries like:*\n" +
                             "• `What went wrong with case 123456?`\n" +
                             "• `Show me SSH failures from yesterday`\n" +
                             "• `Why did the GridForce deployment fail on CS58?`";
            sendMessageInThread(channel, errorMsg, ctx, threadTs);
            removeReaction(channel, threadTs, "eyes", ctx);
            addReaction(channel, threadTs, "x", ctx);
        } catch (Exception e) {
            logger.error("Error processing query from user {}: ", userId, e);
            sendMessageInThread(channel, "❌ Sorry, I encountered an error processing your request. Please try again later.", ctx, threadTs);
            removeReaction(channel, threadTs, "eyes", ctx);
            addReaction(channel, threadTs, "x", ctx);
        }
    }

    /**
     * Process import request from Slack
     */
    private String processImportRequest(QueryRequest queryRequest, String originalQuery, 
                                      NaturalLanguageProcessingService.ParameterExtractionResult extractionResult,
                                      String channelId, String messageTs) {
        try {
            // Create LogImportRequest from QueryRequest
            LogImportRequest importRequest = new LogImportRequest();
            importRequest.setCaseNumber(queryRequest.getCaseNumber());
            importRequest.setStepName(queryRequest.getStepName());
            
            // Validate that we have either case number or step name
            if (importRequest.getCaseNumber() == null && 
                (importRequest.getStepName() == null || importRequest.getStepName().trim().isEmpty())) {
                return "❌ *Error*: I need either a case number or step name to import logs.\n\n" +
                       "*💡 Try queries like:*\n" +
                       "• `Import logs for case 123456`\n" +
                       "• `Pull logs from SSH_TO_ALL_HOSTS step`\n" +
                       "• `Fetch GRIDFORCE_APP_LOG_COPY logs`";
            }
            
            String searchCriteria;
            if (importRequest.getCaseNumber() != null) {
                searchCriteria = "case " + importRequest.getCaseNumber();
            } else {
                searchCriteria = "step " + importRequest.getStepName();
            }
            
            // Start background processing in a separate thread
            new Thread(() -> {
                try {
                    // Change emoji to spinning arrows (processing)
                    updateReaction(channelId, messageTs, "eyes", "arrows_counterclockwise");
                    
                    // Perform the actual import
                    QueryController queryController = new QueryController(queryService, salesforceService, logProcessingService);
                    ResponseEntity<LogImportResponse> importResponseEntity = queryController.importLogs(importRequest);
                    LogImportResponse importResponse = importResponseEntity.getBody();
                    
                    // Change emoji to checkmark (completed)
                    updateReaction(channelId, messageTs, "arrows_counterclockwise", "white_check_mark");
                    
                    // Send simple completion message in thread
                    String completionMessage = String.format("Successfully imported %d logs for %s", 
                                                            importResponse.getSuccessfulLogs(), searchCriteria);
                    sendMessageInThread(channelId, completionMessage, null, messageTs);
                    
                } catch (Exception e) {
                    logger.error("Background import failed: ", e);
                    // Change emoji to X (failed)
                    updateReaction(channelId, messageTs, "arrows_counterclockwise", "x");
                    sendMessageInThread(channelId, "Import failed for " + searchCriteria + ": " + e.getMessage(), null, messageTs);
                }
            }).start();
            
            // Return empty string to avoid sending any message
            return "";
            
        } catch (Exception e) {
            logger.error("Error processing import request: ", e);
            return "❌ *Error importing logs*: " + e.getMessage() + "\n\n" +
                   "Please try again or contact support if the issue persists.";
        }
    }

    private String formatSlackResponseWithNLP(QueryResponse response, String originalQuery, 
                                              NaturalLanguageProcessingService.ParameterExtractionResult extractionResult) {
        StringBuilder sb = new StringBuilder();
        
        // Add confidence indicator if using LLM extraction
        if ("LLM_EXTRACTION".equals(extractionResult.getExtractionMethod()) && extractionResult.getConfidence() < 0.7) {
            sb.append("🤔 *I'm not completely sure I understood your query correctly.*\n\n");
        }
        
        // Extract the actual text response from the LLM JSON
        String analysisText = extractTextFromLlmResponse(response.getLlmResponse());
        sb.append(analysisText);
        
        if (!response.getReports().isEmpty()) {
            sb.append("\n\n*Related Work Items:*\n");
            for (QueryResponse.ReportInfo reportInfo : response.getReports()) {
                // Convert Salesforce record ID to GUS work item URL
                String gusUrl = "https://gus.lightning.force.com/lightning/r/ADM_Work__c/" + reportInfo.getPath() + "/view";
                
                // Use work ID as display text if available, otherwise fall back to record ID
                String displayText = reportInfo.getWorkId() != null && !reportInfo.getWorkId().equals("N/A") 
                    ? reportInfo.getWorkId() 
                    : reportInfo.getPath();
                
                // Format as Slack clickable link: <url|text>
                sb.append("• <").append(gusUrl).append("|").append(displayText).append(">\n");
            }
        }
        
        // Add extracted parameters info for transparency (if low confidence)
        if (extractionResult.getConfidence() < 0.6) {
            sb.append("\n\n_🔍 I extracted these parameters: ");
            QueryRequest params = extractionResult.getQueryRequest();
            boolean hasParams = false;
            
            if (params.getCaseNumber() != null) {
                sb.append("case ").append(params.getCaseNumber());
                hasParams = true;
            }
            if (params.getStepName() != null) {
                if (hasParams) sb.append(", ");
                sb.append("step ").append(params.getStepName());
                hasParams = true;
            }
            if (params.getHostname() != null) {
                if (hasParams) sb.append(", ");
                sb.append("host ").append(params.getHostname());
                hasParams = true;
            }
            if (params.getReportDate() != null) {
                if (hasParams) sb.append(", ");
                sb.append("date ").append(params.getReportDate());
                hasParams = true;
            }
            
            if (!hasParams) {
                sb.append("no specific filters");
            }
            sb.append("_");
        }
        
        return sb.toString();
    }
    
    // Legacy formatSlackResponse method removed - now using formatSlackResponseWithNLP
    
    private void sendMessageInThread(String channel, String message, Object ctx, String threadTs) {
        try {
            if (ctx instanceof EventContext) {
                ((EventContext) ctx).client().chatPostMessage(r -> {
                    var req = r.channel(channel).text(message);
                    if (threadTs != null) {
                        req.threadTs(threadTs);
                    }
                    return req;
                });
            } else {
                // Fallback to app client
                slackApp.client().chatPostMessage(r -> {
                    var req = r.channel(channel).text(message);
                    if (threadTs != null) {
                        req.threadTs(threadTs);
                    }
                    return req;
                });
            }
        } catch (Exception e) {
            logger.error("Failed to send threaded message to channel {}: ", channel, e);
        }
    }
    
    private void addReaction(String channel, String timestamp, String reaction, Object ctx) {
        try {
            if (ctx instanceof EventContext) {
                ((EventContext) ctx).client().reactionsAdd(r -> r
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(reaction)
                );
            } else {
                // Fallback to app client
                slackApp.client().reactionsAdd(r -> r
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(reaction)
                );
            }
        } catch (Exception e) {
            logger.debug("Failed to add reaction '{}' to message: {}", reaction, e.getMessage());
        }
    }
    
    private void removeReaction(String channel, String timestamp, String reaction, Object ctx) {
        try {
            logger.info("Removing reaction '{}' from message {} in channel {}", reaction, timestamp, channel);
            if (ctx instanceof EventContext) {
                var response = ((EventContext) ctx).client().reactionsRemove(r -> r
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(reaction)
                );
                if (response.isOk()) {
                    logger.info("Successfully removed reaction '{}'", reaction);
                } else {
                    logger.debug("Could not remove reaction '{}': {} (may not exist)", reaction, response.getError());
                }
            } else {
                // Fallback to app client
                var response = slackApp.client().reactionsRemove(r -> r
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(reaction)
                );
                if (response.isOk()) {
                    logger.info("Successfully removed reaction '{}'", reaction);
                } else {
                    logger.debug("Could not remove reaction '{}': {} (may not exist)", reaction, response.getError());
                }
            }
        } catch (Exception e) {
            logger.debug("Exception removing reaction '{}' from message: {}", reaction, e.getMessage());
        }
    }
    
    /**
     * Extract the actual text response from the LLM Gateway response
     * Note: SalesforceLlmGatewayService already extracts text from JSON, so this is plain text
     */
    private String extractTextFromLlmResponse(String llmResponse) {
        // The SalesforceLlmGatewayService already handles JSON parsing and returns plain text
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return "No response received from LLM";
        }
        return llmResponse;
    }

    private void updateReaction(String channel, String timestamp, String oldEmoji, String newEmoji) {
        try {
            // Remove the old reaction first
            removeReaction(channel, timestamp, oldEmoji, null);
            
            // Wait a bit to ensure the removal is processed
            Thread.sleep(200);
            
            // Add the new reaction
            addReaction(channel, timestamp, newEmoji, null);
            
            logger.info("Updated reaction from '{}' to '{}' on message {} in channel {}", 
                       oldEmoji, newEmoji, timestamp, channel);
        } catch (Exception e) {
            logger.error("Failed to update reaction from '{}' to '{}': ", oldEmoji, newEmoji, e);
        }
    }

    private void sendMessage(String channel, String message) {
        try {
            slackApp.client().chatPostMessage(r -> r
                .channel(channel)
                .text(message)
            );
        } catch (Exception e) {
            logger.error("Failed to send message to channel {}: ", channel, e);
        }
    }
}