package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntigravityHookRegistrarTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode ours(Path file) throws Exception {
        return JSON.readTree(file.toFile()).path(AntigravityHookRegistrar.HOOK_NAME);
    }

    @Test
    void writesTheSchemaAntigravityShipsGroupedForToolEventsAndFlatForTheOthers(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config/hooks.json");
        Path jar = tmp.resolve("hooks dir/agentic-skills-hooks.jar"); // a space: the command must quote the path

        AntigravityHookRegistrar.register(file, jar, false, false, false);

        JsonNode hook = ours(file);
        assertTrue(hook.path("enabled").asBoolean());
        // PostToolUse: grouped {matcher, hooks}
        JsonNode post = hook.path("PostToolUse").get(0);
        assertEquals("*", post.path("matcher").asText());
        JsonNode handler = post.path("hooks").get(0);
        assertEquals("command", handler.path("type").asText());
        assertEquals("java -jar \"" + jar.toAbsolutePath() + "\" agy post-tool-use", handler.path("command").asText());
        assertEquals(30, handler.path("timeout").asInt());
        // Stop: flat list of handlers (no matcher wrapper)
        JsonNode stop = hook.path("Stop").get(0);
        assertFalse(stop.has("matcher"));
        assertTrue(stop.path("command").asText().endsWith("\" agy stop"));
        // the guard (PreToolUse) and the context hook are opt-in
        assertFalse(hook.has("PreToolUse"));
        assertFalse(hook.has("PreInvocation"));
    }

    @Test
    void verifyAndContextAreOptInAndTheStopHandlerGetsTheLongTimeout(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        Path jar = tmp.resolve("j.jar");

        AntigravityHookRegistrar.register(file, jar, false, true, true);
        JsonNode hook = ours(file);

        assertTrue(hook.path("Stop").get(0).path("command").asText().endsWith("agy stop --verify"));
        assertEquals(600, hook.path("Stop").get(0).path("timeout").asInt());
        JsonNode context = hook.path("PreInvocation").get(0);
        assertFalse(context.has("matcher"));
        assertTrue(context.path("command").asText().endsWith("agy pre-invocation"));
        assertEquals(10, context.path("timeout").asInt());
    }

    @Test
    void reRegisteringConvergesToTheChosenOptionsAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        Path jar = tmp.resolve("j.jar");

        AntigravityHookRegistrar.register(file, jar, false, true, true);
        AntigravityHookRegistrar.register(file, jar, false, false, false);
        JsonNode hook = ours(file);
        assertFalse(hook.has("PreInvocation"), "an opt-in the user dropped must go away");
        assertTrue(hook.path("Stop").get(0).path("command").asText().endsWith("agy stop"));
        assertEquals(30, hook.path("Stop").get(0).path("timeout").asInt());

        String before = Files.readString(file);
        AntigravityHookRegistrar.register(file, jar, false, false, false);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void theUsersOwnHooksAreNeverTouched(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        String theirs = "{\"lint-checker\":{\"PostToolUse\":[{\"matcher\":\"run_command\",\"hooks\":[{\"command\":\"./lint.sh\",\"timeout\":10}]}]},"
            + "\"safety-gate\":{\"enabled\":false}}";
        Files.writeString(file, theirs);

        AntigravityHookRegistrar.register(file, tmp.resolve("j.jar"), false, false, false);
        JsonNode root = JSON.readTree(file.toFile());
        assertEquals(JSON.readTree(theirs).path("lint-checker"), root.path("lint-checker"));
        assertEquals(JSON.readTree(theirs).path("safety-gate"), root.path("safety-gate"));
        assertTrue(root.has(AntigravityHookRegistrar.HOOK_NAME));

        assertEquals(1, AntigravityHookRegistrar.unregister(file));
        JsonNode after = JSON.readTree(file.toFile());
        assertFalse(after.has(AntigravityHookRegistrar.HOOK_NAME));
        assertEquals(JSON.readTree(theirs), after, "unregister removes only our key");
    }

    @Test
    void unregisterDeletesTheFileOnlyWhenWeWereTheOnlyEntry(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        AntigravityHookRegistrar.register(file, tmp.resolve("j.jar"), false, false, false);
        assertTrue(AntigravityHookRegistrar.isRegistered(file));

        assertEquals(1, AntigravityHookRegistrar.unregister(file));
        assertFalse(Files.exists(file));
        assertFalse(AntigravityHookRegistrar.isRegistered(file));
        assertEquals(0, AntigravityHookRegistrar.unregister(file));
    }

    @Test
    void anUnparsableFileIsNeverRewrittenOrWiped(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        String original = "{\n  // my hooks\n  \"mine\": {},\n}\n";
        Files.writeString(file, original);

        assertThrows(UncheckedIOException.class, () -> AntigravityHookRegistrar.register(file, tmp.resolve("j.jar"), false, false, false));
        assertEquals(original, Files.readString(file));
        assertEquals(0, AntigravityHookRegistrar.unregister(file));
        assertEquals(original, Files.readString(file));
        assertFalse(AntigravityHookRegistrar.isRegistered(file));
    }

    @Test
    void theGuardRegistersOnPreToolUseForTheToolsItReadsAndIsOptIn(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hooks.json");
        AntigravityHookRegistrar.register(file, tmp.resolve("j.jar"), true, false, false);

        JsonNode pre = ours(file).path("PreToolUse").get(0);
        assertEquals("run_command|view_file|write_to_file|replace_file_content|multi_replace_file_content", pre.path("matcher").asText());
        JsonNode handler = pre.path("hooks").get(0);
        assertTrue(handler.path("command").asText().endsWith("\" agy pre-tool-use"));
        assertEquals(10, handler.path("timeout").asInt());

        AntigravityHookRegistrar.register(file, tmp.resolve("j.jar"), false, false, false);
        assertFalse(ours(file).has("PreToolUse"), "dropping the guard must remove it");
    }
}
