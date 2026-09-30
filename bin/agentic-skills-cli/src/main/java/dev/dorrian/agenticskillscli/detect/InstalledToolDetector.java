package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Java port of {@code bin/install.js}'s {@code detectInstalledTools()}. */
public final class InstalledToolDetector {

    private InstalledToolDetector() {
    }

    public static Map<String, Boolean> detect() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<String, AgentToolDef> entry : AgentToolRegistry.ALL.entrySet()) {
            Path detectPath = entry.getValue().detectPath();
            results.put(entry.getKey(), detectPath != null && Files.exists(detectPath));
        }
        return results;
    }
}
