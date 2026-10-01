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
 * <p>Tools whose {@link McpConfigDef#serverFormat()} is {@code "toml"} (Codex's
 * {@code config.toml}) are delegated to {@link TomlMcpConfigStore} at the per-file level, so
 * every public MCP method here works unchanged for them.
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
        if (TomlMcpConfigStore.handles(cfg)) {
            return TomlMcpConfigStore.installIfAbsent(cfg.mcpKey(), servers, configFile);
        }
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
        if (TomlMcpConfigStore.handles(cfg)) {
            return TomlMcpConfigStore.migrateLegacyNpx(cfg.mcpKey(), servers, configFile);
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
        return McpConfigRegistry.get(toolKey).map(cfg -> migrateLegacyNpxServers(servers, cfg)).orElse(results);
    }

    /** {@link #migrateLegacyNpxServersInFile} for every existing file of {@code cfg} — one result per file and name. */
    public static List<OperationResult> migrateLegacyNpxServers(Map<String, Object> servers, McpConfigDef cfg) {
        List<OperationResult> results = new ArrayList<>();
        for (Path file : cfg.allFiles()) { // only ever rewrites files that already exist
            for (String name : migrateLegacyNpxServersInFile(servers, cfg, file)) {
                results.add(OperationResult.ok(name, false, file.toString()));
            }
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
        if (TomlMcpConfigStore.handles(cfg)) {
            return TomlMcpConfigStore.uninstall(cfg.mcpKey(), serverNames, configFile);
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

        return McpConfigRegistry.get(toolKey).map(cfg -> installMcpServers(servers, cfg)).orElse(List.of());
    }

    /**
     * Merges MCP server entries into every {@link McpConfigDef#writableFiles() writable file} of
     * {@code cfg}. A name is skipped only if no file needed it; {@code configFile} lists the files
     * it was newly written to (comma-separated), or the global file when skipped.
     */
    public static List<OperationResult> installMcpServers(Map<String, Object> servers, McpConfigDef cfg) {
        Map<String, List<String>> writtenTo = new LinkedHashMap<>();
        for (Path file : cfg.writableFiles()) {
            for (String name : installMcpServersToFile(servers, cfg, file)) {
                writtenTo.computeIfAbsent(name, k -> new ArrayList<>()).add(file.toString());
            }
        }
        return results(servers.keySet(), writtenTo, cfg);
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

        return McpConfigRegistry.get(toolKey).map(cfg -> uninstallMcpServers(serverNames, cfg)).orElse(List.of());
    }

    /** Removes server entries from every file of {@code cfg} (global and extra files alike). */
    public static List<OperationResult> uninstallMcpServers(List<String> serverNames, McpConfigDef cfg) {
        Map<String, List<String>> removedFrom = new LinkedHashMap<>();
        for (Path file : cfg.allFiles()) {
            for (String name : uninstallMcpServersFromFile(serverNames, cfg, file)) {
                removedFrom.computeIfAbsent(name, k -> new ArrayList<>()).add(file.toString());
            }
        }
        return results(serverNames, removedFrom, cfg);
    }

    private static List<OperationResult> results(
        Iterable<String> names, Map<String, List<String>> touchedFiles, McpConfigDef cfg
    ) {
        List<OperationResult> results = new ArrayList<>();
        for (String name : names) {
            List<String> files = touchedFiles.get(name);
            results.add(files == null
                ? OperationResult.ok(name, true, cfg.globalFile().toString())
                : OperationResult.ok(name, false, String.join(", ", files)));
        }
        return results;
    }

    /** Checks whether a server name is already registered for a tool. */
    public static boolean isMcpServerRegistered(String name, String toolKey) {
        if ("claude".equals(toolKey)) {
            return ClaudeCliMcpRegistrar.exists(name);
        }
        return McpConfigRegistry.get(toolKey).map(cfg -> isMcpServerRegistered(name, cfg)).orElse(false);
    }

    /** True if any file of {@code cfg} (global or extra) has an entry named {@code name}. */
    public static boolean isMcpServerRegistered(String name, McpConfigDef cfg) {
        for (Path file : cfg.allFiles()) {
            if (!Files.exists(file)) {
                continue;
            }
            if (TomlMcpConfigStore.handles(cfg)) {
                if (TomlMcpConfigStore.isRegistered(cfg.mcpKey(), name, file)) {
                    return true;
                }
                continue;
            }
            try {
                Map<String, Object> existing = MAPPER.readValue(
                    file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {}
                );
                if (existing.get(cfg.mcpKey()) instanceof Map<?, ?> m && m.containsKey(name)) {
                    return true;
                }
            } catch (IOException e) {
                // unreadable file: treat as not registered there
            }
        }
        return false;
    }

    /** Unconditionally replaces a single server entry — never skips an entry already present. */
    public static OperationResult overwriteMcpServerEntry(String name, Map<String, Object> serverConfig, String toolKey) {
        if ("claude".equals(toolKey)) {
            ClaudeCliMcpRegistrar.uninstall(name);
            return ClaudeCliMcpRegistrar.install(name, serverConfig, true);
        }

        return McpConfigRegistry.get(toolKey)
            .map(cfg -> overwriteMcpServerEntry(name, serverConfig, cfg))
            .orElse(OperationResult.ok(name, true, null));
    }

    /** Unconditionally replaces a single server entry in every {@link McpConfigDef#writableFiles() writable file}. */
    public static OperationResult overwriteMcpServerEntry(String name, Map<String, Object> serverConfig, McpConfigDef cfg) {
        List<String> written = new ArrayList<>();
        for (Path file : cfg.writableFiles()) {
            if (TomlMcpConfigStore.handles(cfg)) {
                TomlMcpConfigStore.overwrite(cfg.mcpKey(), name, serverConfig, file);
                written.add(file.toString());
                continue;
            }
            Map<String, Object> existing = readJsonObject(file);
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) existing.computeIfAbsent(
                cfg.mcpKey(), k -> new LinkedHashMap<String, Object>()
            );
            section.put(name, serverConfig);
            writeJsonObject(file, existing);
            written.add(file.toString());
        }
        return OperationResult.ok(name, false, String.join(", ", written));
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
