package com.pmd_failure_bot.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller providing MCP API metadata endpoints like health and help
 */
@RestController
@RequestMapping("/api/mcp")
public class McpApiController {
    
    private static final Logger logger = LoggerFactory.getLogger(McpApiController.class);

    /**
     * Health check endpoint for MCP tool availability
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("MCP Natural Language Query Tool is operational");
    }
    
    /**
     * Get information about supported query patterns and examples
     */
    @GetMapping("/help")
    public ResponseEntity<MCPHelpResponse> getHelp() {
        MCPHelpResponse help = new MCPHelpResponse();
        return ResponseEntity.ok(help);
    }
    
    /**
     * Help response for the MCP tool
     */
    public static class MCPHelpResponse {
        private final String description = "Natural Language Query Tool for PMD Failure Logs";
        private final String[] supportedParameters = {
            "case_number: Support case numbers (e.g., 'case 123456')",
            "step_name: Deployment step names (e.g., 'SSH deployment', 'GridForce')",
            "hostname: Server or host names (e.g., 'server prod01', 'CS58')",
            "date: Absolute or relative dates (e.g., 'yesterday', '2024-01-15')",
            "work_id: GUS work item identifiers",
            "record_id: Salesforce record identifiers",
            "attachment_id: Salesforce attachment identifiers"
        };
        private final String[] examples = {
            "What went wrong with case 123456's deployment yesterday?",
            "Show me SSH failures from last week",
            "Why did the GridForce deployment fail on CS58?",
            "What errors occurred during yesterday's deployments?",
            "Analyze the failures for case 789012",
            "What happened with the SSH deployment to prod servers?"
        };
        private final String[] tips = {
            "Include at least one filter (case number, step name, hostname, etc.) for better results",
            "Use relative dates like 'yesterday', 'last week', or 'today' for convenience",
            "Mention specific step names or partial matches (e.g., 'SSH' matches 'SSH_TO_ALL_HOSTS')",
            "Case numbers should be numeric only",
            "Be specific about what you want to know (failures, errors, analysis, etc.)"
        };
        
        public String getDescription() { return description; }
        public String[] getSupportedParameters() { return supportedParameters; }
        public String[] getExamples() { return examples; }
        public String[] getTips() { return tips; }
    }
}