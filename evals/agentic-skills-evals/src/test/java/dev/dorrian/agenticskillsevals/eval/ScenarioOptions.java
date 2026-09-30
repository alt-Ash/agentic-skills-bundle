package dev.dorrian.agenticskillsevals.eval;

import dev.dorrian.agenticskillsevals.mcp.InProcessMcpBridge;

import java.util.List;
import java.util.Map;

/**
 * Port of {@code EvalConfig.scenarioOptions}'s per-scenario return shape. {@code judgeCriteria}
 * is {@code null} to fall back to the eval's base {@link AbstractEvalTest#judgeCriteria()}.
 */
public record ScenarioOptions(List<String> allowedTools, InProcessMcpBridge mcpBridge, Map<String, String> judgeCriteria) {

    public static final ScenarioOptions EMPTY = new ScenarioOptions(List.of(), null, null);

    public ScenarioOptions {
        allowedTools = allowedTools == null ? List.of() : allowedTools;
    }
}
