package dev.dorrian.agenticskillscli.detect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledMcpServerDetectorTest {

    @Test
    void returnsEmptyForAnUnknownToolKey() {
        assertTrue(InstalledMcpServerDetector.detect(List.of("some-server"), "unknown-tool").isEmpty());
    }

    @Test
    void claudeBranchNeverThrowsEvenWhenServerIsNotRegistered() {
        assertTrue(InstalledMcpServerDetector.detect(List.of("definitely-not-registered-xyz"), "claude").isEmpty());
    }
}
