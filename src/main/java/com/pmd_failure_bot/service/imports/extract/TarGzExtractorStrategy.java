package com.pmd_failure_bot.service.imports.extract;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
public final class TarGzExtractorStrategy {
    
    private static final String TAR_GZ_EXTENSION = ".tar.gz";
    private static final String TGZ_EXTENSION = ".tgz";
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(TAR_GZ_EXTENSION) || lower.endsWith(TGZ_EXTENSION);
    }

    public boolean extract(Path archiveFile, Path extractDir, long maxFileSize) throws IOException {
        if (archiveFile == null || extractDir == null) {
            throw new IllegalArgumentException("Archive file and extract directory cannot be null");
        }
        
        if (!Files.exists(archiveFile)) {
            throw new IllegalArgumentException("Archive file does not exist: " + archiveFile);
        }
        
        log.info("Extracting tar.gz archive: {} to {}", archiveFile, extractDir);
        
        int extractedFiles = 0;
        try (FileInputStream fis = new FileInputStream(archiveFile.toFile());
             GzipCompressorInputStream gzis = new GzipCompressorInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gzis)) {
            
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                
                // Check file size limit
                if (entry.getSize() > 0 && entry.getSize() > maxFileSize) {
                    log.warn("Skipping file {} - size {} exceeds limit {}", 
                            entry.getName(), entry.getSize(), maxFileSize);
                    continue;
                }
                
                
                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) {
                    log.warn("Skipping path outside extract directory: {}", outputPath);
                    continue;
                }
                
                try {
                    Files.createDirectories(outputPath.getParent());
                    Files.copy(tis, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    extractedFiles++;
                    log.debug("Extracted file: {}", outputPath);
                } catch (Exception e) {
                    log.error("Failed to extract file: {}", entry.getName(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to extract archive: {}", archiveFile, e);
            throw e;
        }
        
        log.info("Successfully extracted {} files from {}", extractedFiles, archiveFile);
        return extractedFiles > 0;
    }
}
