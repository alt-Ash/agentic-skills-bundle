package dev.dorrian.agenticskillscli.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the "registered hooks point at a jar nobody copied" bug: after the hook-install
 * step, every jar path written into settings.json must exist on disk.
 */
class HooksInstallerTest {

    @Test
    void everyRegisteredHookPointsAtAJarThatExists(@TempDir Path tmp) throws IOException {
        Path bundledJar = tmp.resolve("dist").resolve("agentic-skills-hooks.jar");
        Files.createDirectories(bundledJar.getParent());
        Files.writeString(bundledJar, "hooks-v1");
        Path targetJar = tmp.resolve("home/.agentic-skills/hooks/agentic-skills-hooks.jar");
        Path settings = tmp.resolve("home/.claude/settings.json");

        HooksInstaller.installAndRegister(bundledJar, targetJar, settings);

        assertEquals("hooks-v1", Files.readString(targetJar));
        JsonNode hooks = new ObjectMapper().readTree(settings.toFile()).path("hooks");
        assertTrue(hooks.size() > 0, "no hooks were registered");
        int checked = 0;
        for (JsonNode eventEntries : hooks) {
            for (JsonNode entry : eventEntries) {
                for (JsonNode cmd : entry.path("hooks")) {
                    Path registered = Path.of(cmd.path("args").get(1).asText());
                    assertTrue(Files.isRegularFile(registered), "registered hook jar missing: " + registered);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void reinstallOverwritesAnOlderHooksJar(@TempDir Path tmp) throws IOException {
        Path bundledJar = tmp.resolve("agentic-skills-hooks.jar");
        Path targetJar = tmp.resolve("hooks/agentic-skills-hooks.jar");
        Path settings = tmp.resolve("settings.json");
        Files.writeString(bundledJar, "hooks-v1");
        HooksInstaller.installAndRegister(bundledJar, targetJar, settings);

        Files.writeString(bundledJar, "hooks-v2");
        HooksInstaller.installAndRegister(bundledJar, targetJar, settings);

        assertEquals("hooks-v2", Files.readString(targetJar));
    }

    @Test
    void missingBundledJarFailsWithoutRegisteringAnything(@TempDir Path tmp) {
        Path settings = tmp.resolve("settings.json");
        assertThrows(IllegalStateException.class, () -> HooksInstaller.installAndRegister(
            tmp.resolve("nope.jar"), tmp.resolve("hooks/agentic-skills-hooks.jar"), settings));
        assertFalse(Files.exists(settings));
    }

    @Test
    void uninstallUnregistersTheHooksThenDeletesTheJar(@TempDir Path tmp) throws IOException {
        Path bundledJar = tmp.resolve("agentic-skills-hooks.jar");
        Path targetJar = tmp.resolve("home/.agentic-skills/hooks/agentic-skills-hooks.jar");
        Path settings = tmp.resolve("home/.claude/settings.json");
        Files.writeString(bundledJar, "hooks-v1");
        HooksInstaller.installAndRegister(bundledJar, targetJar, settings);

        int removed = HooksInstaller.uninstall(targetJar, settings);

        assertEquals(6, removed);
        assertFalse(Files.exists(targetJar));
        assertFalse(Files.exists(targetJar.getParent()), "empty hooks dir should be removed");
        assertFalse(new ObjectMapper().readTree(settings.toFile()).has("hooks"));
    }

    @Test
    void uninstallIsSafeWhenNothingWasInstalled(@TempDir Path tmp) {
        assertEquals(0, HooksInstaller.uninstall(tmp.resolve("hooks/agentic-skills-hooks.jar"), tmp.resolve("settings.json")));
        assertFalse(Files.exists(tmp.resolve("settings.json")));
    }
}
