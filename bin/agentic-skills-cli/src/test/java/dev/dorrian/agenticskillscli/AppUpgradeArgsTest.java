package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppUpgradeArgsTest {

    @Test
    void upgradeAsFirstArgumentKeepsTheRest() {
        assertEquals(List.of("--dry-run"), App.upgradeArgs(new String[]{"upgrade", "--dry-run"}));
    }

    @Test
    void packageRootBeforeUpgradeIsStillRecognisedAndForwarded() {
        assertEquals(List.of("--package-root", ".", "--dry-run"),
            App.upgradeArgs(new String[]{"--package-root", ".", "upgrade", "--dry-run"}));
    }

    @Test
    void otherCommandsAreNotUpgrade() {
        assertNull(App.upgradeArgs(new String[]{}));
        assertNull(App.upgradeArgs(new String[]{"--uninstall"}));
        assertNull(App.upgradeArgs(new String[]{"--package-root", "."}));
        assertNull(App.upgradeArgs(new String[]{"data", "upgrade"}));
    }
}
