package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiHooksInstallerTest {

    @TempDir
    Path tmp;

    @Test
    void installCopiesJarRegistersAndUninstallLeavesJar() throws Exception {
        Path bundled = tmp.resolve("bundle.jar");
        Files.writeString(bundled, "jar");
        Path target = tmp.resolve("hooks/agentic-skills-hooks.jar");
        Path settings = tmp.resolve(".gemini/settings.json");
        GeminiHooksInstaller installer = new GeminiHooksInstaller(() -> bundled, () -> target, () -> settings);

        assertFalse(installer.isRegistered());
        Path installed = installer.install(new HookInstallOptions(true, true, true));
        assertEquals(target, installed);
        assertTrue(Files.isRegularFile(target));
        assertTrue(installer.isRegistered());
        // guard/verify/context are never registered for Gemini
        String json = Files.readString(settings);
        assertFalse(json.contains(" guard"));
        assertFalse(json.contains(" verify"));
        assertFalse(json.contains(" context"));

        assertEquals(5, installer.uninstall());
        assertFalse(installer.isRegistered());
        assertTrue(Files.exists(target));
    }

    @Test
    void missingBundledJarRegistersNothing() {
        Path settings = tmp.resolve("settings.json");
        GeminiHooksInstaller installer = new GeminiHooksInstaller(
            () -> tmp.resolve("absent.jar"), () -> tmp.resolve("t/agentic-skills-hooks.jar"), () -> settings);
        try {
            installer.install(HookInstallOptions.NONE);
        } catch (IllegalStateException expected) {
            // jar copy fails first
        }
        assertFalse(Files.exists(settings));
    }
}
