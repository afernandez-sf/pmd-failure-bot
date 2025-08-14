package com.pmd_failure_bot.infrastructure.slack;

import com.pmd_failure_bot.dto.LogImportRequest;
import com.pmd_failure_bot.dto.LogImportResponse;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.domain.analysis.NaturalLanguageProcessingService;
import com.pmd_failure_bot.domain.imports.LogImportService;
import com.pmd_failure_bot.domain.query.DatabaseQueryService;
import com.pmd_failure_bot.infrastructure.salesforce.SalesforceService;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.model.event.AppMentionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for Slack messaging and interactions
 */
@Service
public class SlackService {

    private static final Logger logger = LoggerFactory.getLogger(SlackService.class);
    private final NaturalLanguageProcessingService nlpService;
    private final DatabaseQueryService databaseQueryService;
    private final App slackApp;
    private final SalesforceService salesforceService;
    private final LogImportService logImportService;
    
    // Simple cache to prevent duplicate processing of the same message
    private final java.util.Set<String> processedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Executor to process mentions concurrently (up to ~12 users)
    private final java.util.concurrent.ExecutorService mentionExecutor = java.util.concurrent.Executors.newFixedThreadPool(12);

    @Value("${slack.bot.channel:pmd-slack-bot}")
    private String botChannelName;

    @Autowired
    public SlackService(NaturalLanguageProcessingService nlpService, DatabaseQueryService databaseQueryService, App slackApp,
                       SalesforceService salesforceService, LogImportService logImportService) {
        this.nlpService = nlpService;
        this.databaseQueryService = databaseQueryService;
        this.slackApp = slackApp;
        this.salesforceService = salesforceService;
        this.logImportService = logImportService;
        initializeSlackHandlers();
    }

    /**
     * Initialize Slack event handlers
     */
    private void initializeSlackHandlers() {
        // Handle app mentions in channels only (ACK immediately; process asynchronously)
        slackApp.event(AppMentionEvent.class, (payload, ctx) -> {
            var event = payload.getEvent();
            String text = event.getText();
            String userId = event.getUser();
            String channel = event.getChannel();
            String threadTs = event.getTs();

            // Deduplicate per-message
            String eventKey = channel + ":" + threadTs + ":" + (text != null ? text.hashCode() : 0);
            if (!processedMessages.add(eventKey)) {
                return ctx.ack();
            }

            // Clean text and schedule processing without blocking ACK
            String cleanedText = text != null ? text.replaceAll("<@[A-Z0-9]+>", "").trim() : "";
            mentionExecutor.submit(() -> handleAppMentionInternal(cleanedText, userId, channel, threadTs));
            return ctx.ack();
        });
    }

    /**
     * Internal async handler for app mention
     */
    private void handleAppMentionInternal(String cleanedText, String userId, String channel, String threadTs) {
        try {
            if (cleanedText == null || cleanedText.isBlank()) return;
            logger.info("Received mention from user {} in channel {}: {}", userId, channel, cleanedText);
            processQueryAndRespond(cleanedText, channel, null, userId, threadTs);
        } catch (Exception e) {
            logger.error("Error handling app mention: ", e);
            try {
                // Fallback to app client
                slackApp.client().chatPostMessage(r -> r
                    .channel(channel)
                    .threadTs(threadTs)
                    .text("❌ Sorry, I encountered an error processing your request. Please try again later.")
                );
            } catch (Exception ex) {
                logger.error("Failed to send error message: ", ex);
            }
        }
    }

    /**
     * Process a query and send a response to Slack
     */
    private void processQueryAndRespond(String queryText, String channel, Object ctx, String userId, String threadTs) {
        try {
            // Add reaction to show we're processing
            addReaction(channel, threadTs, "eyes", ctx);
            
            // Use natural language processing to extract parameters
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = 
                nlpService.extractParameters(queryText, null);
            
            QueryRequest queryRequest = extractionResult.getQueryRequest();
            logger.info("NLP extracted parameters - Case: {}, Step: {}, Datacenter: {}, Date: {}, Intent: {}, Confidence: {} (Method: {})", 
                       queryRequest.getCaseNumber(), queryRequest.getStepName(), 
                       queryRequest.getDatacenter(), queryRequest.getReportDate(),
                       extractionResult.getIntent(), extractionResult.getConfidence(), extractionResult.getExtractionMethod());
            
            String formattedResponse;
            
            if (extractionResult.isImportRequest()) {
                // Handle import request (reactions managed fully inside the background task)
                processImportRequest(queryRequest, queryText, extractionResult, channel, threadTs);
                return; // Do not remove eyes or add check here; background task will manage
            } else {
                // Route by intent: metrics vs analysis
                String intent = extractionResult.getIntent();
                DatabaseQueryService.DatabaseQueryResult result = databaseQueryService.processNaturalLanguageQuery(queryText, intent);
                formattedResponse = formatSlackResponseWithResult(result, queryText, extractionResult);
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
            
            // Start background processing in a separate thread (manage reactions in correct order)
            new Thread(() -> {
                try {
                    // Remove eyes, then add arrows to indicate active processing
                    removeReaction(channelId, messageTs, "eyes", null);
                    addReaction(channelId, messageTs, "arrows_counterclockwise", null);
                    
                    // Perform the actual import
                    ResponseEntity<LogImportResponse> importResponseEntity = logImportService.importLogs(importRequest);
                    LogImportResponse importResponse = importResponseEntity.getBody();
                    
                    // Swap arrows -> check when done
                    removeReaction(channelId, messageTs, "arrows_counterclockwise", null);
                    addReaction(channelId, messageTs, "white_check_mark", null);
                    
                    // Send simple completion message in thread
                    String completionMessage = String.format("Successfully imported %d logs for %s", 
                                                           importResponse.getSuccessfulLogs(), searchCriteria);
                    sendMessageInThread(channelId, completionMessage, null, messageTs);
                    
                } catch (Exception e) {
                    logger.error("Background import failed: ", e);
                    // Swap arrows -> X on failure
                    removeReaction(channelId, messageTs, "arrows_counterclockwise", null);
                    addReaction(channelId, messageTs, "x", null);
                    sendMessageInThread(channelId, "Import failed for " + searchCriteria + ": " + e.getMessage(), null, messageTs);
                }
            }).start();
            
            // We managed reactions and messaging asynchronously; return empty string
            return "";
            
        } catch (Exception e) {
            logger.error("Error processing import request: ", e);
            return "❌ *Error importing logs*: " + e.getMessage() + "\n\n" +
                   "Please try again or contact support if the issue persists.";
        }
    }

    /**
     * Format response for Slack with NLP context
     */
    private String formatSlackResponseWithResult(DatabaseQueryService.DatabaseQueryResult result, String originalQuery, 
                                           NaturalLanguageProcessingService.ParameterExtractionResult extractionResult) {
        StringBuilder sb = new StringBuilder();
        
        // Add confidence indicator if using LLM extraction
        if ("LLM_EXTRACTION".equals(extractionResult.getExtractionMethod()) && extractionResult.getConfidence() < 0.7) {
            sb.append("🤔 *I'm not completely sure I understood your query correctly.*\n\n");
        }
        
        // Get the natural language response from the result
        String analysisText = result.getNaturalLanguageResponse();
        sb.append(analysisText);
        
        // Related work items removed from Slack responses
        
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
            if (params.getDatacenter() != null) {
                if (hasParams) sb.append(", ");
                sb.append("datacenter ").append(params.getDatacenter());
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
    
    /**
     * Send a message in a thread
     */
    private void sendMessageInThread(String channel, String message, Object ctx, String threadTs) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        
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
    
    /**
     * Add a reaction to a message
     */
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
    
    /**
     * Remove a reaction from a message
     */
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
     */
    private String extractTextFromLlmResponse(String llmResponse) {
        // The SalesforceLlmGatewayService already handles JSON parsing and returns plain text
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return "No response received from LLM";
        }
        return llmResponse;
    }

    /**
     * Update a reaction on a message
     */
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

    /**
     * Send a simple message to a channel
     */
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