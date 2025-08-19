package com.pmd_failure_bot.common.constants;

/**
 * Constants for file processing operations
 */
public final class FileProcessingConstants {
    
    // Prevent instantiation
    private FileProcessingConstants() {}
    
    // File Extensions
    public static final String TAR_GZ_EXTENSION = ".tar.gz";
    public static final String TGZ_EXTENSION = ".tgz";
    public static final String LOG_EXTENSION = ".log";
    
    // Status Values
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    
    // Directory Names
    public static final String TEMP_DIR_PREFIX = "log-processing-";
    public static final String EXTRACTED_DIR_NAME = "extracted";
    
    // Separators
    public static final String UNDERSCORE_SEPARATOR = "_";
    public static final String DASH_SEPARATOR = "-";
    
    // Thread Pool Configuration
    public static final int MIN_THREAD_POOL_SIZE = 2;
    public static final int MAX_THREAD_POOL_SIZE = 8;
}