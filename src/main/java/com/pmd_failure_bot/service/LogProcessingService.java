package com.pmd_failure_bot.service;

import com.pmd_failure_bot.config.SalesforceConfig;
import com.pmd_failure_bot.entity.PmdReport;
import com.pmd_failure_bot.repository.PmdReportRepository;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class LogProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(LogProcessingService.class);
    
    private final SalesforceConfig salesforceConfig;
    private final PmdReportRepository pmdReportRepository;
    

    private static final List<String> ERROR_PATTERNS = Arrays.asList(
        "\\bERROR\\b",
        "\\[ERROR\\]",
        "\\bFATAL\\b",
        "\\bFAILED\\b",
        "Refusing to execute",
        "Unable to get",
        "Unable to retrieve",
        "Unable to start",
        "connection error",
        "maximum retries reached",
        "Oracle not available"
    );
    
    @Autowired
    public LogProcessingService(SalesforceConfig salesforceConfig, PmdReportRepository pmdReportRepository) {
        this.salesforceConfig = salesforceConfig;
        this.pmdReportRepository = pmdReportRepository;
    }
    
    /**
     * Process attachment: download, extract, and parse log files
     */
    public ProcessingResult processAttachment(String attachmentId, String attachmentName, 
                                            String recordId, String stepName, 
                                            Map<String, Object> salesforceMetadata,
                                            SalesforceService salesforceService) {
        
        ProcessingResult result = new ProcessingResult();
        result.attachmentId = attachmentId;
        result.attachmentName = attachmentName;
        result.recordId = recordId;
        result.stepName = stepName;
        
        // Check if already processed
        if (isAttachmentProcessed(attachmentId)) {
            result.status = "SKIPPED";
            result.message = "Attachment already processed";
            logger.info("Skipping already processed attachment: {}", attachmentId);
            return result;
        }
        
        Path tempDir = null;
        try {
            logger.info("Processing attachment: {} ({})", attachmentName, attachmentId);
            
            // Create temporary directory
            tempDir = Files.createTempDirectory("log-processing-");
            
            // Download attachment
            byte[] attachmentData = salesforceService.downloadAttachment(attachmentId);
            if (attachmentData.length > salesforceConfig.getMaxAttachmentSize()) {
                result.status = "FAILED";
                result.message = "Attachment too large: " + attachmentData.length + " bytes";
                return result;
            }
            
            // Save to temporary file
            Path tempFile = tempDir.resolve(sanitizeFileName(attachmentName));
            Files.write(tempFile, attachmentData);
            
            // Extract archive
            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);
            
            if (!extractArchive(tempFile, extractDir)) {
                result.status = "FAILED";
                result.message = "Failed to extract archive";
                return result;
            }
            
            // Find and process log files
            List<Path> logFiles = findLogFiles(extractDir, stepName);
            if (logFiles.isEmpty()) {
                result.status = "FAILED";
                result.message = "No log files found for step: " + stepName;
                return result;
            }
            
            // Process each log file
            int successfulLogs = 0;
            int failedLogs = 0;
            
            for (Path logFile : logFiles) {
                try {
                    PmdReport report = processLogFile(logFile, attachmentId, recordId, salesforceMetadata);
                    if (report != null) {
                        pmdReportRepository.save(report);
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
            result.message = String.format("Processed %d logs successfully, %d failed", 
                                         successfulLogs, failedLogs);
            
        } catch (Exception e) {
            logger.error("Failed to process attachment: {}", attachmentName, e);
            result.status = "FAILED";
            result.message = "Processing error: " + e.getMessage();
        } finally {
            // Cleanup temporary directory
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException e) {
                    logger.warn("Failed to cleanup temporary directory: {}", tempDir, e);
                }
            }
        }
        
        return result;
    }
    
    private boolean isAttachmentProcessed(String attachmentId) {
        return pmdReportRepository.findByFilters(null, null, null, null, attachmentId, 
                                                null, null).size() > 0;
    }
    
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown_file";
        return fileName.replaceAll("[^\\w\\.\\-]", "_");
    }
    
    private boolean extractArchive(Path archiveFile, Path extractDir) {
        String fileName = archiveFile.getFileName().toString().toLowerCase();
        
        try {
            if (fileName.endsWith(".zip")) {
                return extractZip(archiveFile, extractDir);
            } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
                return extractTarGz(archiveFile, extractDir);
            } else if (fileName.endsWith(".tar")) {
                return extractTar(archiveFile, extractDir);
            } else {
                logger.warn("Unsupported archive type: {}", fileName);
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to extract archive: {}", archiveFile.getFileName(), e);
            return false;
        }
    }
    
    private boolean extractZip(Path zipFile, Path extractDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() > salesforceConfig.getMaxAttachmentSize()) continue;
                if (!isValidPath(entry.getName())) continue;
                
                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) continue; // Path traversal protection
                
                Files.createDirectories(outputPath.getParent());
                Files.copy(zis, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return true;
    }
    
    private boolean extractTarGz(Path tarGzFile, Path extractDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(tarGzFile.toFile());
             GzipCompressorInputStream gzis = new GzipCompressorInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gzis)) {
            
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() > salesforceConfig.getMaxAttachmentSize()) continue;
                if (!isValidPath(entry.getName())) continue;
                
                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) continue; // Path traversal protection
                
                Files.createDirectories(outputPath.getParent());
                Files.copy(tis, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return true;
    }
    
    private boolean extractTar(Path tarFile, Path extractDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(tarFile.toFile());
             TarArchiveInputStream tis = new TarArchiveInputStream(fis)) {
            
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() > salesforceConfig.getMaxAttachmentSize()) continue;
                if (!isValidPath(entry.getName())) continue;
                
                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) continue; // Path traversal protection
                
                Files.createDirectories(outputPath.getParent());
                Files.copy(tis, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return true;
    }
    
    private boolean isValidPath(String path) {
        return !path.contains("..") && !Paths.get(path).isAbsolute();
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
    
    private PmdReport processLogFile(Path logFile, String attachmentId, String recordId, 
                                   Map<String, Object> salesforceMetadata) throws IOException {
        
        PmdReport report = new PmdReport();
        
        // Read log content
        List<String> lines = Files.readAllLines(logFile);
        
        // Extract error context or use full log content
        String errorContext = extractErrorContext(lines);
        if (errorContext.isEmpty()) {
            // If no error patterns detected, store the entire log content
            errorContext = String.join("\n", lines);
        }
        
        // Set basic fields
        report.setContent(errorContext);
        report.setAttachmentId(attachmentId);
        report.setRecordId(recordId);
        
        // Set Salesforce metadata
        if (salesforceMetadata.containsKey("work_id")) {
            report.setWorkId((String) salesforceMetadata.get("work_id"));
        }
        if (salesforceMetadata.containsKey("case_number")) {
            report.setCaseNumber((Integer) salesforceMetadata.get("case_number"));
        }
        if (salesforceMetadata.containsKey("step_name")) {
            report.setStepName((String) salesforceMetadata.get("step_name"));
        } else {
            // Fallback: extract step name from filename if not available in Salesforce metadata
            String fileName = logFile.getFileName().toString().replaceAll("\\.log$", "");
            report.setStepName(fileName);
        }
        if (salesforceMetadata.containsKey("hostname")) {
            report.setHostname((String) salesforceMetadata.get("hostname"));
        } else {
            // Fallback: extract hostname from log content if not available in Salesforce metadata
            String fullContent = String.join("\n", lines);
            extractHostnameFromLogContent(report, fullContent);
        }
        
        // Set report date from attachment LastModifiedDate
        if (salesforceMetadata.containsKey("attachment_last_modified_date")) {
            String lastModifiedDateStr = (String) salesforceMetadata.get("attachment_last_modified_date");
            if (lastModifiedDateStr != null) {
                try {
                    // Parse Salesforce datetime format (ISO format) and extract date part
                    // Example: "2024-09-17T14:09:21.000+0000"
                    LocalDate reportDate = LocalDate.parse(lastModifiedDateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
                    report.setReportDate(reportDate);
                } catch (Exception e) {
                    logger.warn("Failed to parse LastModifiedDate: {}", lastModifiedDateStr, e);
                }
            }
        }
        
        return report;
    }
    
    private String extractErrorContext(List<String> lines) {
        List<Pattern> errorPatterns = ERROR_PATTERNS.stream()
            .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
            .toList();
        
        Set<Integer> errorIndices = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern pattern : errorPatterns) {
                if (pattern.matcher(line).find()) {
                    errorIndices.add(i);
                    break;
                }
            }
        }
        
        if (errorIndices.isEmpty()) {
            return "";
        }
        
        // Create ranges with context
        List<int[]> ranges = new ArrayList<>();
        int contextLines = 3;
        
        for (int errorIdx : errorIndices.stream().sorted().toList()) {
            int start = Math.max(0, errorIdx - contextLines);
            int end = Math.min(lines.size(), errorIdx + contextLines + 1);
            ranges.add(new int[]{start, end});
        }
        
        // Merge overlapping ranges
        List<int[]> mergedRanges = new ArrayList<>();
        for (int[] range : ranges) {
            if (mergedRanges.isEmpty() || range[0] > mergedRanges.get(mergedRanges.size() - 1)[1]) {
                mergedRanges.add(range);
            } else {
                int[] lastRange = mergedRanges.get(mergedRanges.size() - 1);
                lastRange[1] = Math.max(lastRange[1], range[1]);
            }
        }
        
        // Extract lines from merged ranges
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < mergedRanges.size(); i++) {
            if (i > 0) {
                context.append("\n--- ERROR CONTEXT SEPARATOR ---\n");
            }
            int[] range = mergedRanges.get(i);
            for (int j = range[0]; j < range[1]; j++) {
                context.append(lines.get(j)).append("\n");
            }
        }
        
        return context.toString();
    }
    
    private void extractHostnameFromLogContent(PmdReport report, String logContent) {
        // Extract hostname from log content as fallback
        Pattern hostnamePattern = Pattern.compile("Hostname:\\s*([^,\\n]+)");
        Matcher hostnameMatcher = hostnamePattern.matcher(logContent);
        if (hostnameMatcher.find()) {
            String fullHostname = hostnameMatcher.group(1).trim();
            report.setHostname(extractHostnameSuffix(fullHostname));
        }
    }
    

    
    /**
     * Extract hostname suffix (last part after hyphen)
     * Examples:
     * - ops0-release1-2-hn3 -> hn3
     * - worker-host-sp1 -> sp1
     */
    private String extractHostnameSuffix(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) {
            return hostname;
        }
        
        String trimmed = hostname.trim();
        int lastHyphen = trimmed.lastIndexOf('-');
        if (lastHyphen >= 0 && lastHyphen < trimmed.length() - 1) {
            return trimmed.substring(lastHyphen + 1);
        }
        return trimmed; // Return full hostname if no hyphen found
    }
    
    private void deleteDirectory(Path directory) throws IOException {
        Files.walk(directory)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
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