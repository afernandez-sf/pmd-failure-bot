package com.pmd_failure_bot.web.controller;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ApiController {
    
    private static final String HEALTH_MESSAGE = "Natural Language Query Tool is operational";
    private static final String API_HEALTH_PATH = "/health";
    private static final String API_HELP_PATH = "/help";
    private static final HelpResponse HELP_RESPONSE = new HelpResponse();

    @GetMapping(API_HEALTH_PATH)
    public ResponseEntity<String> health() {
        log.debug("Health check requested");
        return ResponseEntity.ok(HEALTH_MESSAGE);
    }

    @GetMapping(API_HELP_PATH)
    public ResponseEntity<HelpResponse> getHelp() {
        log.debug("Help documentation requested");
        return ResponseEntity.ok(HELP_RESPONSE);
    }

    @Getter
    public static class HelpResponse {
        private final String description = "Natural Language Query Tool for PMD Failure Logs";
        private final List<String> supportedParameters = List.of(
            "case_number: Support case numbers (e.g., 'case 123456')",
            "step_name: Deployment step names (e.g., 'SSH deployment', 'GridForce')",
            "datacenter: Datacenter suffix (e.g., 'am3', 'cs58')",
            "date: Absolute or relative dates (e.g., 'yesterday', '2024-01-15')",
            "work_id: GUS work item identifiers",
            "record_id: Salesforce record identifiers",
            "attachment_id: Salesforce attachment identifiers"
        );
        private final List<String> examples = List.of(
            "What went wrong with case 123456's deployment yesterday?",
            "Show me SSH_TO_ALL_HOSTS failures from last week",
            "Why did the GridForce deployment fail on CS58?",
            "What errors occurred during yesterday's deployments?",
            "Analyze the failures for case 789012"
        );
        private final List<String> tips = List.of(
            "Include at least one filter (case number, step name, datacenter, etc.) for better results",
            "Use relative dates like 'yesterday', 'last week', or 'today' for convenience",
            "Mention specific step names (e.g., 'SSH_TO_ALL_HOSTS')",
            "Case numbers should be numeric only",
            "Be specific about what you want to know (failures, errors, analysis, etc.)"
        );
    }
}


