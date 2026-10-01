package dev.dorrian.agenticskillsevals.agent;

import java.util.Map;

/** Port of {@code evals/behavioral/lib/parse-agent.ts}'s {@code AgentFrontmatter} type. */
public record AgentFrontmatter(
        String description,
        String mode,
        Double temperature,
        String color,
        Map<String, Object> permission,
        String name
) {
}
