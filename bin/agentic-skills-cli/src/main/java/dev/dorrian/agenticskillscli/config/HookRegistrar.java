package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.registry.HookDescriptor;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;
import dev.dorrian.agenticskillscli.registry.HooksRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NEW — closes the "Known gap" documented in CLAUDE.md: {@code
 * bin/install.js} has never had any code path to register {@code hooks/*}
 * into a consuming tool's config. This is a from-scratch design, scoped to
 * Claude Code's {@code settings.json} hooks schema only for v1 (verified
 * live against {@code code.claude.com/docs/en/hooks}):
 *
 * <pre>{@code
 * { "hooks": { "PostToolUse": [{ "matcher": "", "hooks": [
 *     { "type": "command", "command": "java", "args": ["-jar", "<jarPath>", "post-tool-use"] }
 * ] }] } }
 * }</pre>
 *
 * <p>Merges without clobbering: the user's own hook entries are left
 * untouched; ours are refreshed to the current descriptors (see {@link #registerAll}), matching the read-modify-write discipline of {@link
 * JsonConfigStore}.
 *
 * <p>Our entries are recognised by the jar they run ({@link
 * HooksJarLocation#JAR_NAME}), never by hook type alone, so a user's own
 * {@code java -jar ... stop} hook is neither mistaken for ours at install
 * time nor removed by {@link #unregisterAll}.
 */
public final class HookRegistrar {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOOKS_KEY = "hooks";

    private HookRegistrar() {
    }

    public static void registerAll(Path settingsFile, Path hooksJarPath) {
        registerAll(settingsFile, hooksJarPath, false);
    }

    public static void registerAll(Path settingsFile, Path hooksJarPath, boolean includeGuard) {
        registerAll(settingsFile, hooksJarPath, new HookInstallOptions(includeGuard, false, false));
    }

    /**
     * Converges our entries to exactly the current descriptors: each (hook type, event) is updated
     * in place (command, jar path, timeout, matcher) or added; our entries that the descriptors no
     * longer call for (an opt-in the user dropped, a stale event, duplicates) are removed. Entries
     * are recognised by jar name only, so the user's own hooks and other settings are untouched.
     * Rewrites the file only when something changed, so a repeat run is byte-identical.
     */
    @SuppressWarnings("unchecked")
    public static void registerAll(Path settingsFile, Path hooksJarPath, HookInstallOptions options) {
        List<HookDescriptor> descriptors = new ArrayList<>(HooksRegistry.ALL);
        descriptors.addAll(options.descriptors());
        Map<String, Object> config = readJsonObject(settingsFile);
        boolean hadHooks = config.get(HOOKS_KEY) instanceof Map<?, ?>;
        Map<String, Object> hooksSection = hadHooks
            ? (Map<String, Object>) config.get(HOOKS_KEY)
            : new LinkedHashMap<>();

        Map<String, Map<String, HookDescriptor>> desired = new LinkedHashMap<>();
        for (HookDescriptor d : descriptors) {
            for (String event : d.claudeEventNames()) {
                desired.computeIfAbsent(event, k -> new LinkedHashMap<>()).put(d.hookType(), d);
            }
        }

        boolean changed = !hadHooks;
        // 1. Update / prune existing entries of ours.
        for (Iterator<Map.Entry<String, Object>> events = hooksSection.entrySet().iterator(); events.hasNext(); ) {
            Map.Entry<String, Object> ev = events.next();
            if (!(ev.getValue() instanceof List<?> rawEntries)) continue;
            List<Object> entries = (List<Object>) rawEntries;
            Map<String, HookDescriptor> wanted = desired.getOrDefault(ev.getKey(), Map.of());
            java.util.Set<String> seen = new java.util.HashSet<>();
            boolean touched = false;
            for (Iterator<Object> it = entries.iterator(); it.hasNext(); ) {
                if (!(it.next() instanceof Map<?, ?> rawEntry) || !(rawEntry.get("hooks") instanceof List<?> rawCmds)) continue;
                Map<String, Object> entry = (Map<String, Object>) rawEntry;
                List<Object> cmds = (List<Object>) rawCmds;
                if (cmds.stream().noneMatch(HookRegistrar::isOurCommand)) continue;
                boolean onlyOurs = cmds.stream().allMatch(HookRegistrar::isOurCommand);
                for (Iterator<Object> ci = cmds.iterator(); ci.hasNext(); ) {
                    Object c = ci.next();
                    if (!isOurCommand(c)) continue;
                    touched = true;
                    Object type = lastArg(c);
                    HookDescriptor d = type instanceof String t ? wanted.get(t) : null;
                    if (d == null || !seen.add(d.hookType())) {
                        ci.remove();
                        changed = true;
                        continue;
                    }
                    Map<String, Object> cmd = (Map<String, Object>) c;
                    changed |= putIfDifferent(cmd, "type", "command");
                    changed |= putIfDifferent(cmd, "command", "java");
                    changed |= putIfDifferent(cmd, "args", List.of("-jar", hooksJarPath.toString(), d.hookType()));
                    changed |= putIfDifferent(cmd, "timeout", d.timeoutSeconds());
                    if (onlyOurs) {
                        changed |= putIfDifferent(entry, "matcher", d.matcher());
                    }
                }
                if (cmds.isEmpty()) {
                    it.remove();
                }
            }
            if (touched && entries.isEmpty()) {
                events.remove();
                changed = true;
            }
        }

        // 2. Add what is still missing.
        for (Map.Entry<String, Map<String, HookDescriptor>> ev : desired.entrySet()) {
            List<Object> entries = (List<Object>) hooksSection.computeIfAbsent(ev.getKey(), k -> new ArrayList<Object>());
            for (HookDescriptor d : ev.getValue().values()) {
                if (!alreadyRegistered(entries, d.hookType())) {
                    entries.add(newEntry(d, hooksJarPath));
                    changed = true;
                }
            }
        }

        if (changed) {
            config.put(HOOKS_KEY, hooksSection);
            writeJsonObject(settingsFile, config);
        }
    }

    private static boolean putIfDifferent(Map<String, Object> map, String key, Object value) {
        if (value.equals(map.get(key))) return false;
        map.put(key, value);
        return true;
    }

    private static Map<String, Object> newEntry(HookDescriptor descriptor, Path jarPath) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", "java");
        command.put("args", List.of("-jar", jarPath.toString(), descriptor.hookType()));
        command.put("timeout", descriptor.timeoutSeconds());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("matcher", descriptor.matcher());
        entry.put("hooks", new ArrayList<>(List.of(command)));
        return entry;
    }

    private static boolean alreadyRegistered(List<Object> eventEntries, String hookType) {
        for (Object entryObj : eventEntries) {
            if (!(entryObj instanceof Map<?, ?> entry)) continue;
            if (!(entry.get("hooks") instanceof List<?> commands)) continue;
            for (Object command : commands) {
                if (isOurCommand(command) && hookType.equals(lastArg(command))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Which opt-in hooks (guard, verify, context) are currently registered by us in this settings file. An
     * unparsable or missing file reports none. Lets the installer default its questions to the current state
     * instead of silently dropping an opt-in the user enabled earlier.
     */
    public static HookInstallOptions registeredOptIns(Path settingsFile) {
        if (!(readJsonObjectOrEmpty(settingsFile).get(HOOKS_KEY) instanceof Map<?, ?> hooksSection)) {
            return HookInstallOptions.NONE;
        }
        return new HookInstallOptions(
            hasHookType(hooksSection, HooksRegistry.GUARD),
            hasHookType(hooksSection, HooksRegistry.VERIFY),
            hasHookType(hooksSection, HooksRegistry.CONTEXT));
    }

    private static boolean hasHookType(Map<?, ?> hooksSection, HookDescriptor descriptor) {
        for (String event : descriptor.claudeEventNames()) {
            if (hooksSection.get(event) instanceof List<?> entries && alreadyRegistered(castList(entries), descriptor.hookType())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(List<?> list) {
        return (List<Object>) list;
    }

    /** True if {@code settings.json} contains at least one of our hook commands. */
    public static boolean isRegistered(Path settingsFile) {
        if (!(readJsonObjectOrEmpty(settingsFile).get(HOOKS_KEY) instanceof Map<?, ?> hooksSection)) {
            return false;
        }
        for (Object eventEntries : hooksSection.values()) {
            if (!(eventEntries instanceof List<?> entries)) continue;
            for (Object entryObj : entries) {
                if (entryObj instanceof Map<?, ?> entry && entry.get("hooks") instanceof List<?> commands
                    && commands.stream().anyMatch(HookRegistrar::isOurCommand)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes every hook command that runs our jar, leaving all other hooks and settings
     * untouched. Entry wrappers, events and the {@code hooks} block itself are pruned only when
     * removing ours leaves them empty. Never creates the file; doesn't rewrite it when nothing
     * was ours. Returns the number of commands removed.
     */
    @SuppressWarnings("unchecked")
    public static int unregisterAll(Path settingsFile) {
        if (!Files.exists(settingsFile)) {
            return 0;
        }
        Map<String, Object> config;
        try {
            config = readJsonObject(settingsFile);
        } catch (UncheckedIOException e) {
            return 0; // unparsable: leave it exactly as it is
        }
        if (!(config.get(HOOKS_KEY) instanceof Map<?, ?> rawHooks)) {
            return 0;
        }
        Map<String, Object> hooksSection = (Map<String, Object>) rawHooks;

        int removed = 0;
        for (Iterator<Map.Entry<String, Object>> events = hooksSection.entrySet().iterator(); events.hasNext(); ) {
            if (!(events.next().getValue() instanceof List<?> rawEntries)) continue;
            List<Object> entries = (List<Object>) rawEntries;
            for (Iterator<Object> it = entries.iterator(); it.hasNext(); ) {
                if (!(it.next() instanceof Map<?, ?> entry) || !(entry.get("hooks") instanceof List<?> rawCommands)) continue;
                List<Object> commands = (List<Object>) rawCommands;
                int before = commands.size();
                commands.removeIf(HookRegistrar::isOurCommand);
                removed += before - commands.size();
                if (before > 0 && commands.isEmpty()) {
                    it.remove();
                }
            }
            if (entries.isEmpty()) {
                events.remove();
            }
        }

        if (removed > 0) {
            if (hooksSection.isEmpty()) {
                config.remove(HOOKS_KEY);
            }
            writeJsonObject(settingsFile, config);
        }
        return removed;
    }

    private static boolean isOurCommand(Object commandObj) {
        if (!(commandObj instanceof Map<?, ?> command) || !"java".equals(command.get("command"))) {
            return false;
        }
        if (!(command.get("args") instanceof List<?> args)) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String s && (s.equals(HooksJarLocation.JAR_NAME)
                || s.endsWith("/" + HooksJarLocation.JAR_NAME) || s.endsWith("\\" + HooksJarLocation.JAR_NAME))) {
                return true;
            }
        }
        return false;
    }

    private static Object lastArg(Object commandObj) {
        if (commandObj instanceof Map<?, ?> command && command.get("args") instanceof List<?> args && !args.isEmpty()) {
            return args.get(args.size() - 1);
        }
        return null;
    }

    /**
     * Reads the settings file for a read-modify-write. A file that exists but cannot be parsed is an
     * error, never an empty map: treating it as empty would make the caller rewrite the file with only
     * our entries and silently destroy the user's other settings (comments and trailing commas in a
     * tool's settings file make this likely).
     */
    private static Map<String, Object> readJsonObject(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse " + file + " (" + firstLine(e.getMessage())
                + "). It was left untouched: fix or remove the syntax error and run the installer again.", e);
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "invalid JSON";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    /** Read-only callers (detection) treat an unparsable file as "nothing registered". */
    private static Map<String, Object> readJsonObjectOrEmpty(Path file) {
        try {
            return readJsonObject(file);
        } catch (UncheckedIOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static void writeJsonObject(Path file, Map<String, Object> content) {
        try {
            Files.createDirectories(file.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content) + "\n";
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
