package com.pmd_failure_bot.config;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "slack")
public class SlackConfig {

    private static final Logger logger = LoggerFactory.getLogger(SlackConfig.class);

    private String botToken;
    private String appToken;
    private String signingSecret;

    @Bean
    public App slackApp() {
        if (botToken == null || botToken.trim().isEmpty()) {
            logger.error("SLACK_BOT_TOKEN is not configured. Slack integration will be disabled.");
            return null;
        }
        
        if (appToken == null || appToken.trim().isEmpty()) {
            logger.error("SLACK_APP_TOKEN is not configured. Slack integration will be disabled.");
            return null;
        }

        try {
            AppConfig appConfig = AppConfig.builder().singleTeamBotToken(botToken).signingSecret(signingSecret).build();
            App app = new App(appConfig);
            logger.info("Slack app configured successfully");
            return app;
        } catch (Exception e) {
            logger.error("Failed to configure Slack app: ", e);
            return null;
        }
    }

    @Bean
    public SocketModeApp socketModeApp(App slackApp) {
        if (appToken == null || appToken.trim().isEmpty()) {
            logger.warn("Socket mode is disabled due to missing SLACK_APP_TOKEN");
            return null;
        }

        try {
            SocketModeApp socketModeApp = new SocketModeApp(appToken, slackApp);
            
            new Thread(() -> {
                try {
                    logger.info("Starting Slack Socket Mode connection...");
                    socketModeApp.start();
                    logger.info("Slack Socket Mode connection established successfully");
                } catch (Exception e) {
                    logger.error("Failed to start Slack Socket Mode: ", e);
                }
            }).start();
            
            return socketModeApp;
            
        } catch (Exception e) {
            logger.error("Failed to create Socket Mode app: ", e);
            return null;
        }
    }
}