package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code detectInstalledTools()} — a
 * tool counts as installed when any of {@link AgentToolRegistry#detectPaths}
 * exists.
 */
public final class InstalledToolDetector {

    private InstalledToolDetector() {
    }

    public static Map<String, Boolean> detect() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String key : AgentToolRegistry.ALL.keySet()) {
            results.put(key, AgentToolRegistry.detectPaths(key).stream().anyMatch(Files::exists));
        }
        return results;
    }
}
