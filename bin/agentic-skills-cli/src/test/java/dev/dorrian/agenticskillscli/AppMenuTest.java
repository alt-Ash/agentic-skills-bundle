package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppMenuTest {

    @Test
    void upgradeIsFirstAndMapsToUpgrade() {
        assertEquals("upgrade", App.modeFor(App.menuChoices().get(0)));
    }

    @Test
    void remainingEntriesMapInOrder() {
        List<String> choices = App.menuChoices();
        assertEquals(4, choices.size());
        assertEquals("install", App.modeFor(choices.get(1)));
        assertEquals("update-token", App.modeFor(choices.get(2)));
        assertEquals("uninstall", App.modeFor(choices.get(3)));
    }

    @Test
    void quickInstallNoLongerExists() {
        for (String choice : App.menuChoices()) {
            assertFalse(choice.toLowerCase().contains("quick install"), choice);
        }
    }
}
