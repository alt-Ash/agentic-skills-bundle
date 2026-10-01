package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s generic JSON-config-file
 * read-modify-write functions: {@code registerAgentInConfig}, {@code
 * unregisterAgentFromConfig}, {@code installMcpServersToFile}, {@code
 * uninstallMcpServersFromFile}, {@code installMcpServers}, {@code
 * uninstallMcpServers}, {@code isMcpServerRegistered}, {@code
 * overwriteMcpServerEntry}, and the {@code isMalformedAgentEntry} guard.
 *
 * <p>Claude Code is special-cased throughout (matching the original) to
 * shell out to the {@code claude mcp} CLI via {@link ClaudeCliMcpRegistrar}
 * instead of merging JSON directly — Claude Code never reads MCP servers
 * from {@code settings.json}.
 */
public final class JsonConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConfigStore() {
    }

    // ─── Agent registration ─────────────────────────────────────────────────

    public static Optional<AgentRegistrationResult> registerAgentInConfig(
        AgentToolDef tool, String agentName, Map<String, Object> frontmatter
    ) {
        if (tool.agentConfigFile() == null) {
            return Optional.empty(); // tool doesn't support JSON agent registration
        }

        Path configFile = tool.agentConfigFile();
        Map<String, Object> config = readJsonObject(configFile);

        String agentSection = tool.agentConfigKey() != null ? tool.agentConfigKey() : "agent";
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) config.computeIfAbsent(
            agentSection, k -> new LinkedHashMap<String, Object>()
        );

        Object existing = section.get(agentName);
        boolean repaired = false;
        if (existing != null) {
            if (isMalformedAgentEntry(existing)) {
                repaired = true; // fall through to overwrite
            } else {
                return Optional.of(new AgentRegistrationResult(agentName, true, true, false, configFile.toString()));
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        for (String key : List.of("description", "mode", "temperature", "top_p", "color", "hidden", "model", "steps")) {
            if (frontmatter.get(key) != null) {
                entry.put(key, frontmatter.get(key));
            }
        }
        Object perm = frontmatter.get("permission");
        entry.put("permission", perm instanceof Map ? perm : Map.of("edit", "allow", "write", "allow", "bash", "allow"));

        Path agentsRelDir = configFile.getParent() == null
            ? tool.agentsGlobalPath()
            : configFile.getParent().relativize(tool.agentsGlobalPath());
        entry.put("prompt", "{file:./" + agentsRelDir.toString().replace('\\', '/') + "/" + agentName + ".md}");

        section.put(agentName, entry);

        writeJsonObject(configFile, config);
        return Optional.of(new AgentRegistrationResult(agentName, true, false, repaired, configFile.toString()));
    }

    /**
     * Port of {@code isMalformedAgentEntry}: detects an existing agent config
     * entry with any empty-string/null permission value, which OpenCode
     * rejects with {@code Expected PermissionActionConfig, got ""}.
     */
    @SuppressWarnings("unchecked")
    static boolean isMalformedAgentEntry(Object entry) {
        if (!(entry instanceof Map)) {
            return true;
        }
        Object perm = ((Map<String, Object>) entry).get("permission");
        if (perm instanceof Map<?, ?> permMap) {
            for (Object v : permMap.values()) {
                if ("".equals(v) || v == null) return true;
                if (v instanceof Map<?, ?> inner) {
                    for (Object innerV : inner.values()) {
                        if ("".equals(innerV) || innerV == null) return true;
                    }
                }
            }
        }
        return false;
    }

    public static Optional<OperationResult> unregisterAgentFromConfig(AgentToolDef tool, String agentName) {
        if (tool.agentConfigFile() == null) {
            return Optional.empty();
        }
        Path configFile = tool.agentConfigFile();
        if (!Files.exists(configFile)) {
            return Optional.of(OperationResult.ok(agentName, true, configFile.toString()));
        }

        Map<String, Object> config;
        try {
            config = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return Optional.of(OperationResult.ok(agentName, true, configFile.toString()));
        }

        String agentSection = tool.agentConfigKey() != null ? tool.agentConfigKey() : "agent";
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) config.get(agentSection);
        if (section == null || !section.containsKey(agentName)) {
            return Optional.of(OperationResult.ok(agentName, true, configFile.toString()));
        }

        section.remove(agentName);
        writeJsonObject(configFile, config);
        return Optional.of(OperationResult.ok(agentName, false, configFile.toString()));
    }

    // ─── MCP server config files ────────────────────────────────────────────

    /** Writes servers not already present into a single config file. Returns the names actually written. */
    public static Set<String> installMcpServersToFile(Map<String, Object> servers, McpConfigDef cfg, Path configFile) {
        Map<String, Object> existing = readJsonObject(configFile);
        @SuppressWarnings("unchecked")
        Map<String, Object> existingServers = (Map<String, Object>) existing.computeIfAbsent(
            cfg.mcpKey(), k -> new LinkedHashMap<String, Object>()
        );

        Set<String> toInstall = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : servers.entrySet()) {
            if (!existingServers.containsKey(entry.getKey())) {
                existingServers.put(entry.getKey(), entry.getValue());
                toInstall.add(entry.getKey());
            }
        }

        if (!toInstall.isEmpty()) {
            writeJsonObject(configFile, existing);
        }
        return toInstall;
    }

    /**
     * Replaces entries that still launch via {@code npx} — the pre-2.0 context7/figma configs,
     * which 2.0 points at hosted HTTP endpoints instead. Entries a user has customised to anything
     * else, and servers not present at all, are left alone. Returns the names actually replaced.
     */
    public static Set<String> migrateLegacyNpxServersInFile(Map<String, Object> servers, McpConfigDef cfg, Path configFile) {
        if (!Files.exists(configFile)) {
            return Set.of();
        }
        Map<String, Object> existing = readJsonObject(configFile);
        if (!(existing.get(cfg.mcpKey()) instanceof Map<?, ?> rawSection)) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) rawSection;
        Set<String> replaced = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : servers.entrySet()) {
            if (isLegacyNpxEntry(section.get(entry.getKey()))) {
                section.put(entry.getKey(), entry.getValue());
                replaced.add(entry.getKey());
            }
        }
        if (!replaced.isEmpty()) {
            writeJsonObject(configFile, existing);
        }
        return replaced;
    }

    /** {@link #migrateLegacyNpxServersInFile} for a tool's global config (via the CLI for Claude Code). */
    public static List<OperationResult> migrateLegacyNpxServers(Map<String, Object> servers, String toolKey) {
        List<OperationResult> results = new ArrayList<>();
        if ("claude".equals(toolKey)) {
            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                if (ClaudeCliMcpRegistrar.describe(entry.getKey()).contains("npx")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> serverConfig = (Map<String, Object>) entry.getValue();
                    results.add(overwriteMcpServerEntry(entry.getKey(), serverConfig, toolKey));
                }
            }
            return results;
        }
        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        if (cfgOpt.isEmpty()) {
            return results;
        }
        McpConfigDef cfg = cfgOpt.get();
        for (String name : migrateLegacyNpxServersInFile(servers, cfg, cfg.globalFile())) {
            results.add(OperationResult.ok(name, false, cfg.globalFile().toString()));
        }
        return results;
    }

    static boolean isLegacyNpxEntry(Object entry) {
        return entry != null && MAPPER.valueToTree(entry).toString().contains("\"npx\"");
    }

    /** Removes server names from a single config file. Returns the names actually removed. */
    public static Set<String> uninstallMcpServersFromFile(List<String> serverNames, McpConfigDef cfg, Path configFile) {
        if (!Files.exists(configFile)) {
            return Set.of();
        }
        Map<String, Object> existing;
        try {
            existing = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return Set.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) existing.getOrDefault(cfg.mcpKey(), new LinkedHashMap<>());
        Set<String> removed = new LinkedHashSet<>();
        for (String name : serverNames) {
            if (section.containsKey(name)) {
                section.remove(name);
                removed.add(name);
            }
        }

        if (!removed.isEmpty()) {
            existing.put(cfg.mcpKey(), section);
            writeJsonObject(configFile, existing);
        }
        return removed;
    }

    /** Merges MCP server entries into the global config file(s) for the given tool. */
    public static List<OperationResult> installMcpServers(Map<String, Object> servers, String toolKey) {
        if ("claude".equals(toolKey)) {
            // Each `claude mcp add` does its own non-atomic read-modify-write of
            // ~/.claude.json — running them concurrently races and silently drops entries.
            List<OperationResult> results = new ArrayList<>();
            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serverConfig = (Map<String, Object>) entry.getValue();
                results.add(ClaudeCliMcpRegistrar.install(entry.getKey(), serverConfig, false));
            }
            return results;
        }

        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        if (cfgOpt.isEmpty()) {
            return List.of();
        }
        McpConfigDef cfg = cfgOpt.get();
        Set<String> installed = installMcpServersToFile(servers, cfg, cfg.globalFile());

        List<OperationResult> results = new ArrayList<>();
        for (String name : servers.keySet()) {
            results.add(OperationResult.ok(name, !installed.contains(name), cfg.globalFile().toString()));
        }
        return results;
    }

    /** Removes a set of MCP server entries from the global config file(s) for the given tool. */
    public static List<OperationResult> uninstallMcpServers(List<String> serverNames, String toolKey) {
        if ("claude".equals(toolKey)) {
            List<OperationResult> results = new ArrayList<>();
            for (String name : serverNames) {
                results.add(ClaudeCliMcpRegistrar.uninstall(name));
            }
            return results;
        }

        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        if (cfgOpt.isEmpty()) {
            return List.of();
        }
        McpConfigDef cfg = cfgOpt.get();
        Set<String> removed = uninstallMcpServersFromFile(serverNames, cfg, cfg.globalFile());

        List<OperationResult> results = new ArrayList<>();
        for (String name : serverNames) {
            results.add(OperationResult.ok(name, !removed.contains(name), cfg.globalFile().toString()));
        }
        return results;
    }

    /** Checks whether a server name is already registered for a tool. */
    public static boolean isMcpServerRegistered(String name, String toolKey) {
        if ("claude".equals(toolKey)) {
            return ClaudeCliMcpRegistrar.exists(name);
        }
        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        if (cfgOpt.isEmpty()) {
            return false;
        }
        McpConfigDef cfg = cfgOpt.get();
        if (!Files.exists(cfg.globalFile())) {
            return false;
        }
        try {
            Map<String, Object> existing = MAPPER.readValue(
                cfg.globalFile().toFile(), new TypeReference<LinkedHashMap<String, Object>>() {}
            );
            Object section = existing.get(cfg.mcpKey());
            return section instanceof Map<?, ?> m && m.containsKey(name);
        } catch (IOException e) {
            return false;
        }
    }

    /** Unconditionally replaces a single server entry — never skips an entry already present. */
    public static OperationResult overwriteMcpServerEntry(String name, Map<String, Object> serverConfig, String toolKey) {
        if ("claude".equals(toolKey)) {
            ClaudeCliMcpRegistrar.uninstall(name);
            return ClaudeCliMcpRegistrar.install(name, serverConfig, true);
        }

        Optional<McpConfigDef> cfgOpt = McpConfigRegistry.get(toolKey);
        if (cfgOpt.isEmpty()) {
            return OperationResult.ok(name, true, null);
        }
        McpConfigDef cfg = cfgOpt.get();
        Map<String, Object> existing = readJsonObject(cfg.globalFile());
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) existing.computeIfAbsent(
            cfg.mcpKey(), k -> new LinkedHashMap<String, Object>()
        );
        section.put(name, serverConfig);
        writeJsonObject(cfg.globalFile(), existing);
        return OperationResult.ok(name, false, cfg.globalFile().toString());
    }

    // ─── Shared JSON I/O helpers ─────────────────────────────────────────────

    private static Map<String, Object> readJsonObject(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>(); // leave as empty; will create fresh, matching the original's try/catch
        }
    }

    private static void writeJsonObject(Path file, Map<String, Object> content) {
        try {
            Files.createDirectories(file.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content) + "\n";
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
