package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.registry.HookDescriptor;
import dev.dorrian.agenticskillscli.registry.HooksRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
public final class HookRegistrar {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOOKS_KEY = "hooks";

    private HookRegistrar() {
    }

    public static void registerAll(Path settingsFile, Path hooksJarPath) {
        Map<String, Object> config = readJsonObject(settingsFile);
        @SuppressWarnings("unchecked")
        Map<String, Object> hooksSection = (Map<String, Object>) config.computeIfAbsent(
            HOOKS_KEY, k -> new LinkedHashMap<String, Object>()
        );

        boolean changed = false;
        for (HookDescriptor descriptor : HooksRegistry.ALL) {
            for (String eventName : descriptor.claudeEventNames()) {
                if (registerOne(hooksSection, eventName, descriptor.hookType(), hooksJarPath)) {
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
    private static boolean registerOne(Map<String, Object> hooksSection, String eventName, String hookType, Path jarPath) {
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

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("matcher", "");
        entry.put("hooks", List.of(command));

        eventEntries.add(entry);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean alreadyRegistered(List<Object> eventEntries, String hookType) {
        for (Object entryObj : eventEntries) {
            if (!(entryObj instanceof Map<?, ?> entry)) continue;
            Object hooksList = entry.get("hooks");
            if (!(hooksList instanceof List<?> commands)) continue;
            for (Object commandObj : commands) {
                if (!(commandObj instanceof Map<?, ?> command)) continue;
                if (!"java".equals(command.get("command"))) continue;
                Object argsObj = command.get("args");
                if (argsObj instanceof List<?> args && !args.isEmpty() && hookType.equals(args.get(args.size() - 1))) {
                    return true;
                }
            }
        }
        return false;
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
