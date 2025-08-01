package com.pmd_failure_bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.pmd_failure_bot.repository")
public class PmdFailureBotApplication {

	private static final Logger logger = LoggerFactory.getLogger(PmdFailureBotApplication.class);

	public static void main(String[] args) {
		logger.info("Starting PMD Failure Bot with Slack integration...");
		
		// Check for required Slack environment variables
		String botToken = System.getenv("SLACK_BOT_TOKEN");
		String appToken = System.getenv("SLACK_APP_TOKEN");
		
		if (botToken == null || botToken.trim().isEmpty()) {
			logger.warn("SLACK_BOT_TOKEN environment variable is not set. Slack integration will be disabled.");
		}
		
		if (appToken == null || appToken.trim().isEmpty()) {
			logger.warn("SLACK_APP_TOKEN environment variable is not set. Socket mode will be disabled.");
		}
		
		if (botToken != null && appToken != null && !botToken.trim().isEmpty() && !appToken.trim().isEmpty()) {
			logger.info("Slack tokens detected. Slack integration will be enabled.");
		}
		
		SpringApplication.run(PmdFailureBotApplication.class, args);
		
		logger.info("PMD Failure Bot started successfully. Ready to receive Slack messages!");
	}

}
