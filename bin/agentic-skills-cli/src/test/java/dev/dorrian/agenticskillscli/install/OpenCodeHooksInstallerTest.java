package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeHooksInstallerTest {

    @TempDir
    Path tmp;

    Path plugins;
    Path target;
    OpenCodeHooksInstaller installer;

    @BeforeEach
    void setUp() throws Exception {
        Path bundled = tmp.resolve("bundle.jar");
        Files.writeString(bundled, "jar");
        plugins = tmp.resolve(".config/opencode/plugins");
        target = tmp.resolve("hooks/agentic-skills-hooks.jar");
        installer = new OpenCodeHooksInstaller(() -> bundled, () -> target, () -> plugins);
    }

    private String plugin() throws Exception {
        return Files.readString(plugins.resolve("agentic-skills-hooks.js"));
    }

    @Test
    void writesPluginWithJarPathBakedAndGuardOff() throws Exception {
        installer.install(HookInstallOptions.NONE);
        String js = plugin();
        assertTrue(Files.isRegularFile(target));
        assertTrue(js.contains("const JAR = \"" + target.toAbsolutePath() + "\""));
        assertTrue(js.contains("const GUARD = false"));
        assertFalse(js.contains("__AGENTIC_SKILLS"));
        assertTrue(installer.isRegistered());
    }

    @Test
    void guardFlagIsBakedWhenRequestedAndReinstallConverges() throws Exception {
        installer.install(new HookInstallOptions(true, false, false));
        assertTrue(plugin().contains("const GUARD = true"));
        installer.install(HookInstallOptions.NONE);
        assertTrue(plugin().contains("const GUARD = false"));
    }

    @Test
    void installIsIdempotent() throws Exception {
        installer.install(HookInstallOptions.NONE);
        String first = plugin();
        installer.install(HookInstallOptions.NONE);
        assertEquals(first, plugin());
    }

    @Test
    void uninstallRemovesOnlyOurPlugin() throws Exception {
        Files.createDirectories(plugins);
        Path other = plugins.resolve("other.js");
        Files.writeString(other, "export const X = 1");
        installer.install(HookInstallOptions.NONE);

        assertEquals(1, installer.uninstall());
        assertFalse(installer.isRegistered());
        assertTrue(Files.exists(other));
        assertEquals(0, installer.uninstall());
    }

    @Test
    void doesNotTreatAUserFileWithSameNameAsOurs() throws Exception {
        Files.createDirectories(plugins);
        Path mine = plugins.resolve("agentic-skills-hooks.js");
        Files.writeString(mine, "// my own plugin");
        assertFalse(installer.isRegistered());
        assertEquals(0, installer.uninstall());
        assertTrue(Files.exists(mine));
    }

    @Test
    void currentOptionsRoundTripAndReinstallIsIdempotent() throws Exception {
        for (boolean guard : new boolean[] {true, false}) {
            HookInstallOptions x = new HookInstallOptions(guard, false, false);
            installer.install(x);
            assertEquals(x, installer.currentOptions());
            String before = plugin();
            installer.install(installer.currentOptions());
            assertEquals(before, plugin());
        }
    }

    @Test
    void currentOptionsAreNoneWhenMissingOrUnrecognisable() throws Exception {
        assertEquals(HookInstallOptions.NONE, installer.currentOptions());
        Files.createDirectories(plugins);
        Files.writeString(plugins.resolve("agentic-skills-hooks.js"), "const GUARD = true\n");
        assertEquals(HookInstallOptions.NONE, installer.currentOptions());
        Files.writeString(plugins.resolve("agentic-skills-hooks.js"), "// agentic-skills-hooks plugin\nconst GUARD = maybe\n");
        assertEquals(HookInstallOptions.NONE, installer.currentOptions());
    }
}
