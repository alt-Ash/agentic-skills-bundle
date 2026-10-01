package dev.dorrian.agenticskillscli.detect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledToolDetectorTest {

    @Test
    void returnsAnEntryForEveryRegisteredTool() {
        Map<String, Boolean> detected = InstalledToolDetector.detect();
        assertEquals(8, detected.size());
        assertTrue(detected.containsKey("opencode"));
        assertTrue(detected.containsKey("zed"));
    }
}
