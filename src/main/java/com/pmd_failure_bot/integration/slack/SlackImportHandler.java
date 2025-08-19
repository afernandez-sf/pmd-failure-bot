package com.pmd_failure_bot.integration.slack;

import com.pmd_failure_bot.web.dto.request.LogImportRequest;
import com.pmd_failure_bot.web.dto.response.LogImportResponse;
import com.pmd_failure_bot.web.dto.request.QueryRequest;
import com.pmd_failure_bot.service.imports.LogImportService;
import com.pmd_failure_bot.common.constants.SlackConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Handles Slack import requests separately from general message processing
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlackImportHandler {


    private final LogImportService logImportService;
    private final SlackReactionService slackReactionService;

    /**
     * Process import request from Slack in the background
     */
    protected void processImportRequest(QueryRequest queryRequest, String channelId, String messageTs) {
        try {
            // Create LogImportRequest from QueryRequest
            LogImportRequest importRequest = new LogImportRequest();
            importRequest.setCaseNumber(queryRequest.getCaseNumber());
            importRequest.setStepName(queryRequest.getStepName());
            
            // Validate that we have either case number or step name
            if (importRequest.getCaseNumber() == null && (importRequest.getStepName() == null || importRequest.getStepName().trim().isEmpty())) {
                return;
            }
            
            String searchCriteria = buildSearchCriteria(importRequest);
            
            // Start background processing in a separate thread
            new Thread(() -> {
                try {
                    handleImportInBackground(importRequest, searchCriteria, channelId, messageTs);
                } catch (Exception e) {
                    log.error("Background import failed: ", e);
                    handleImportError(searchCriteria, channelId, messageTs, e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            log.error("Error processing import request: ", e);
        }
    }

    private String buildSearchCriteria(LogImportRequest importRequest) {
        if (importRequest.getCaseNumber() != null) {
            return "case " + importRequest.getCaseNumber();
        } else {
            return "step " + importRequest.getStepName();
        }
    }

    private void handleImportInBackground(LogImportRequest importRequest, String searchCriteria, String channelId, String messageTs) {
        // Remove eyes, then add arrows to indicate active processing
        slackReactionService.removeReaction(channelId, messageTs, SlackConstants.PROCESSING_REACTION);
        slackReactionService.addReaction(channelId, messageTs, SlackConstants.IMPORT_PROCESSING_REACTION);
        
        // Perform the actual import
        ResponseEntity<LogImportResponse> importResponseEntity = logImportService.importLogs(importRequest);
        LogImportResponse importResponse = importResponseEntity.getBody();
        
        // Remove arrows first
        slackReactionService.removeReaction(channelId, messageTs, SlackConstants.IMPORT_PROCESSING_REACTION);
        
        // Analyze the import result and react appropriately
        handleImportResult(importResponse, searchCriteria, channelId, messageTs);
    }

    private void handleImportResult(LogImportResponse importResponse, String searchCriteria, String channelId, String messageTs) {
        if (importResponse == null) {
            // Null response indicates a serious error like invalid login
            slackReactionService.addReaction(channelId, messageTs, SlackConstants.ERROR_REACTION);
            slackReactionService.sendMessage(channelId, String.format("Import failed for %s: No response from import service", searchCriteria), messageTs);
        } else if (importResponse.getFailedLogs() > 0) {
            // Some logs failed to process
            slackReactionService.addReaction(channelId, messageTs, SlackConstants.ERROR_REACTION);
            String errorMessage = String.format("Import completed for %s with errors: %d logs imported, %d failed", 
                                               searchCriteria, importResponse.getSuccessfulLogs(), importResponse.getFailedLogs());
            slackReactionService.sendMessage(channelId, errorMessage, messageTs);
        } else if (importResponse.getSuccessfulLogs() > 0) {
            // Logs were imported successfully
            slackReactionService.addReaction(channelId, messageTs, SlackConstants.SUCCESS_REACTION);
            String completionMessage = String.format("Successfully imported %d logs for %s", 
                                                   importResponse.getSuccessfulLogs(), searchCriteria);
            slackReactionService.sendMessage(channelId, completionMessage, messageTs);
        } else if (importResponse.getSkippedAttachments() > 0) {
            // No new logs, but attachments were processed
            slackReactionService.addReaction(channelId, messageTs, SlackConstants.SUCCESS_REACTION);
            String completionMessage = String.format("Import completed for %s: %d attachments were already processed (no new logs)", 
                                                   searchCriteria, importResponse.getSkippedAttachments());
            slackReactionService.sendMessage(channelId, completionMessage, messageTs);
        } else {
            // No logs found at all - could be case/step doesn't exist or no failure attachments
            slackReactionService.addReaction(channelId, messageTs, "warning");
            String warningMessage = String.format("No failure logs found for %s. This could mean:\n• Case/step doesn't exist\n• No failure attachments available\n• All logs already imported", searchCriteria);
            slackReactionService.sendMessage(channelId, warningMessage, messageTs);
        }
    }

    private void handleImportError(String searchCriteria, String channelId, String messageTs, String errorMessage) {
        // Swap arrows -> X on failure
        slackReactionService.removeReaction(channelId, messageTs, SlackConstants.IMPORT_PROCESSING_REACTION);
        slackReactionService.addReaction(channelId, messageTs, SlackConstants.ERROR_REACTION);
        slackReactionService.sendMessage(channelId, "Import failed for " + searchCriteria + ": " + errorMessage, messageTs);
    }
}