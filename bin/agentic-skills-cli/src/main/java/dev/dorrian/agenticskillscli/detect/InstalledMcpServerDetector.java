package dev.dorrian.agenticskillscli.detect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.config.ClaudeCliMcpRegistrar;
import dev.dorrian.agenticskillscli.config.TomlMcpConfigStore;
import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code detectInstalledMcpServers()}
 * — for Claude Code, shells out to {@code claude mcp get} per server name
 * (matching how Claude registration itself is special-cased); for every
 * other tool, reads the tool's MCP config file directly (JSON, or Codex's TOML via
 * {@link TomlMcpConfigStore}).
 */
public final class InstalledMcpServerDetector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstalledMcpServerDetector() {
    }

    public static Set<String> detect(List<String> serverNames, String toolKey) {
        Set<String> installed = new LinkedHashSet<>();

        if ("claude".equals(toolKey)) {
            for (String name : serverNames) {
                if (ClaudeCliMcpRegistrar.exists(name)) {
                    installed.add(name);
                }
            }
            return installed;
        }

        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        return cfgOpt.isEmpty() ? installed : detect(serverNames, cfgOpt.get());
    }

    /** Names present in any file of {@code cfg} — the global file or any extra file. */
    public static Set<String> detect(List<String> serverNames, McpConfigDef cfg) {
        Set<String> installed = new LinkedHashSet<>();
        for (Path file : cfg.allFiles()) {
            if (!Files.exists(file)) continue;

            if (TomlMcpConfigStore.handles(cfg)) {
                Set<String> present = TomlMcpConfigStore.serverNames(cfg.mcpKey(), file);
                for (String name : serverNames) {
                    if (present.contains(name)) installed.add(name);
                }
                continue;
            }

            Map<String, Object> existing;
            try {
                existing = MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (IOException e) {
                continue;
            }

            if (!(existing.get(cfg.mcpKey()) instanceof Map<?, ?> section)) continue;
            for (String name : serverNames) {
                if (section.containsKey(name)) installed.add(name);
            }
        }
        return installed;
    }
}
