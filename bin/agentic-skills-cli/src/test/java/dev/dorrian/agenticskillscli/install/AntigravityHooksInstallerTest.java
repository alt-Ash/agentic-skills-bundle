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

class AntigravityHooksInstallerTest {

    @TempDir
    Path tmp;

    Path hooksFile;
    Path target;
    AntigravityHooksInstaller installer;

    @BeforeEach
    void setUp() throws Exception {
        Path bundled = tmp.resolve("bundle.jar");
        Files.writeString(bundled, "jar");
        hooksFile = tmp.resolve(".gemini/config/hooks.json");
        target = tmp.resolve("hooks/agentic-skills-hooks.jar");
        installer = new AntigravityHooksInstaller(() -> bundled, () -> target, () -> hooksFile);
    }

    @Test
    void installCopiesTheJarAndRegistersIt() throws Exception {
        assertFalse(installer.isRegistered());

        Path installed = installer.install(HookInstallOptions.NONE);

        assertEquals(target, installed);
        assertTrue(Files.isRegularFile(target));
        assertTrue(installer.isRegistered());
        assertTrue(Files.readString(hooksFile).contains(target.toAbsolutePath().toString()));
    }

    @Test
    void guardVerifyAndContextAreAllHonored() throws Exception {
        installer.install(new HookInstallOptions(true, true, true));
        String json = Files.readString(hooksFile);

        assertTrue(json.contains("agy pre-tool-use"));
        assertTrue(json.contains("agy stop --verify"));
        assertTrue(json.contains("agy pre-invocation"));
    }

    @Test
    void uninstallRemovesOnlyOurEntryAndReportsTheCount() throws Exception {
        installer.install(HookInstallOptions.NONE);
        assertEquals(1, installer.uninstall());
        assertFalse(installer.isRegistered());
        assertEquals(0, installer.uninstall());
    }

    @Test
    void anUnparsableHooksFileFailsTheInstallWithoutTouchingIt() throws Exception {
        Files.createDirectories(hooksFile.getParent());
        Files.writeString(hooksFile, "{ not json");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
            () -> installer.install(HookInstallOptions.NONE));
        assertEquals("{ not json", Files.readString(hooksFile));
    }
}
