package com.pmd_failure_bot.util;

import com.pmd_failure_bot.data.repository.StepNameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class StepNameNormalizer {
    
    private static final String LOG_EXTENSION = ".log";
    private static final String UNDERSCORE_SEPARATOR = "_";
    private static final String DASH_SEPARATOR = "-";
    
    private final StepNameRepository stepNameRepository;
    private volatile Set<String> cachedCanonicalNames;

    public String normalize(String rawStepName) {
        if (rawStepName == null || rawStepName.trim().isEmpty()) {
            log.debug("Received null or empty step name for normalization");
            return null;
        }
        
        String sanitized = sanitize(rawStepName);
        Set<String> canonicalNames = loadCanonicalNames();
        
        // Exact match
        if (canonicalNames.contains(sanitized)) {
            log.debug("Found exact match for step name: {} -> {}", rawStepName, sanitized);
            return sanitized;
        }
        
        // Best prefix match
        String bestPrefix = findBestCanonicalPrefix(sanitized, canonicalNames);
        if (bestPrefix != null) {
            log.debug("Found prefix match for step name: {} -> {}", rawStepName, bestPrefix);
            return bestPrefix;
        }
        
        // Fallback: try removing suffixes
        String fallbackMatch = findFallbackMatch(sanitized, canonicalNames);
        if (fallbackMatch != null) {
            log.debug("Found fallback match for step name: {} -> {}", rawStepName, fallbackMatch);
            return fallbackMatch;
        }
        
        log.debug("No canonical match found for step name: {} -> {}", rawStepName, sanitized);
        return sanitized;
    }

    private String findBestCanonicalPrefix(String input, Set<String> canonicalNames) {
        String best = null;
        int bestLen = -1;
        for (String name : canonicalNames) {
            if (input.equals(name) || 
                input.startsWith(name + UNDERSCORE_SEPARATOR) || 
                input.startsWith(name + DASH_SEPARATOR)) {
                if (name.length() > bestLen) {
                    best = name;
                    bestLen = name.length();
                }
            }
        }
        return best;
    }
    
    private String findFallbackMatch(String input, Set<String> canonicalNames) {
        String candidate = input;
        int separatorIndex;
        while ((separatorIndex = candidate.lastIndexOf(UNDERSCORE_SEPARATOR.charAt(0))) > 0) {
            candidate = candidate.substring(0, separatorIndex);
            if (canonicalNames.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String sanitize(String input) {
        if (input == null) return null;
        
        String s = input.trim();
        if (s.isEmpty()) return s;
        
        // Remove path separators
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        
        // Remove .log extension
        if (s.toLowerCase(Locale.ROOT).endsWith(LOG_EXTENSION)) {
            s = s.substring(0, s.length() - LOG_EXTENSION.length());
        }
        
        return s.trim().replace(' ', UNDERSCORE_SEPARATOR.charAt(0)).toUpperCase(Locale.ROOT);
    }

    private Set<String> loadCanonicalNames() {
        if (cachedCanonicalNames != null) {
            return cachedCanonicalNames;
        }
        
        synchronized (this) {
            if (cachedCanonicalNames != null) {
                return cachedCanonicalNames;
            }
            
            try {
                log.debug("Loading canonical step names from database");
                Set<String> names = new HashSet<>();
                stepNameRepository.findAll().forEach(row -> {
                    if (row.getStepName() != null && !row.getStepName().isBlank()) {
                        names.add(row.getStepName().trim().toUpperCase(Locale.ROOT));
                    }
                });
                
                cachedCanonicalNames = names;
                log.info("Loaded {} canonical step names", names.size());
                return cachedCanonicalNames;
                
            } catch (Exception e) {
                log.error("Failed to load canonical step names from database", e);
                // Return empty set to avoid repeated database calls
                cachedCanonicalNames = new HashSet<>();
                return cachedCanonicalNames;
            }
        }
    }
    
    /**
     * Clears the cached canonical names, forcing a reload on next access
     */
    public void clearCache() {
        log.info("Clearing canonical step names cache");
        cachedCanonicalNames = null;
    }
}


