package com.pmd_failure_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.context.builtin.SlashCommandContext;
import com.slack.api.bolt.request.builtin.SlashCommandRequest;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SlackService {

    private static final Logger logger = LoggerFactory.getLogger(SlackService.class);
    private final QueryService queryService;
    private final App slackApp;
    
    // Simple cache to prevent duplicate processing of the same message
    private final java.util.Set<String> processedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Value("${slack.bot.channel:pmd-slack-bot}")
    private String botChannelName;

    @Autowired
    public SlackService(QueryService queryService, App slackApp) {
        this.queryService = queryService;
        this.slackApp = slackApp;
        initializeSlackHandlers();
    }

    private void initializeSlackHandlers() {
        // Handle app mentions in channels
        slackApp.event(AppMentionEvent.class, (payload, ctx) -> {
            handleAppMention(payload.getEvent(), ctx);
            return ctx.ack();
        });
        
        // Handle direct messages
        slackApp.event(MessageEvent.class, (payload, ctx) -> {
            handleDirectMessage(payload.getEvent(), ctx);
            return ctx.ack();
        });
        
        // Handle slash command for querying
        slackApp.command("/pmd-query", (req, ctx) -> {
            handleSlashCommand(req, ctx);
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
                sendHelpMessage(channel, ctx, event.getTs());
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

    private void handleDirectMessage(MessageEvent event, EventContext ctx) {
        try {
            // Only handle DMs (channel type starts with "D")
            if (!event.getChannelType().equals("im")) {
                return;
            }
            
            // Ignore bot messages
            if (event.getBotId() != null) {
                return;
            }
            
            String text = event.getText();
            String userId = event.getUser();
            String channel = event.getChannel();
            
            logger.info("Received DM from user {}: {}", userId, text);
            
            if (text == null || text.trim().isEmpty()) {
                sendHelpMessage(channel, ctx, null); // No thread for DMs
                return;
            }
            
            processQueryAndRespond(text.trim(), channel, ctx, userId, null); // No thread for DMs
            
        } catch (Exception e) {
            logger.error("Error handling direct message: ", e);
            try {
                ctx.client().chatPostMessage(r -> r
                    .channel(event.getChannel())
                    .text("❌ Sorry, I encountered an error processing your request. Please try again later.")
                );
            } catch (Exception ex) {
                logger.error("Failed to send error message: ", ex);
            }
        }
    }

    private void handleSlashCommand(com.slack.api.bolt.request.builtin.SlashCommandRequest req, SlashCommandContext ctx) {
        String channelId = req.getPayload().getChannelId();
        try {
            String command = req.getPayload().getText();
            String userId = req.getPayload().getUserId();
            
            logger.info("Received slash command from user {}: {}", userId, command);
            
            if (command == null || command.trim().isEmpty()) {
                sendHelpMessage(channelId, ctx, null); // No thread for slash commands
                return;
            }
            
            processQueryAndRespond(command.trim(), channelId, ctx, userId, null); // No thread for slash commands
            
        } catch (Exception e) {
            logger.error("Error handling slash command: ", e);
            sendMessage(channelId, "❌ Sorry, I encountered an error processing your request. Please try again later.", ctx);
        }
    }

    private void processQueryAndRespond(String queryText, String channel, Object ctx, String userId, String threadTs) {
        try {
            // Add reaction to show we're processing
            addReaction(channel, threadTs, "eyes", ctx);
            
            // Parse the query to extract parameters
            QueryRequest queryRequest = parseQueryFromText(queryText);
            logger.info("Parsed query - Case: {}, Step: {}, Host: {}, Date: {}, Query: '{}'", 
                       queryRequest.getCaseNumber(), queryRequest.getStepName(), 
                       queryRequest.getHostname(), queryRequest.getReportDate(), queryRequest.getQuery());
            
            // Process the query
            QueryResponse response = queryService.processQuery(queryRequest);
            
            // Format and send the response
            String formattedResponse = formatSlackResponse(response, queryText);
            sendMessageInThread(channel, formattedResponse, ctx, threadTs);
            
            // Remove processing reaction and add completion reaction
            removeReaction(channel, threadTs, "eyes", ctx);
            addReaction(channel, threadTs, "white_check_mark", ctx);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid query from user {}: {}", userId, e.getMessage());
            String errorMsg = "❌ **Error**: " + e.getMessage() + "\n\n" + getUsageHelp();
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

    private QueryRequest parseQueryFromText(String text) {
        QueryRequest request = new QueryRequest();
        
        // No default values - let them be null so they don't filter the query
        
        // Extract parameters using patterns
        // Pattern for case number: case_number:123456 or case_number=123456
        Pattern casePattern = Pattern.compile("(?:case_number[:=])(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher caseMatcher = casePattern.matcher(text);
        if (caseMatcher.find()) {
            request.setCaseNumber(Integer.parseInt(caseMatcher.group(1)));
            text = text.replaceAll(casePattern.pattern(), "").trim();
        }
        
        // Pattern for step name: step:stepname or step=stepname
        Pattern stepPattern = Pattern.compile("(?:step[:=])([\\w-]+)", Pattern.CASE_INSENSITIVE);
        Matcher stepMatcher = stepPattern.matcher(text);
        if (stepMatcher.find()) {
            request.setStepName(stepMatcher.group(1));
            text = text.replaceAll(stepPattern.pattern(), "").trim();
        }
        
        // Pattern for date: date:2024-01-01 or date=2024-01-01
        Pattern datePattern = Pattern.compile("(?:date[:=])(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
        Matcher dateMatcher = datePattern.matcher(text);
        if (dateMatcher.find()) {
            try {
                request.setReportDate(LocalDate.parse(dateMatcher.group(1)));
                text = text.replaceAll(datePattern.pattern(), "").trim();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date format. Please use YYYY-MM-DD format.");
            }
        }
        
        // Note: File path field has been removed from the new schema
        
        // Pattern for hostname: host:hostname or host=hostname
        Pattern hostPattern = Pattern.compile("(?:host[:=])([^\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher hostMatcher = hostPattern.matcher(text);
        if (hostMatcher.find()) {
            request.setHostname(hostMatcher.group(1));
            text = text.replaceAll(hostPattern.pattern(), "").trim();
        }
        
        // The remaining text is the query
        if (text.trim().isEmpty()) {
            throw new IllegalArgumentException("Please provide a query. Example: 'What deployment issues occurred today?'");
        }
        
        request.setQuery(text.trim());
        return request;
    }

    private String formatSlackResponse(QueryResponse response, String originalQuery) {
        StringBuilder sb = new StringBuilder();
        
        // Extract the actual text response from the LLM JSON
        String analysisText = extractTextFromLlmResponse(response.getLlmResponse());
        sb.append(analysisText);
        
        if (!response.getReports().isEmpty()) {
            sb.append("\n\n*Related Work Items:*\n");
            for (QueryResponse.ReportInfo reportInfo : response.getReports()) {
                // Convert Salesforce record ID to GUS work item URL
                String gusUrl = "https://gus.lightning.force.com/lightning/r/ADM_Work__c/" + reportInfo.getPath() + "/view";
                sb.append("• ").append(gusUrl).append("\n");
            }
        }
        
        return sb.toString();
    }

    private void sendHelpMessage(String channel, Object ctx, String threadTs) {
        String helpMessage = "👋 **PMD Failure Bot Help**\n\n" + getUsageHelp();
        sendMessageInThread(channel, helpMessage, ctx, threadTs);
    }

    private String getUsageHelp() {
        return """
                **How to use the PMD Failure Bot:**
                
                **Basic Query:**
                `What deployment issues occurred today?`
                
                **Query with Parameters:**
                `step:my-step date:2024-01-15 What errors happened during this deployment?`
                
                **Available Parameters:**
                • `step:stepname` - Filter by step name
                • `date:YYYY-MM-DD` - Filter by report date
                • `file:path/to/file` - Filter by file path
                • `host:hostname` - Filter by hostname
                
                **Examples:**
                • `@pmd-bot What deployment failures occurred today?`
                • `/pmd-query step:deploy-prod date:2024-01-15 Show me all errors`
                • DM: `What issues happened on host:server01?`
                
                **Note**: You can mention me in the #pmd-slack-bot channel, send me a DM, or use the `/pmd-query` slash command.
                """;
    }



    private void sendMessage(String channel, String message, Object ctx) {
        try {
            if (ctx instanceof EventContext) {
                ((EventContext) ctx).client().chatPostMessage(r -> r
                    .channel(channel)
                    .text(message)
                );
            } else if (ctx instanceof SlashCommandContext) {
                ((SlashCommandContext) ctx).client().chatPostMessage(r -> r
                    .channel(channel)
                    .text(message)
                );
            } else {
                // Fallback to app client
                slackApp.client().chatPostMessage(r -> r
                    .channel(channel)
                    .text(message)
                );
            }
        } catch (Exception e) {
            logger.error("Failed to send message to channel {}: ", channel, e);
        }
    }
    
    private void sendMessageInThread(String channel, String message, Object ctx, String threadTs) {
        try {
            if (ctx instanceof EventContext) {
                var builder = ((EventContext) ctx).client().chatPostMessage(r -> {
                    var req = r.channel(channel).text(message);
                    if (threadTs != null) {
                        req.threadTs(threadTs);
                    }
                    return req;
                });
            } else if (ctx instanceof SlashCommandContext) {
                var builder = ((SlashCommandContext) ctx).client().chatPostMessage(r -> {
                    var req = r.channel(channel).text(message);
                    if (threadTs != null) {
                        req.threadTs(threadTs);
                    }
                    return req;
                });
            } else {
                // Fallback to app client
                var builder = slackApp.client().chatPostMessage(r -> {
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
            } else if (ctx instanceof SlashCommandContext) {
                ((SlashCommandContext) ctx).client().reactionsAdd(r -> r
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
            } else if (ctx instanceof SlashCommandContext) {
                var response = ((SlashCommandContext) ctx).client().reactionsRemove(r -> r
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
     * Extract the actual text response from the LLM Gateway JSON response
     */
    private String extractTextFromLlmResponse(String llmResponseJson) {
        try {
            // Parse the JSON response to extract the text
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(llmResponseJson);
            JsonNode generations = rootNode.get("generations");
            
            if (generations != null && generations.isArray() && generations.size() > 0) {
                JsonNode firstGeneration = generations.get(0);
                JsonNode textNode = firstGeneration.get("text");
                if (textNode != null) {
                    return textNode.asText();
                }
            }
            
            // Fallback if we can't parse the response
            return "Unable to parse response from LLM";
            
        } catch (Exception e) {
            logger.error("Failed to parse LLM response JSON: ", e);
            return "Error parsing LLM response: " + e.getMessage();
        }
    }
}