package com.pmd_failure_bot.service;

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
            
            logger.info("Received mention from user {} in channel {}: {}", userId, channel, text);
            
            // Remove the bot mention from the text
            String cleanedText = text.replaceAll("<@[A-Z0-9]+>", "").trim();
            
            if (cleanedText.isEmpty()) {
                sendHelpMessage(channel, ctx);
                return;
            }
            
            processQueryAndRespond(cleanedText, channel, ctx, userId);
            
        } catch (Exception e) {
            logger.error("Error handling app mention: ", e);
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
                sendHelpMessage(channel, ctx);
                return;
            }
            
            processQueryAndRespond(text.trim(), channel, ctx, userId);
            
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
                sendHelpMessage(channelId, ctx);
                return;
            }
            
            processQueryAndRespond(command.trim(), channelId, ctx, userId);
            
        } catch (Exception e) {
            logger.error("Error handling slash command: ", e);
            sendMessage(channelId, "❌ Sorry, I encountered an error processing your request. Please try again later.", ctx);
        }
    }

    private void processQueryAndRespond(String queryText, String channel, Object ctx, String userId) {
        try {
            // Send typing indicator
            sendTypingIndicator(channel);
            
            // Parse the query to extract parameters
            QueryRequest queryRequest = parseQueryFromText(queryText);
            
            // Process the query
            QueryResponse response = queryService.processQuery(queryRequest);
            
            // Format and send the response
            String formattedResponse = formatSlackResponse(response, queryText);
            sendMessage(channel, formattedResponse, ctx);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid query from user {}: {}", userId, e.getMessage());
            String errorMsg = "❌ **Error**: " + e.getMessage() + "\n\n" + getUsageHelp();
            sendMessage(channel, errorMsg, ctx);
        } catch (Exception e) {
            logger.error("Error processing query from user {}: ", userId, e);
            sendMessage(channel, "❌ Sorry, I encountered an error processing your request. Please try again later.", ctx);
        }
    }

    private QueryRequest parseQueryFromText(String text) {
        QueryRequest request = new QueryRequest();
        
        // Default values
        request.setStepName("default"); // Set a default step name
        
        // Extract parameters using patterns
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
        
        sb.append("🤖 **PMD Analysis Results**\n\n");
        sb.append("📝 **Query**: ").append(originalQuery).append("\n");
        sb.append("⏱️ **Execution Time**: ").append(response.getExecutionTimeMs()).append("ms\n");
        sb.append("📊 **Reports Analyzed**: ").append(response.getReports().size()).append("\n");
        sb.append("🕐 **Analyzed At**: ").append(response.getExecutedAt()).append("\n\n");
        
        sb.append("📋 **Analysis**:\n");
        sb.append(response.getLlmResponse());
        
        if (!response.getReports().isEmpty()) {
            sb.append("\n\n📁 **Report Files**:\n");
            for (QueryResponse.ReportInfo reportInfo : response.getReports()) {
                sb.append("• ").append(reportInfo.getPath()).append("\n");
            }
        }
        
        return sb.toString();
    }

    private void sendHelpMessage(String channel, Object ctx) {
        String helpMessage = "👋 **PMD Failure Bot Help**\n\n" + getUsageHelp();
        sendMessage(channel, helpMessage, ctx);
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

    private void sendTypingIndicator(String channel) {
        try {
            slackApp.client().chatPostMessage(r -> r
                .channel(channel)
                .text("🔍 Analyzing PMD reports...")
            );
        } catch (Exception e) {
            logger.debug("Failed to send typing indicator: ", e);
        }
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
}