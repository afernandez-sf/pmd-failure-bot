package com.pmd_failure_bot.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration placeholder
 * 
 * This configuration is prepared for future integration with Spring AI framework.
 * Currently, we use SalesforceLlmGatewayService directly for all AI operations,
 * but this provides a foundation for adopting Spring AI features like:
 * - ChatClient for conversation management
 * - Prompt templates and structured outputs
 * - Function calling capabilities
 * - Memory and context management
 * 
 * The MCP natural language processing is implemented using the existing
 * Salesforce LLM Gateway integration, which provides enterprise-grade
 * AI capabilities without requiring additional dependencies.
 */
@Configuration
public class SpringAiConfig {
    
    // Future Spring AI integration will be added here when needed
    // For now, NaturalLanguageProcessingService uses SalesforceLlmGatewayService directly
    
}