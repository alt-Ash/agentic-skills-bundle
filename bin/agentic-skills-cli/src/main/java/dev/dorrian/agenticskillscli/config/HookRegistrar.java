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
 * <p>Merges without clobbering: existing hook entries already present in
 * {@code settings.json} (whether ours from a prior run, or the user's own)
 * are left untouched; only missing {hookType, event} combinations are
 * appended, matching the read-modify-write discipline of {@link
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

    public static void registerAll(Path settingsFile, Path hooksJarPath, HookInstallOptions options) {
        List<HookDescriptor> descriptors = new ArrayList<>(HooksRegistry.ALL);
        descriptors.addAll(options.descriptors());
        Map<String, Object> config = readJsonObject(settingsFile);
        @SuppressWarnings("unchecked")
        Map<String, Object> hooksSection = (Map<String, Object>) config.computeIfAbsent(
            HOOKS_KEY, k -> new LinkedHashMap<String, Object>()
        );

        boolean changed = false;
        for (HookDescriptor descriptor : descriptors) {
            for (String eventName : descriptor.claudeEventNames()) {
                if (registerOne(hooksSection, eventName, descriptor, hooksJarPath)) {
                    changed = true;
                }
            }
        }

        if (changed) {
            writeJsonObject(settingsFile, config);
        }
    }

    /** Returns true if a new entry was appended (i.e. it wasn't already registered). */
    @SuppressWarnings("unchecked")
    private static boolean registerOne(Map<String, Object> hooksSection, String eventName, HookDescriptor descriptor, Path jarPath) {
        String hookType = descriptor.hookType();
        List<Object> eventEntries = (List<Object>) hooksSection.computeIfAbsent(
            eventName, k -> new ArrayList<Object>()
        );

        if (alreadyRegistered(eventEntries, hookType)) {
            return false;
        }

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", "java");
        command.put("args", List.of("-jar", jarPath.toString(), hookType));
        command.put("timeout", descriptor.timeoutSeconds());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("matcher", descriptor.matcher());
        entry.put("hooks", new ArrayList<>(List.of(command)));

        eventEntries.add(entry);
        return true;
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

    /** True if {@code settings.json} contains at least one of our hook commands. */
    public static boolean isRegistered(Path settingsFile) {
        if (!(readJsonObject(settingsFile).get(HOOKS_KEY) instanceof Map<?, ?> hooksSection)) {
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
        Map<String, Object> config = readJsonObject(settingsFile);
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

    private static Map<String, Object> readJsonObject(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
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
