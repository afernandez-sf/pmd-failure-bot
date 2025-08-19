package com.pmd_failure_bot.service.imports;

import com.pmd_failure_bot.config.SalesforceConfig;
import com.pmd_failure_bot.data.entity.PmdReport;
import com.pmd_failure_bot.data.repository.PmdReportRepository;
import com.pmd_failure_bot.integration.salesforce.SalesforceService;
import com.pmd_failure_bot.service.analysis.ErrorAnalyzer;
import com.pmd_failure_bot.service.imports.extract.TarGzExtractorStrategy;
import com.pmd_failure_bot.common.util.StepNameNormalizer;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogProcessingService {
    
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final String TEMP_DIR_PREFIX = "log-processing-";
    private static final String EXTRACTED_DIR_NAME = "extracted";
    
    private final SalesforceConfig salesforceConfig;
    private final PmdReportRepository pmdReportRepository;
    private final StepNameNormalizer stepNameNormalizer;

    public ProcessingResult processAttachment(String attachmentId, String attachmentName, String recordId, String stepName, Map<String, Object> salesforceMetadata, SalesforceService salesforceService) {
        ProcessingResult result = new ProcessingResult();
        result.attachmentId = attachmentId;
        result.attachmentName = attachmentName;
        result.recordId = recordId;
        result.stepName = stepName;
        if (pmdReportRepository.existsByAttachmentId(attachmentId)) {
            result.status = STATUS_SKIPPED;
            result.message = "Attachment already processed";
            log.info("Skipping already processed attachment: {}", attachmentId);
            return result;
        }
        Path tempDir = null;
        try {
            log.info("Processing attachment: {} ({})", attachmentName, attachmentId);
            tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
            byte[] attachmentData = salesforceService.downloadAttachment(attachmentId);
            if (attachmentData.length > salesforceConfig.getMaxAttachmentSize()) {
                result.status = STATUS_FAILED;
                result.message = "Attachment too large: " + attachmentData.length + " bytes";
                return result;
            }
            Path tempFile = tempDir.resolve(sanitizeFileName(attachmentName));
            Files.write(tempFile, attachmentData);
            Path extractDir = tempDir.resolve(EXTRACTED_DIR_NAME);
            Files.createDirectories(extractDir);
            if (!extractArchive(tempFile, extractDir)) {
                result.status = STATUS_FAILED;
                result.message = "Failed to extract archive";
                return result;
            }
            List<Path> logFiles = findLogFiles(extractDir, stepName);
            if (logFiles.isEmpty()) {
                result.status = STATUS_FAILED;
                result.message = "No log files found for step: " + stepName;
                return result;
            }
            int successfulLogs = 0;
            int failedLogs = 0;
            for (Path logFile : logFiles) {
                try {
                    PmdReport report = processLogFile(logFile, attachmentId, recordId, salesforceMetadata);
                    pmdReportRepository.save(report);
                    successfulLogs++;
                    log.info("Processed log file: {}", logFile.getFileName());
                } catch (IOException e) {
                    log.error("Failed to process log file: {}", logFile.getFileName(), e);
                    failedLogs++;
                }
            }
            result.status = STATUS_SUCCESS;
            result.logsProcessed = successfulLogs;
            result.logsFailed = failedLogs;
            result.message = String.format("Processed %d logs successfully, %d failed", successfulLogs, failedLogs);
        } catch (Exception e) {
            log.error("Failed to process attachment: {}", attachmentName, e);
            result.status = STATUS_FAILED;
            result.message = "Processing error: " + e.getMessage();
        } finally {
            if (tempDir != null) {
                try { deleteDirectory(tempDir); } catch (IOException e) { log.warn("Failed to cleanup temporary directory: {}", tempDir, e); }
            }
        }
        return result;
    }

    private String sanitizeFileName(String fileName) { 
        if (fileName == null) return "unknown_file"; 
        return fileName.replaceAll("[^\\w.-]", "_"); 
    }

    private boolean extractArchive(Path archiveFile, Path extractDir) {
        String fileName = archiveFile.getFileName().toString();
        TarGzExtractorStrategy strategy = new TarGzExtractorStrategy();
        if (strategy.canHandle(fileName)) {
            try {
                return strategy.extract(archiveFile, extractDir, salesforceConfig.getMaxAttachmentSize());
            } catch (IOException e) {
                log.error("Failed to extract archive: {}", fileName, e);
                return false;
            }
        }
        log.warn("Unsupported archive type (expecting .tar.gz): {}", fileName);
        return false;
    }

    private List<Path> findLogFiles(Path directory, String stepName) throws IOException {
        List<Path> logFiles = new ArrayList<>();
        String stepNameLower = stepName.toLowerCase();
        try (var pathStream = Files.walk(directory)) {
            pathStream
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    return fileName.endsWith(".log") && fileName.startsWith(stepNameLower);
                })
                .forEach(logFiles::add);
        }
        return logFiles;
    }

    private PmdReport processLogFile(Path logFile, String attachmentId, String recordId, Map<String, Object> salesforceMetadata) throws IOException {
        PmdReport report = new PmdReport();
        List<String> lines = Files.readAllLines(logFile);
        String errorContext = ErrorAnalyzer.extractErrorContext(lines);
        if (errorContext.isEmpty()) { errorContext = String.join("\n", lines); }
        report.setContent(errorContext);
        report.setAttachmentId(attachmentId);
        report.setRecordId(recordId);
        if (salesforceMetadata.containsKey("work_id")) { report.setWorkId((String) salesforceMetadata.get("work_id")); }
        if (salesforceMetadata.containsKey("case_number")) { report.setCaseNumber((Integer) salesforceMetadata.get("case_number")); }
        if (salesforceMetadata.containsKey("step_name")) { String normalized = stepNameNormalizer.normalize((String) salesforceMetadata.get("step_name")); report.setStepName(normalized); }
        if (salesforceMetadata.containsKey("datacenter")) { report.setDatacenter((String) salesforceMetadata.get("datacenter")); }
        if (salesforceMetadata.containsKey("attachment_last_modified_date")) {
            String lastModifiedDateStr = (String) salesforceMetadata.get("attachment_last_modified_date");
            if (lastModifiedDateStr != null) {
                try { LocalDate reportDate = LocalDate.parse(lastModifiedDateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE); report.setReportDate(reportDate); }
                catch (Exception e) { log.warn("Failed to parse LastModifiedDate: {}", lastModifiedDateStr, e); }
            }
        }
        return report;
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (var pathStream = Files.walk(directory)) {
            pathStream
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        log.warn("Failed to delete file: {}", file.getAbsolutePath());
                    }
                });
        }
    }

    @AllArgsConstructor
    public static class ProcessingResult {
        public String attachmentId;
        public String attachmentName;
        public String recordId;
        public String stepName;
        public String status;
        public String message;
        public int logsProcessed;
        public int logsFailed;
        
        public ProcessingResult() {
            this.logsProcessed = 0;
            this.logsFailed = 0;
        }
    }
}


