package dev.dorrian.agenticskillscli.frontmatter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContentTransformerTest {

    private static final String SOURCE = """
        ---
        description: A test agent
        mode: subagent
        model: claude-sonnet
        color: '#FF6B35'
        permission:
          edit: deny
          write: allow
          webfetch: allow
          bash:
            '*': deny
        ---
        Body text here.
        """;

    @Test
    void opencodeReturnsContentUnchanged() {
        assertEquals(SOURCE, AgentContentTransformer.transform(SOURCE, "opencode", "test-agent"));
    }

    @Test
    void claudeMapsDenyPermissionsToDisallowedToolsAndQuotesColor() {
        String result = AgentContentTransformer.transform(SOURCE, "claude", "test-agent");
        assertTrue(result.contains("name: test-agent"));
        assertTrue(result.contains("description: A test agent"));
        assertTrue(result.contains("disallowedTools: Edit, Bash"));
        assertTrue(result.contains("model: claude-sonnet"));
        assertTrue(result.contains("color: \"#FF6B35\""));
        assertTrue(result.contains("Body text here."));
    }

    @Test
    void claudeOmitsDisallowedToolsLineWhenNothingIsDenied() {
        String content = """
            ---
            description: open agent
            permission:
              edit: allow
            ---
            Body.
            """;
        String result = AgentContentTransformer.transform(content, "claude", "open-agent");
        assertTrue(!result.contains("disallowedTools"));
    }

    @Test
    void vscodeMapsAllowPermissionsToToolsListAndDerivesUserInvocable() {
        String result = AgentContentTransformer.transform(SOURCE, "vscode", "test-agent");
        assertTrue(result.contains("user-invocable: false")); // mode: subagent
        // edit:deny is excluded; write:allow has no VS Code tool-map entry so it's
        // dropped too (matches the original's toolMap, which has no "write" key);
        // only webfetch:allow maps to a tool.
        assertTrue(result.contains("tools: ['web/fetch']"));
        assertTrue(result.contains("model: claude-sonnet"));
    }

    @Test
    void vscodePrimaryModeIsUserInvocableTrue() {
        String content = """
            ---
            description: primary agent
            mode: primary
            ---
            Body.
            """;
        String result = AgentContentTransformer.transform(content, "vscode", "p");
        assertTrue(result.contains("user-invocable: true"));
    }

    @Test
    void vscodeDefaultsToUserInvocableTrueWhenModeAbsent() {
        String content = """
            ---
            description: no mode agent
            ---
            Body.
            """;
        String result = AgentContentTransformer.transform(content, "vscode", "n");
        assertTrue(result.contains("user-invocable: true"));
    }

    @Test
    void passesThroughContentUnchangedWhenNoFrontmatterBlockPresent() {
        String noFrontmatter = "# Just a heading\n\nNo frontmatter here.";
        assertEquals(noFrontmatter, AgentContentTransformer.transform(noFrontmatter, "claude", "x"));
    }

    @Test
    void cursorWindsurfZedReturnUnchangedAsAGuard() {
        assertEquals(SOURCE, AgentContentTransformer.transform(SOURCE, "cursor", "x"));
        assertEquals(SOURCE, AgentContentTransformer.transform(SOURCE, "windsurf", "x"));
        assertEquals(SOURCE, AgentContentTransformer.transform(SOURCE, "zed", "x"));
    }
}
