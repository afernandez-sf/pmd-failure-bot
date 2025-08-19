package com.pmd_failure_bot;

import com.pmd_failure_bot.config.ApplicationConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Main application class for PMD Failure Bot
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.pmd_failure_bot.data.repository")
@Import(ApplicationConfig.class)
@Slf4j
public class PmdFailureBotApplication {

	private static final String SLACK_BOT_TOKEN = "SLACK_BOT_TOKEN";
	private static final String SLACK_APP_TOKEN = "SLACK_APP_TOKEN";

	public static void main(String[] args) {
		log.info("Starting PMD Failure Bot with Slack integration...");
		
		validateSlackTokens();
		
		SpringApplication.run(PmdFailureBotApplication.class, args);
		
		log.info("PMD Failure Bot started successfully. Ready to receive requests!");
	}

	private static void validateSlackTokens() {
		String botToken = System.getenv(SLACK_BOT_TOKEN);
		String appToken = System.getenv(SLACK_APP_TOKEN);
		
		if (isTokenMissing(botToken)) {
			log.warn("{} environment variable is not set. Slack integration will be disabled.", SLACK_BOT_TOKEN);
		}
		
		if (isTokenMissing(appToken)) {
			log.warn("{} environment variable is not set. Socket mode will be disabled.", SLACK_APP_TOKEN);
		}
		
		if (areTokensValid(botToken, appToken)) {
			log.info("Slack tokens detected. Slack integration will be enabled.");
		}
	}

	private static boolean isTokenMissing(String token) {
		return token == null || token.trim().isEmpty();
	}

	private static boolean areTokensValid(String botToken, String appToken) {
		return !isTokenMissing(botToken) && !isTokenMissing(appToken);
	}
}