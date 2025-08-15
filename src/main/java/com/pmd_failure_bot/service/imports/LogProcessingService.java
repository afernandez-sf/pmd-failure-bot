package com.pmd_failure_bot.service.imports;

import com.pmd_failure_bot.config.SalesforceConfig;
import com.pmd_failure_bot.data.entity.PmdReport;
import com.pmd_failure_bot.data.repository.PmdReportRepository;
import com.pmd_failure_bot.integration.salesforce.SalesforceService;
import com.pmd_failure_bot.service.analysis.ErrorAnalyzer;
import com.pmd_failure_bot.service.imports.extract.TarGzExtractorStrategy;
import com.pmd_failure_bot.util.StepNameNormalizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class LogProcessingService {
    private static final Logger logger = LoggerFactory.getLogger(LogProcessingService.class);
    private final SalesforceConfig salesforceConfig;
    private final PmdReportRepository pmdReportRepository;
    private final StepNameNormalizer stepNameNormalizer;

    @Autowired
    public LogProcessingService(SalesforceConfig salesforceConfig, PmdReportRepository pmdReportRepository, StepNameNormalizer stepNameNormalizer) {
        this.salesforceConfig = salesforceConfig;
        this.pmdReportRepository = pmdReportRepository;
        this.stepNameNormalizer = stepNameNormalizer;
    }

    public ProcessingResult processAttachment(String attachmentId, String attachmentName,
                                             String recordId, String stepName,
                                             Map<String, Object> salesforceMetadata,
                                             SalesforceService salesforceService) {
        ProcessingResult result = new ProcessingResult();
        result.attachmentId = attachmentId;
        result.attachmentName = attachmentName;
        result.recordId = recordId;
        result.stepName = stepName;
        if (pmdReportRepository.existsByAttachmentId(attachmentId)) {
            result.status = "SKIPPED";
            result.message = "Attachment already processed";
            logger.info("Skipping already processed attachment: {}", attachmentId);
            return result;
        }
        Path tempDir = null;
        try {
            logger.info("Processing attachment: {} ({})", attachmentName, attachmentId);
            tempDir = Files.createTempDirectory("log-processing-");
            byte[] attachmentData = salesforceService.downloadAttachment(attachmentId);
            if (attachmentData.length > salesforceConfig.getMaxAttachmentSize()) {
                result.status = "FAILED";
                result.message = "Attachment too large: " + attachmentData.length + " bytes";
                return result;
            }
            Path tempFile = tempDir.resolve(sanitizeFileName(attachmentName));
            Files.write(tempFile, attachmentData);
            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);
            if (!extractArchive(tempFile, extractDir)) {
                result.status = "FAILED";
                result.message = "Failed to extract archive";
                return result;
            }
            List<Path> logFiles = findLogFiles(extractDir, stepName);
            if (logFiles.isEmpty()) {
                result.status = "FAILED";
                result.message = "No log files found for step: " + stepName;
                return result;
            }
            int successfulLogs = 0;
            int failedLogs = 0;
            for (Path logFile : logFiles) {
                try {
                    PmdReport report = processLogFile(logFile, attachmentId, recordId, salesforceMetadata);
                    if (report != null) {
                        if (pmdReportRepository.existsByAttachmentId(report.getAttachmentId())) {
                            logger.info("Attachment {} already exists in DB; skipping save.", report.getAttachmentId());
                            result.status = "SKIPPED";
                        } else {
                            pmdReportRepository.save(report);
                        }
                        successfulLogs++;
                        logger.info("Processed log file: {}", logFile.getFileName());
                    } else {
                        failedLogs++;
                    }
                } catch (Exception e) {
                    logger.error("Failed to process log file: {}", logFile.getFileName(), e);
                    failedLogs++;
                }
            }
            result.status = "SUCCESS";
            result.logsProcessed = successfulLogs;
            result.logsFailed = failedLogs;
            result.message = String.format("Processed %d logs successfully, %d failed", successfulLogs, failedLogs);
        } catch (Exception e) {
            logger.error("Failed to process attachment: {}", attachmentName, e);
            result.status = "FAILED";
            result.message = "Processing error: " + e.getMessage();
        } finally {
            if (tempDir != null) {
                try { deleteDirectory(tempDir); } catch (IOException e) { logger.warn("Failed to cleanup temporary directory: {}", tempDir, e); }
            }
        }
        return result;
    }

    private String sanitizeFileName(String fileName) { if (fileName == null) return "unknown_file"; return fileName.replaceAll("[^\\w\\.\\-]", "_"); }

    private boolean extractArchive(Path archiveFile, Path extractDir) {
        String fileName = archiveFile.getFileName().toString();
        TarGzExtractorStrategy strategy = new TarGzExtractorStrategy();
        if (strategy.canHandle(fileName)) {
            try {
                return strategy.extract(archiveFile, extractDir, salesforceConfig.getMaxAttachmentSize());
            } catch (IOException e) {
                logger.error("Failed to extract archive: {}", fileName, e);
                return false;
            }
        }
        logger.warn("Unsupported archive type (expecting .tar.gz): {}", fileName);
        return false;
    }

    private List<Path> findLogFiles(Path directory, String stepName) throws IOException {
        List<Path> logFiles = new ArrayList<>();
        String stepNameLower = stepName.toLowerCase();
        Files.walk(directory)
            .filter(Files::isRegularFile)
            .filter(path -> {
                String fileName = path.getFileName().toString().toLowerCase();
                return fileName.endsWith(".log") && fileName.startsWith(stepNameLower);
            })
            .forEach(logFiles::add);
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
        if (salesforceMetadata.containsKey("hostname")) { report.setDatacenter((String) salesforceMetadata.get("hostname")); }
        if (salesforceMetadata.containsKey("attachment_last_modified_date")) {
            String lastModifiedDateStr = (String) salesforceMetadata.get("attachment_last_modified_date");
            if (lastModifiedDateStr != null) {
                try { LocalDate reportDate = LocalDate.parse(lastModifiedDateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE); report.setReportDate(reportDate); }
                catch (Exception e) { logger.warn("Failed to parse LastModifiedDate: {}", lastModifiedDateStr, e); }
            }
        }
        return report;
    }

    private void deleteDirectory(Path directory) throws IOException {
        Files.walk(directory).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    public static class ProcessingResult {
        public String attachmentId;
        public String attachmentName;
        public String recordId;
        public String stepName;
        public String status;
        public String message;
        public int logsProcessed = 0;
        public int logsFailed = 0;
    }
}


