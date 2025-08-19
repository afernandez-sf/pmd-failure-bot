package com.pmd_failure_bot.integration.slack;

import com.slack.api.bolt.App;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for Slack reactions and message sending
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackReactionService {

    private final App slackApp;

    /**
     * Send a message in a thread
     */
    protected void sendMessage(String channel, String message, String threadTs) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        
        try {
            slackApp.client().chatPostMessage(r -> {
                var req = r.channel(channel).text(message);
                if (threadTs != null) {
                    req.threadTs(threadTs);
                }
                return req;
            });
        } catch (Exception e) {
            log.error("Failed to send threaded message to channel {}: ", channel, e);
        }
    }
    
    /**
     * Add a reaction to a message
     */
    protected void addReaction(String channel, String timestamp, String reaction) {
        try {
            slackApp.client().reactionsAdd(r -> r.channel(channel).timestamp(timestamp).name(reaction));
        } catch (Exception e) {
            log.debug("Failed to add reaction '{}' to message: {}", reaction, e.getMessage());
        }
    }
    
    /**
     * Remove a reaction from a message
     */
    protected void removeReaction(String channel, String timestamp, String reaction) {
        try {
            log.info("Removing reaction '{}' from message {} in channel {}", reaction, timestamp, channel);
            var response = slackApp.client().reactionsRemove(r -> r.channel(channel).timestamp(timestamp).name(reaction));
            if (response.isOk()) {
                log.info("Successfully removed reaction '{}'", reaction);
            } else {
                log.debug("Could not remove reaction '{}': {} (may not exist)", reaction, response.getError());
            }
        } catch (Exception e) {
            log.debug("Exception removing reaction '{}' from message: {}", reaction, e.getMessage());
        }
    }
}