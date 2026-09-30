package dev.dorrian.agenticskillscli.registry;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code AGENT_COMPANION_FILES} object
 * — agent name -&gt; extra files (relative to the agents source dir) copied
 * alongside the agent's markdown file.
 */
public final class CompanionFileRegistry {

    public static final Map<String, List<String>> ALL = Map.of(
        "security-auditor", List.of("audit-triage.sh", "security-scan.sh")
    );

    private CompanionFileRegistry() {
    }

    public static List<String> companionsFor(String agentName) {
        return ALL.getOrDefault(agentName, List.of());
    }
}
