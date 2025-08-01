package com.pmd_failure_bot.config;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackConfig {

    private static final Logger logger = LoggerFactory.getLogger(SlackConfig.class);

    @Value("${slack.bot.token}")
    private String botToken;

    @Value("${slack.app.token}")
    private String appToken;

    @Value("${slack.signing.secret}")
    private String signingSecret;

    @Bean
    public App slackApp() {
        // Validate configuration
        if (botToken == null || botToken.trim().isEmpty()) {
            logger.warn("SLACK_BOT_TOKEN is not configured. Slack integration will be disabled.");
            return createDummyApp();
        }
        
        if (appToken == null || appToken.trim().isEmpty()) {
            logger.warn("SLACK_APP_TOKEN is not configured. Socket mode will be disabled.");
            return createDummyApp();
        }

        try {
            // Create app configuration
            AppConfig appConfig = AppConfig.builder()
                    .singleTeamBotToken(botToken)
                    .signingSecret(signingSecret)
                    .build();

            // Create Slack app
            App app = new App(appConfig);
            
            logger.info("Slack app configured successfully");
            return app;
            
        } catch (Exception e) {
            logger.error("Failed to configure Slack app: ", e);
            return createDummyApp();
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
            
            // Start the socket mode app in a separate thread
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

    private App createDummyApp() {
        // Create a dummy app for cases where Slack is not properly configured
        try {
            AppConfig appConfig = AppConfig.builder()
                    .singleTeamBotToken("dummy-token")
                    .signingSecret("dummy-secret")
                    .build();
            return new App(appConfig);
        } catch (Exception e) {
            logger.error("Failed to create dummy app: ", e);
            return null;
        }
    }
}