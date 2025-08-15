package com.pmd_failure_bot.service.imports.extract;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class TarGzExtractorStrategy {
    public boolean canHandle(String fileName) { String lower = fileName.toLowerCase(); return lower.endsWith(".tar.gz") || lower.endsWith(".tgz"); }
    public boolean extract(Path archiveFile, Path extractDir, long maxFileSize) throws IOException {
        try (FileInputStream fis = new FileInputStream(archiveFile.toFile());
             GzipCompressorInputStream gzis = new GzipCompressorInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gzis)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() > 0 && entry.getSize() > maxFileSize) continue;
                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) continue;
                Files.createDirectories(outputPath.getParent());
                Files.copy(tis, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return true;
    }
}


