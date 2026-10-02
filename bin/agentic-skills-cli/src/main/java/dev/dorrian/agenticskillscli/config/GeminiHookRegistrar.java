package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

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
 * Registers the analytics hooks in Gemini CLI's {@code ~/.gemini/settings.json}. Schema (per the
 * Gemini CLI hooks docs): {@code {"hooks": {"<Event>": [{"matcher": "...", "hooks": [{"name",
 * "type": "command", "command": "<shell string>", "timeout": <MILLISECONDS>}]}]}}}. Unlike Claude
 * Code, {@code command} is a single shell string (no {@code args}) and {@code timeout} is in ms.
 *
 * <p>Only the analytics hooks that map cleanly to Gemini events are registered; guard/verify/context
 * are deliberately not (the guard matches Claude tool names, which differ in Gemini).
 *
 * <p>Our entries are recognised only by the jar name inside {@code command}; the user's own hooks
 * are never touched. {@link #register} converges our entries to the given jar path (idempotent).
 */
public final class GeminiHookRegistrar {

    /** One registration: Gemini event to our hook type. */
    public record Mapping(String event, String hookType) {
    }

    /** Gemini event to hook type. AfterTool is the only Gemini event that carries a tool result. */
    public static final List<Mapping> MAPPINGS = List.of(
        new Mapping("AfterTool", "post-tool-use"),
        new Mapping("SessionStart", "session"),
        new Mapping("SessionEnd", "session"),
        new Mapping("BeforeAgent", "user-prompt-submit"),
        new Mapping("AfterAgent", "stop")
    );

    /** Gemini hook timeouts are milliseconds. */
    static final int TIMEOUT_MS = 10_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOOKS_KEY = "hooks";

    private GeminiHookRegistrar() {
    }

    @SuppressWarnings("unchecked")
    public static void register(Path settingsFile, Path hooksJarPath) {
        Map<String, Object> config = readJsonObject(settingsFile);
        Map<String, Object> hooksSection = config.get(HOOKS_KEY) instanceof Map<?, ?> m
            ? (Map<String, Object>) m : new LinkedHashMap<>();
        config.put(HOOKS_KEY, hooksSection);

        for (Mapping mapping : MAPPINGS) {
            Object existing = hooksSection.get(mapping.event());
            List<Object> entries = existing instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
            hooksSection.put(mapping.event(), entries);
            Map<String, Object> ours = findOurs(entries, mapping.hookType());
            if (ours == null) {
                ours = new LinkedHashMap<>();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("matcher", "*");
                entry.put("hooks", new ArrayList<>(List.of(ours)));
                entries.add(entry);
            }
            ours.put("name", "agentic-skills-" + mapping.hookType());
            ours.put("type", "command");
            ours.put("command", commandFor(hooksJarPath, mapping.hookType()));
            ours.put("timeout", TIMEOUT_MS);
        }
        writeJsonObject(settingsFile, config);
    }

    public static boolean isRegistered(Path settingsFile) {
        if (!(readJsonObject(settingsFile).get(HOOKS_KEY) instanceof Map<?, ?> hooksSection)) {
            return false;
        }
        for (Object eventEntries : hooksSection.values()) {
            if (!(eventEntries instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> e && e.get("hooks") instanceof List<?> cmds
                    && cmds.stream().anyMatch(GeminiHookRegistrar::isOurCommand)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes only our commands, pruning emptied entries, events and the {@code hooks} block. Returns count removed. */
    @SuppressWarnings("unchecked")
    public static int unregister(Path settingsFile) {
        if (!Files.exists(settingsFile)) {
            return 0;
        }
        Map<String, Object> config = readJsonObject(settingsFile);
        if (!(config.get(HOOKS_KEY) instanceof Map<?, ?> raw)) {
            return 0;
        }
        Map<String, Object> hooksSection = (Map<String, Object>) raw;
        int removed = 0;
        for (Iterator<Map.Entry<String, Object>> events = hooksSection.entrySet().iterator(); events.hasNext(); ) {
            if (!(events.next().getValue() instanceof List<?> rawEntries)) continue;
            List<Object> entries = (List<Object>) rawEntries;
            for (Iterator<Object> it = entries.iterator(); it.hasNext(); ) {
                if (!(it.next() instanceof Map<?, ?> entry) || !(entry.get("hooks") instanceof List<?> rawCmds)) continue;
                List<Object> cmds = (List<Object>) rawCmds;
                int before = cmds.size();
                cmds.removeIf(GeminiHookRegistrar::isOurCommand);
                removed += before - cmds.size();
                if (before > 0 && cmds.isEmpty()) {
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

    static String commandFor(Path jar, String hookType) {
        return "java -jar \"" + jar + "\" " + hookType;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findOurs(List<Object> entries, String hookType) {
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e) || !(e.get("hooks") instanceof List<?> cmds)) continue;
            for (Object cmd : cmds) {
                if (isOurCommand(cmd) && hookType.equals(hookTypeOf(cmd))) {
                    return (Map<String, Object>) cmd;
                }
            }
        }
        return null;
    }

    private static boolean isOurCommand(Object cmdObj) {
        if (!(cmdObj instanceof Map<?, ?> cmd) || !(cmd.get("command") instanceof String s)) {
            return false;
        }
        String jar = HooksJarLocation.JAR_NAME;
        return s.contains("/" + jar) || s.contains("\\" + jar) || s.contains("\"" + jar) || s.contains(" " + jar);
    }

    private static String hookTypeOf(Object cmdObj) {
        String s = ((String) ((Map<?, ?>) cmdObj).get("command")).trim();
        return s.substring(s.lastIndexOf(' ') + 1);
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
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
