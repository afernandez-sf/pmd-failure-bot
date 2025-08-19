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

import jakarta.annotation.PostConstruct;

@Data
@Configuration
@ConfigurationProperties(prefix = "slack")
public class SlackConfig {

    private static final Logger logger = LoggerFactory.getLogger(SlackConfig.class);

    private String botToken;
    private String appToken; 
    private String signingSecret;

    @PostConstruct
    public void validateConfiguration() {
        if (botToken == null || botToken.trim().isEmpty()) {
            throw new IllegalStateException("SLACK_BOT_TOKEN is required but not configured");
        }
        if (appToken == null || appToken.trim().isEmpty()) {
            throw new IllegalStateException("SLACK_APP_TOKEN is required but not configured");
        }
        if (signingSecret == null || signingSecret.trim().isEmpty()) {
            logger.warn("SLACK_SIGNING_SECRET is not configured - this may cause signature verification issues");
        }
        logger.info("Slack configuration validated successfully");
    }

    @Bean
    public App slackApp() {
        try {
            AppConfig appConfig = AppConfig.builder().singleTeamBotToken(botToken).signingSecret(signingSecret).build();
            App app = new App(appConfig);
            logger.info("Slack app configured successfully");
            return app;
        } catch (Exception e) {
            logger.error("Failed to configure Slack app: ", e);
            throw new IllegalStateException("Failed to initialize Slack app", e);
        }
    }

    @Bean
    public SocketModeApp socketModeApp(App slackApp) {
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
            throw new IllegalStateException("Failed to initialize Slack Socket Mode", e);
        }
    }
}