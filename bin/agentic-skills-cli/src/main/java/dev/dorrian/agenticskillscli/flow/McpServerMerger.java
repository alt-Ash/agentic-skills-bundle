package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.registry.AgentMcpServerRegistry;
import dev.dorrian.agenticskillscli.registry.SkillMcpRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code bin/install.js}'s {@code resolveSkillMcpServers}/{@code
 * resolveAgentMcpServers} — merges the per-skill/per-agent MCP server maps
 * for a given tool key across a whole selected list (later entries win on
 * key collision, matching {@code Object.assign} semantics in the original).
 */
public final class McpServerMerger {

    private McpServerMerger() {
    }

    public static Map<String, Object> mergeSkillServers(List<SkillDescriptor> skills, String toolKey) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (SkillDescriptor skill : skills) {
            merged.putAll(SkillMcpRegistry.serversFor(skill.name(), toolKey));
        }
        return merged;
    }

    public static Map<String, Object> mergeAgentServers(List<AgentDescriptor> agents, String toolKey) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (AgentDescriptor agent : agents) {
            merged.putAll(AgentMcpServerRegistry.serversFor(agent.name(), toolKey));
        }
        return merged;
    }
}
