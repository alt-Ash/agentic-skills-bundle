package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRegistrarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersAllFiveHookTypesUnderCorrectClaudeEventNames(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Path jarPath = tempDir.resolve("agentic-skills-hooks.jar");

        HookRegistrar.registerAll(settingsFile, jarPath);

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) written.get("hooks");

        assertTrue(hooks.containsKey("PostToolUse"));
        assertTrue(hooks.containsKey("PostToolUseFailure"));
        assertTrue(hooks.containsKey("UserPromptSubmit"));
        assertTrue(hooks.containsKey("Stop"));
        assertTrue(hooks.containsKey("SessionStart"));
        assertTrue(hooks.containsKey("SessionEnd"));

        assertCommandArgsEndWith(hooks, "PostToolUse", "post-tool-use");
        assertCommandArgsEndWith(hooks, "SessionStart", "session");
        assertCommandArgsEndWith(hooks, "SessionEnd", "session");
    }

    @SuppressWarnings("unchecked")
    private static void assertCommandArgsEndWith(Map<String, Object> hooks, String event, String expectedHookType) {
        List<Object> entries = (List<Object>) hooks.get(event);
        Map<String, Object> entry = (Map<String, Object>) entries.get(0);
        List<Object> commands = (List<Object>) entry.get("hooks");
        Map<String, Object> command = (Map<String, Object>) commands.get(0);
        List<Object> args = (List<Object>) command.get("args");
        assertEquals(expectedHookType, args.get(args.size() - 1));
        assertEquals("java", command.get("command"));
        assertEquals("command", command.get("type"));
    }

    @Test
    void isIdempotentAndDoesNotDuplicateEntriesOnASecondRun(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Path jarPath = tempDir.resolve("agentic-skills-hooks.jar");

        HookRegistrar.registerAll(settingsFile, jarPath);
        HookRegistrar.registerAll(settingsFile, jarPath);

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) written.get("hooks");
        @SuppressWarnings("unchecked")
        List<Object> postToolUseEntries = (List<Object>) hooks.get("PostToolUse");
        assertEquals(1, postToolUseEntries.size());
    }

    @Test
    void doesNotClobberAPreExistingUnrelatedHookEntryForTheSameEvent(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Path jarPath = tempDir.resolve("agentic-skills-hooks.jar");

        Files.writeString(settingsFile, """
            {
              "hooks": {
                "PostToolUse": [
                  { "matcher": "Bash", "hooks": [ { "type": "command", "command": "my-other-tool", "args": ["custom"] } ] }
                ]
              }
            }
            """);

        HookRegistrar.registerAll(settingsFile, jarPath);

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) written.get("hooks");
        @SuppressWarnings("unchecked")
        List<Object> postToolUseEntries = (List<Object>) hooks.get("PostToolUse");

        assertEquals(2, postToolUseEntries.size()); // the pre-existing entry plus our new one
    }

    // ─── unregister ─────────────────────────────────────────────────────────

    @Test
    void unregisterRemovesOnlyOurHooksAndKeepsEverythingElse(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Path jarPath = tempDir.resolve(".agentic-skills/hooks/agentic-skills-hooks.jar");
        Files.writeString(settingsFile, """
            {
              "model": "opus",
              "hooks": {
                "PostToolUse": [
                  { "matcher": "Bash", "hooks": [ { "type": "command", "command": "my-other-tool", "args": ["custom"] } ] }
                ],
                "Stop": [
                  { "matcher": "", "hooks": [ { "type": "command", "command": "java", "args": ["-jar", "/opt/my-own.jar", "stop"] } ] }
                ]
              }
            }
            """);
        HookRegistrar.registerAll(settingsFile, jarPath);
        assertTrue(HookRegistrar.isRegistered(settingsFile));

        int removed = HookRegistrar.unregisterAll(settingsFile);

        assertEquals(6, removed); // one per Claude event across the 5 hook types
        assertFalse(HookRegistrar.isRegistered(settingsFile));
        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        assertEquals("opus", written.get("model"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) written.get("hooks");
        assertEquals(java.util.Set.of("PostToolUse", "Stop"), hooks.keySet()); // events left empty are pruned
        assertEquals(1, ((List<?>) hooks.get("PostToolUse")).size());
        // A user's own `java -jar ... stop` hook is not ours: it must survive.
        assertEquals(1, ((List<?>) hooks.get("Stop")).size());
        assertTrue(MAPPER.writeValueAsString(hooks.get("Stop")).contains("/opt/my-own.jar"));
    }

    @Test
    void unregisterDropsTheHooksBlockWhenNothingElseIsInIt(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, "{ \"model\": \"opus\" }");
        HookRegistrar.registerAll(settingsFile, tempDir.resolve("agentic-skills-hooks.jar"));

        HookRegistrar.unregisterAll(settingsFile);

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        assertEquals(Map.of("model", "opus"), written);
    }

    @Test
    void unregisterRemovesOurCommandFromAMixedEntryButKeepsTheEntry(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, """
            { "hooks": { "Stop": [ { "matcher": "", "hooks": [
                { "type": "command", "command": "notify-send", "args": ["done"] },
                { "type": "command", "command": "java", "args": ["-jar", "/home/u/.agentic-skills/hooks/agentic-skills-hooks.jar", "stop"] }
            ] } ] } }
            """);

        assertEquals(1, HookRegistrar.unregisterAll(settingsFile));

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        String json = MAPPER.writeValueAsString(written);
        assertTrue(json.contains("notify-send"));
        assertFalse(json.contains("agentic-skills-hooks.jar"));
    }

    @Test
    void unregisterIsANoOpWithoutASettingsFileOrOurHooks(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("settings.json");
        assertEquals(0, HookRegistrar.unregisterAll(missing));
        assertFalse(Files.exists(missing)); // never creates the file

        Files.writeString(missing, "{ \"hooks\": {} }");
        String before = Files.readString(missing);
        assertEquals(0, HookRegistrar.unregisterAll(missing));
        assertEquals(before, Files.readString(missing)); // untouched when nothing was ours
    }

    @Test
    void aUsersOwnJavaHookEndingInTheSameHookTypeDoesNotBlockRegistration(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, """
            { "hooks": { "Stop": [ { "matcher": "", "hooks": [
                { "type": "command", "command": "java", "args": ["-jar", "/opt/my-own.jar", "stop"] } ] } ] } }
            """);

        HookRegistrar.registerAll(settingsFile, tempDir.resolve("agentic-skills-hooks.jar"));

        Map<String, Object> written = MAPPER.readValue(settingsFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) written.get("hooks");
        assertEquals(2, ((List<?>) hooks.get("Stop")).size()); // theirs + ours
    }
}
