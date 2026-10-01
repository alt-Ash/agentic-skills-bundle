package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers CommandRegistry, CompanionFileRegistry, TemplateRegistry, HooksRegistry. */
class SmallRegistriesTest {

    @Test
    void commandRegistryMapsSkillsAndAgentsToCommandBasenames() {
        assertEquals(List.of("migrate-java"), CommandRegistry.commandsForSkill("java-version-migrator"));
        assertEquals(List.of("security-gate"), CommandRegistry.commandsForSkill("secure-feature-gate"));
        assertEquals(List.of("new-issue"), CommandRegistry.commandsForAgent("issue-architect"));
        assertTrue(CommandRegistry.commandsForSkill("no-such-skill").isEmpty());
    }

    @Test
    void companionFileRegistryHasSecurityAuditorScripts() {
        assertEquals(
            List.of("audit-triage.sh", "security-scan.sh"),
            CompanionFileRegistry.companionsFor("security-auditor")
        );
        assertTrue(CompanionFileRegistry.companionsFor("no-such-agent").isEmpty());
    }

    @Test
    void templateRegistryHasSixFiles() {
        assertEquals(6, TemplateRegistry.FILES.size());
        assertTrue(TemplateRegistry.FILES.contains("CLAUDE.md"));
    }

    @Test
    void hooksRegistryHasFiveEntriesAndSessionMapsToTwoClaudeEvents() {
        assertEquals(5, HooksRegistry.ALL.size());
        HookDescriptor session = HooksRegistry.ALL.stream()
            .filter(h -> h.hookType().equals("session"))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("SessionStart", "SessionEnd"), session.claudeEventNames());

        HookDescriptor postToolUse = HooksRegistry.ALL.stream()
            .filter(h -> h.hookType().equals("post-tool-use"))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("PostToolUse"), postToolUse.claudeEventNames());
    }
}
