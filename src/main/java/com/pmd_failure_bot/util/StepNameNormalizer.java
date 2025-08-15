package com.pmd_failure_bot.util;

import com.pmd_failure_bot.data.repository.StepNameRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class StepNameNormalizer {
    private final StepNameRepository stepNameRepository;
    private volatile Set<String> cachedCanonicalNames;

    @Autowired
    public StepNameNormalizer(StepNameRepository stepNameRepository) {
        this.stepNameRepository = stepNameRepository;
    }

    public String normalize(String rawStepName) {
        if (rawStepName == null) return null;
        String upper = sanitize(rawStepName);
        Set<String> canon = loadCanonicalNames();
        if (canon.contains(upper)) return upper;
        String bestPrefix = findBestCanonicalPrefix(upper, canon);
        if (bestPrefix != null) return bestPrefix;
        int underscore;
        String candidate = upper;
        while ((underscore = candidate.lastIndexOf('_')) > 0) {
            candidate = candidate.substring(0, underscore);
            if (canon.contains(candidate)) return candidate;
        }
        return upper;
    }

    private String findBestCanonicalPrefix(String upper, Set<String> canon) {
        String best = null;
        int bestLen = -1;
        for (String name : canon) {
            if (upper.equals(name) || upper.startsWith(name + "_") || upper.startsWith(name + "-")) {
                if (name.length() > bestLen) {
                    best = name;
                    bestLen = name.length();
                }
            }
        }
        return best;
    }

    private String sanitize(String input) {
        String s = input;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.toLowerCase(Locale.ROOT).endsWith(".log")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private Set<String> loadCanonicalNames() {
        if (cachedCanonicalNames != null) return cachedCanonicalNames;
        synchronized (this) {
            if (cachedCanonicalNames != null) return cachedCanonicalNames;
            Set<String> names = new HashSet<>();
            stepNameRepository.findAll().forEach(row -> {
                if (row.getStepName() != null && !row.getStepName().isBlank()) {
                    names.add(row.getStepName().trim().toUpperCase(Locale.ROOT));
                }
            });
            cachedCanonicalNames = names;
            return cachedCanonicalNames;
        }
    }
}


