package dev.dorrian.agenticskillscli.install;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.config.HookRegistrar;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HooksUpgradeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OLD_SETTINGS = """
        {
          "model": "opus",
          "hooks": {
            "Stop": [
              { "matcher": "", "hooks": [ { "type": "command", "command": "java",
                  "args": ["-jar", "/old/place/agentic-skills-hooks.jar", "stop"] } ] },
              { "matcher": "", "hooks": [ { "type": "command", "command": "java",
                  "args": ["-jar", "/home/me/own-tool.jar", "stop"] } ] }
            ],
            "PreToolUse": [
              { "matcher": "Bash", "hooks": [ { "type": "command", "command": "my-linter" } ] },
              { "matcher": "", "hooks": [ { "type": "command", "command": "java",
                  "args": ["-jar", "/old/place/agentic-skills-hooks.jar", "guard"] } ] }
            ]
          },
          "permissions": { "allow": ["Bash(ls)"] }
        }
        """;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Path settings, String event) throws Exception {
        Map<String, Object> root = MAPPER.readValue(settings.toFile(), new TypeReference<>() {});
        Map<String, Object> hooks = (Map<String, Object>) root.get("hooks");
        return (List<Map<String, Object>>) hooks.get(event);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCommand(Map<String, Object> entry) {
        return ((List<Map<String, Object>>) entry.get("hooks")).get(0);
    }

    @Test
    void upgradeRefreshesOurOldShapeEntriesAndKeepsTheUsersOwn(@TempDir Path tmp) throws Exception {
        Path settings = Files.writeString(tmp.resolve("settings.json"), OLD_SETTINGS);
        Path jar = tmp.resolve("new").resolve("agentic-skills-hooks.jar");

        HookRegistrar.registerAll(settings, jar, new HookInstallOptions(true, false, false));

        List<Map<String, Object>> stop = entries(settings, "Stop");
        assertEquals(2, stop.size(), "ours updated in place, user's kept, no duplicate added");
        assertEquals(List.of("-jar", jar.toString(), "stop"), firstCommand(stop.get(0)).get("args"));
        assertEquals(30, firstCommand(stop.get(0)).get("timeout"));
        assertEquals(List.of("-jar", "/home/me/own-tool.jar", "stop"), firstCommand(stop.get(1)).get("args"));

        List<Map<String, Object>> pre = entries(settings, "PreToolUse");
        assertEquals(2, pre.size());
        assertEquals("my-linter", firstCommand(pre.get(0)).get("command"));
        assertEquals("Bash|Read|Edit|Write|MultiEdit|NotebookEdit", pre.get(1).get("matcher"));
        assertEquals(10, firstCommand(pre.get(1)).get("timeout"));
        assertEquals(List.of("-jar", jar.toString(), "guard"), firstCommand(pre.get(1)).get("args"));

        Map<String, Object> root = MAPPER.readValue(settings.toFile(), new TypeReference<>() {});
        assertEquals("opus", root.get("model"));
        assertTrue(root.containsKey("permissions"));
        assertTrue(Files.readString(settings).indexOf("\"model\"") < Files.readString(settings).indexOf("\"hooks\""));
    }

    @Test
    void droppingAnOptInRemovesOnlyOurEntryAndPrunesEmptyEvents(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("settings.json");
        Path jar = tmp.resolve("agentic-skills-hooks.jar");
        HookRegistrar.registerAll(settings, jar, new HookInstallOptions(true, true, true));
        assertEquals(2, entries(settings, "SessionStart").size());
        assertEquals(2, entries(settings, "Stop").size());

        HookRegistrar.registerAll(settings, jar, HookInstallOptions.NONE);

        String json = Files.readString(settings);
        assertFalse(json.contains("\"guard\""));
        assertFalse(json.contains("\"verify\""));
        assertFalse(json.contains("\"context\""));
        assertFalse(json.contains("PreToolUse"), "event we emptied is pruned");
        assertEquals(1, entries(settings, "SessionStart").size());
        assertEquals(1, entries(settings, "Stop").size());
    }

    @Test
    void optOutDoesNotTouchUsersOwnHookOnTheSameEvent(@TempDir Path tmp) throws Exception {
        Path settings = Files.writeString(tmp.resolve("settings.json"), OLD_SETTINGS);
        HookRegistrar.registerAll(settings, tmp.resolve("agentic-skills-hooks.jar"), HookInstallOptions.NONE);

        List<Map<String, Object>> pre = entries(settings, "PreToolUse");
        assertEquals(1, pre.size());
        assertEquals("my-linter", firstCommand(pre.get(0)).get("command"));
        assertEquals(2, entries(settings, "Stop").size());
    }

    @Test
    void secondIdenticalRunLeavesTheFileByteIdentical(@TempDir Path tmp) throws Exception {
        Path settings = Files.writeString(tmp.resolve("settings.json"), OLD_SETTINGS);
        Path jar = tmp.resolve("agentic-skills-hooks.jar");
        HookInstallOptions opts = new HookInstallOptions(true, true, false);

        HookRegistrar.registerAll(settings, jar, opts);
        byte[] first = Files.readAllBytes(settings);
        HookRegistrar.registerAll(settings, jar, opts);

        assertArrayEquals(first, Files.readAllBytes(settings));
    }

    @Test
    void reinstallOfAnIdenticalJarLeavesItUntouched(@TempDir Path tmp) throws Exception {
        Path bundled = Files.writeString(tmp.resolve("bundled.jar"), "same-bytes");
        Path target = tmp.resolve("hooks").resolve("agentic-skills-hooks.jar");
        Path settings = tmp.resolve("settings.json");

        HooksInstaller.installAndRegister(bundled, target, settings);
        var before = Files.getLastModifiedTime(target);
        HooksInstaller.installAndRegister(bundled, target, settings);

        assertEquals(before, Files.getLastModifiedTime(target));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
    }
}
