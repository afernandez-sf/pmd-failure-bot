package com.pmd_failure_bot.config;

import com.pmd_failure_bot.entity.StepName;
import com.pmd_failure_bot.repository.StepNameRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Configuration
public class StepNamesSeeder {

    private static final Logger logger = LoggerFactory.getLogger(StepNamesSeeder.class);

    private final StepNameRepository stepNameRepository;

    @Autowired
    public StepNamesSeeder(StepNameRepository stepNameRepository) {
        this.stepNameRepository = stepNameRepository;
    }

    @PostConstruct
    public void seedIfEmpty() {
        if (stepNameRepository.count() > 0) {
            return;
        }

        Set<String> names = readStepNames();
        if (names.isEmpty()) {
            logger.warn("No step names found to seed. Ensure STEP_NAMES.md is available.");
            return;
        }

        int inserted = 0;
        for (String name : names) {
            stepNameRepository.save(new StepName(name));
            inserted++;
        }
        logger.info("Seeded {} step names into database", inserted);
    }

    private Set<String> readStepNames() {
        Set<String> out = new HashSet<>();

        // Try classpath resource
        try (InputStream in = getClass().getResourceAsStream("/STEP_NAMES.md")) {
            if (in != null) {
                loadFromStream(in, out);
                return out;
            }
        } catch (IOException ignored) {}

        // Try working dir file
        Path path = Path.of("STEP_NAMES.md");
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                loadFromStream(in, out);
            } catch (IOException ignored) {}
        }

        return out;
    }

    private void loadFromStream(InputStream in, Set<String> target) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                target.add(trimmed.toUpperCase(Locale.ROOT));
            }
        }
    }
}


