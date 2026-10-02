package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiHookRegistrarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_HOOKS = """
        {"theme":"dark","hooks":{"AfterTool":[{"matcher":"write_file","hooks":[
          {"name":"mine","type":"command","command":"./my.sh","timeout":5000}]}]}}
        """;

    @TempDir
    Path tmp;

    private JsonNode read(Path f) throws Exception {
        return MAPPER.readTree(f.toFile());
    }

    @Test
    void registersMappedEventsWithMillisecondTimeouts() throws Exception {
        Path settings = tmp.resolve(".gemini/settings.json");
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));

        JsonNode hooks = read(settings).get("hooks");
        for (String ev : new String[]{"AfterTool", "SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent"}) {
            assertEquals(1, hooks.get(ev).size(), ev);
        }
        assertEquals(5, hooks.size());
        JsonNode cmd = hooks.get("AfterTool").get(0).get("hooks").get(0);
        assertEquals("java -jar \"/h/agentic-skills-hooks.jar\" post-tool-use", cmd.get("command").asText());
        assertEquals("command", cmd.get("type").asText());
        assertEquals(10000, cmd.get("timeout").asInt());
        assertTrue(GeminiHookRegistrar.isRegistered(settings));
    }

    @Test
    void preservesUserHooksAndSettingsAndIsIdempotent() throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, USER_HOOKS);
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));
        String once = Files.readString(settings);
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));
        assertEquals(once, Files.readString(settings));

        JsonNode root = read(settings);
        assertEquals("dark", root.get("theme").asText());
        JsonNode afterTool = root.get("hooks").get("AfterTool");
        assertEquals(2, afterTool.size());
        assertEquals("./my.sh", afterTool.get(0).get("hooks").get(0).get("command").asText());
    }

    @Test
    void convergesOurEntryToNewJarPathWithoutDuplicating() throws Exception {
        Path settings = tmp.resolve("settings.json");
        GeminiHookRegistrar.register(settings, Path.of("/old/agentic-skills-hooks.jar"));
        GeminiHookRegistrar.register(settings, Path.of("/new/agentic-skills-hooks.jar"));
        JsonNode stop = read(settings).get("hooks").get("AfterAgent");
        assertEquals(1, stop.size());
        assertEquals("java -jar \"/new/agentic-skills-hooks.jar\" stop",
            stop.get(0).get("hooks").get(0).get("command").asText());
    }

    @Test
    void unregisterRemovesOnlyOursAndPrunesEmptyContainers() throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, USER_HOOKS);
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));

        assertEquals(5, GeminiHookRegistrar.unregister(settings));
        assertFalse(GeminiHookRegistrar.isRegistered(settings));
        JsonNode root = read(settings);
        assertEquals(1, root.get("hooks").size());
        assertEquals("./my.sh", root.get("hooks").get("AfterTool").get(0).get("hooks").get(0).get("command").asText());
        assertEquals(0, GeminiHookRegistrar.unregister(settings));
    }

    @Test
    void unregisterDropsHooksBlockWhenOnlyOursAndNeverCreatesFile() throws Exception {
        Path missing = tmp.resolve("nope/settings.json");
        assertEquals(0, GeminiHookRegistrar.unregister(missing));
        assertFalse(Files.exists(missing));

        Path settings = tmp.resolve("settings.json");
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));
        GeminiHookRegistrar.unregister(settings);
        assertFalse(read(settings).has("hooks"));
    }

    @Test
    void userHookWithSimilarCommandIsNotMistakenForOurs() throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, """
            {"hooks":{"AfterAgent":[{"matcher":"*","hooks":[
              {"type":"command","command":"java -jar /x/other.jar stop"}]}]}}
            """);
        assertFalse(GeminiHookRegistrar.isRegistered(settings));
        GeminiHookRegistrar.register(settings, Path.of("/h/agentic-skills-hooks.jar"));
        assertEquals(2, read(settings).get("hooks").get("AfterAgent").size());
    }
}
