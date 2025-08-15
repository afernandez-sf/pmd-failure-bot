package com.pmd_failure_bot.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmd_failure_bot.integration.salesforce.SalesforceLlmGatewayService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI Service implementation using Salesforce LLM Gateway
 */
@Service
public class SalesforceAIService implements AIService {

    private final SalesforceLlmGatewayService gatewayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SalesforceAIService(SalesforceLlmGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String generate(String prompt) throws Exception {
        return gatewayService.generateResponse(prompt);
    }

    @Override
    public FunctionCallResponse generateWithFunctions(String userMessage, List<Map<String, Object>> tools) throws Exception {
        SalesforceLlmGatewayService.FunctionCallResponse res = gatewayService.generateResponseWithFunctions(userMessage, tools);
        if (res.isFunctionCall()) {
            return FunctionCallResponse.forCall(res.getFunctionName(), res.getArguments(), res.getInvocationId());
        }
        return FunctionCallResponse.forContent(res.getContent());
    }
}