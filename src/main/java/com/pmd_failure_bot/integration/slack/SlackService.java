package com.pmd_failure_bot.integration.slack;

import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.service.analysis.NaturalLanguageProcessingService;
import com.pmd_failure_bot.service.query.DatabaseQueryService;
import com.pmd_failure_bot.common.constants.SlackConstants;
import com.pmd_failure_bot.common.constants.ErrorMessages;
import com.slack.api.bolt.App;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for Slack messaging and interactions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackService {

    private final NaturalLanguageProcessingService nlpService;
    private final DatabaseQueryService databaseQueryService;
    private final App slackApp;
    private final SlackImportHandler slackImportHandler;
    private final SlackReactionService slackReactionService;
    
    // Cache to prevent duplicate processing of the same message
    private final java.util.Set<String> processedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Executor to process mentions concurrently
    private final java.util.concurrent.ExecutorService mentionExecutor = java.util.concurrent.Executors.newFixedThreadPool(SlackConstants.THREAD_POOL_SIZE);


    // Initialize handlers via @PostConstruct to ensure proper bean initialization
    @jakarta.annotation.PostConstruct
    private void initialize() {
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
            String cleanedText = text != null ? text.replaceAll(SlackConstants.MENTION_PATTERN, "").trim() : "";
            mentionExecutor.submit(() -> handleAppMentionInternal(cleanedText, userId, channel, threadTs));
            return ctx.ack();
        });

        // Handle direct messages (IM) to the bot similar to mentions
        slackApp.event(MessageEvent.class, (payload, ctx) -> {
            var event = payload.getEvent();
            // Ignore edited/changed/bot messages
            if (event.getSubtype() != null && !event.getSubtype().isEmpty()) {
                return ctx.ack();
            }
            String channel = event.getChannel();
            // Only process direct messages (IM channels start with 'D')
            if (channel == null || !channel.startsWith(SlackConstants.DIRECT_MESSAGE_CHANNEL_PREFIX)) {
                return ctx.ack();
            }
            String text = event.getText();
            String userId = event.getUser();
            String threadTs = event.getTs();

            // Deduplicate per-message
            String eventKey = channel + ":" + threadTs + ":" + (text != null ? text.hashCode() : 0);
            if (!processedMessages.add(eventKey)) {
                return ctx.ack();
            }

            String cleanedText = text != null ? text.trim() : "";
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
            log.info("Received mention from user {} in channel {}: {}", userId, channel, cleanedText);
            processQueryAndRespond(cleanedText, channel, userId, threadTs);
        } catch (Exception e) {
            log.error("Error handling app mention: ", e);
            try {
                slackApp.client().chatPostMessage(r -> r.channel(channel).threadTs(threadTs).text(ErrorMessages.GENERIC_ERROR_MESSAGE));
            } catch (Exception ex) {
                log.error("Failed to send error message: ", ex);
            }
        }
    }

    /**
     * Process a query and send a response to Slack
     */
    private void processQueryAndRespond(String queryText, String channel, String userId, String threadTs) {
        try {
            // Add reaction to show we're processing
            slackReactionService.addReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
            
            // Use natural language processing to extract parameters
            NaturalLanguageProcessingService.ParameterExtractionResult extractionResult = 
                nlpService.extractParameters(queryText, null);
            
            QueryRequest queryRequest = extractionResult.getQueryRequest();
            log.info("NLP extracted parameters - Case: {}, Step: {}, Datacenter: {}, Date: {}, Intent: {}, Confidence: {} (Method: {})",
                    queryRequest.getCaseNumber(), queryRequest.getStepName(), queryRequest.getDatacenter(), queryRequest.getReportDate(),
                    extractionResult.getIntent(), extractionResult.getConfidence(), extractionResult.getExtractionMethod());
            
            String formattedResponse;
            
            if (extractionResult.isImportRequest()) {
                // Handle import request (reactions managed fully inside the background task)
                slackImportHandler.processImportRequest(queryRequest, channel, threadTs);
                return;
            } else {
                // Guardrail: block irrelevant queries early with a helpful response
                if (!extractionResult.isRelevant()) {
                    String msg = SlackConstants.IRRELEVANT_QUERY_MESSAGE + "\n*Why*: " + 
                                (extractionResult.getIrrelevantReason() != null ? extractionResult.getIrrelevantReason() : "Question outside PMD/logs scope.");
                    slackReactionService.sendMessage(channel, msg, threadTs);
                    slackReactionService.removeReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
                    slackReactionService.addReaction(channel, threadTs, SlackConstants.BLOCKED_REACTION);
                    return;
                }
                // Route by intent: metrics vs analysis; if intent is null, do not proceed
                String intent = extractionResult.getIntent();
                if (intent == null) {
                    slackReactionService.sendMessage(channel, SlackConstants.NO_INTENT_ERROR_MESSAGE, threadTs);
                    slackReactionService.removeReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
                    slackReactionService.addReaction(channel, threadTs, SlackConstants.ERROR_REACTION);
                    return;
                }
                DatabaseQueryService.DatabaseQueryResult result = databaseQueryService.processNaturalLanguageQuery(queryText, intent);
                formattedResponse = formatSlackResponseWithResult(result, extractionResult);
            }
            
            slackReactionService.sendMessage(channel, formattedResponse, threadTs);
            
            // Remove processing reaction and add completion reaction
            slackReactionService.removeReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
            slackReactionService.addReaction(channel, threadTs, SlackConstants.SUCCESS_REACTION);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid query from user {}: {}", userId, e.getMessage());
            String errorMsg = "*Error*: " + e.getMessage() + "\n\n" + SlackConstants.USAGE_EXAMPLES;
            slackReactionService.sendMessage(channel, errorMsg, threadTs);
            slackReactionService.removeReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
            slackReactionService.addReaction(channel, threadTs, SlackConstants.ERROR_REACTION);
        } catch (Exception e) {
            log.error("Error processing query from user {}: ", userId, e);
            slackReactionService.sendMessage(channel, ErrorMessages.GENERIC_ERROR_MESSAGE, threadTs);
            slackReactionService.removeReaction(channel, threadTs, SlackConstants.PROCESSING_REACTION);
            slackReactionService.addReaction(channel, threadTs, SlackConstants.ERROR_REACTION);
        }
    }


    /**
     * Format response for Slack with NLP context
     */
    private String formatSlackResponseWithResult(DatabaseQueryService.DatabaseQueryResult result,
                                           NaturalLanguageProcessingService.ParameterExtractionResult extractionResult) {
        StringBuilder sb = new StringBuilder();
        
        // Add confidence indicator only when confidence is low AND result is weak (to avoid hedging before good answers)
        boolean lowConfidence = "LLM_EXTRACTION".equals(extractionResult.getExtractionMethod()) && extractionResult.getConfidence() < SlackConstants.LOW_CONFIDENCE_THRESHOLD;
        boolean weakResult = result == null || !result.successful() || result.getResultCount() == 0;
        if (lowConfidence && weakResult) {
            sb.append("*I'm not completely sure I understood your query correctly.*\n\n");
        }
        
        // Get the natural language response from the result
        String analysisText = result != null ? result.naturalLanguageResponse() : "No response available";
        sb.append(analysisText != null ? analysisText : "No response available");

        // Add extracted parameters info for transparency only when confidence is low AND result is weak
        if (extractionResult.getConfidence() < SlackConstants.LOW_CONFIDENCE_THRESHOLD && weakResult) {
            sb.append("\n\n_I extracted these parameters: ");
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
}