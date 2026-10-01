package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UninstallWizardTest {

    @Test
    void capitalizesOnlyTheFirstCharacter() {
        assertEquals("Engram", UninstallWizard.capitalize("engram"));
        assertEquals("Figma-mcp", UninstallWizard.capitalize("figma-mcp"));
        assertEquals("Context7", UninstallWizard.capitalize("context7"));
    }

    @Test
    void handlesEmptyString() {
        assertEquals("", UninstallWizard.capitalize(""));
    }

    @Test
    void leavesAlreadyCapitalizedStringUnchanged() {
        assertEquals("Already", UninstallWizard.capitalize("Already"));
    }
}
