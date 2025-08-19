package com.pmd_failure_bot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Main application configuration class
 */
@Configuration
@Import({
    SalesforceConfig.class,
    LlmGatewayConfig.class,
    SlackConfig.class
})
@ComponentScan(basePackages = {
    "com.pmd_failure_bot.web",
    "com.pmd_failure_bot.service",
    "com.pmd_failure_bot.integration",
    "com.pmd_failure_bot.data",
    "com.pmd_failure_bot.util"
})
public class ApplicationConfig {

    /**
     * Shared ObjectMapper bean for JSON processing throughout the application
     * Configured to handle Java 8 date/time types (JSR-310)
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}