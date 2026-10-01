package dev.dorrian.agenticskillscli.frontmatter;

import dev.dorrian.agenticskillscli.TomlTestSupport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiCodexAgentTransformTest {

    /** Read-only agent: edit + write denied, bash only partially allowed, an MCP grant, webfetch allowed. */
    private static final String READ_ONLY = """
        ---
        description: "Reviews code: reports issues, never edits"
        mode: subagent
        temperature: 0.1
        color: "#8957E5"
        model: anthropic/claude-sonnet
        permission:
          edit: deny
          write: deny
          bash:
            "*": ask
            "git diff*": allow
          webfetch: allow
          mcp:
            "issue-tickets/pull_ticket": allow
            "issue-tickets/create_issue": deny
        ---

        You are a reviewer.
        Report findings only.
        """;

    private static final String WRITER = """
        ---
        description: Implements features end to end
        mode: subagent
        temperature: 0.2
        color: "#2EA043"
        permission:
          edit: allow
          write: allow
          bash: allow
        ---
        Build things.
        """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> geminiFrontmatter(String out) {
        assertTrue(out.startsWith("---\n"), out);
        int end = out.indexOf("\n---\n", 4);
        assertTrue(end > 0, out);
        return (Map<String, Object>) new Yaml().load(out.substring(4, end));
    }

    private static String geminiBody(String out) {
        return out.substring(out.indexOf("\n---\n", 4) + 5);
    }

    // ─── Gemini ──────────────────────────────────────────────────────────────

    @Test
    void geminiEmitsRequiredNameAndDescriptionAndKindLocal() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(WRITER, "gemini", "issue-implementer"));
        assertEquals("issue-implementer", fm.get("name"));
        assertEquals("Implements features end to end", fm.get("description"));
        assertEquals("local", fm.get("kind"));
        assertEquals(0.2, ((Number) fm.get("temperature")).doubleValue());
    }

    @Test
    void geminiDropsAllOpencodeOnlyKeys() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(READ_ONLY, "gemini", "pr-reviewer"));
        for (String key : List.of("mode", "color", "permission", "model")) {
            assertFalse(fm.containsKey(key), key + " leaked: " + fm);
        }
    }

    @Test
    void geminiQuotesDescriptionsContainingColons() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(READ_ONLY, "gemini", "pr-reviewer"));
        assertEquals("Reviews code: reports issues, never edits", fm.get("description"));
    }

    @Test
    void geminiNameIsAlwaysAValidSlug() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(WRITER, "gemini", "My Agent.v2"));
        String name = (String) fm.get("name");
        assertTrue(name.matches("[a-z0-9_-]+"), name);
        assertEquals("my-agent-v2", name);
    }

    @Test
    void geminiOmitsToolsWhenAgentCanEdit() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(WRITER, "gemini", "issue-implementer"));
        assertFalse(fm.containsKey("tools"), "tools omitted = inherit all");
    }

    @Test
    void geminiReadOnlyAgentGetsVerifiedReadOnlyAllowlist() {
        Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(READ_ONLY, "gemini", "pr-reviewer"));
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) fm.get("tools");
        assertEquals(List.of(
            "read_file", "read_many_files", "list_directory", "glob", "grep_search",
            "web_fetch", "google_web_search",
            "run_shell_command",
            "mcp_issue-tickets_pull_ticket"
        ), tools);
        assertFalse(tools.contains("write_file"));
        assertFalse(tools.contains("replace"));
    }

    @Test
    void geminiReadOnlyAgentWithBashAndWebDeniedGetsNeitherAndMcpWildcardMaps() {
        String src = """
            ---
            description: Drafts issues without touching code
            temperature: 0.1
            permission:
              edit: deny
              write: deny
              bash:
                "*": deny
                "ls *": allow
              webfetch: deny
              mcp:
                "issue-tickets/*": allow
            ---
            Body.
            """;
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) geminiFrontmatter(AgentContentTransformer.transform(src, "gemini", "a")).get("tools");
        assertEquals(List.of("read_file", "read_many_files", "list_directory", "glob", "grep_search", "mcp_issue-tickets_*"), tools);
    }

    @Test
    void geminiOmitsOutOfRangeTemperature() {
        String src = "---\ndescription: Hot agent here\ntemperature: 3\n---\nBody.\n";
        assertFalse(geminiFrontmatter(AgentContentTransformer.transform(src, "gemini", "hot")).containsKey("temperature"));
    }

    @Test
    void geminiKeepsBodyAsSystemPrompt() {
        String out = AgentContentTransformer.transform(READ_ONLY, "gemini", "pr-reviewer");
        assertTrue(geminiBody(out).startsWith("\nYou are a reviewer.\nReport findings only."), out);
    }

    // ─── Codex ───────────────────────────────────────────────────────────────

    @Test
    void codexEmitsParseableTomlWithRequiredKeys() {
        String out = AgentContentTransformer.transform(WRITER, "codex", "issue-implementer");
        Map<String, Object> toml = TomlTestSupport.parse(out);
        assertEquals("issue-implementer", toml.get("name"));
        assertEquals("Implements features end to end", toml.get("description"));
        assertEquals("Build things.\n", toml.get("developer_instructions"));
        assertNull(toml.get("sandbox_mode"));
        for (String key : List.of("mode", "color", "permission", "temperature", "model")) {
            assertFalse(toml.containsKey(key), key);
        }
    }

    @Test
    void codexReadOnlyAgentGetsReadOnlySandbox() {
        Map<String, Object> toml = TomlTestSupport.parse(AgentContentTransformer.transform(READ_ONLY, "codex", "pr-reviewer"));
        assertEquals("read-only", toml.get("sandbox_mode"));
        assertEquals("Reviews code: reports issues, never edits", toml.get("description"));
        assertEquals("You are a reviewer.\nReport findings only.\n", toml.get("developer_instructions"));
    }

    @Test
    void codexUsesLiteralMultilineStringForOrdinaryBodies() {
        String src = "---\ndescription: Quotes \"and\" back\\slashes\n---\nUse C:\\path and \"quotes\" and 'single' ones.\n";
        String out = AgentContentTransformer.transform(src, "codex", "q");
        assertTrue(out.contains("developer_instructions = '''\n"), out);
        Map<String, Object> toml = TomlTestSupport.parse(out);
        assertEquals("Use C:\\path and \"quotes\" and 'single' ones.\n", toml.get("developer_instructions"));
        assertEquals("Quotes \"and\" back\\slashes", toml.get("description"));
    }

    @Test
    void codexFallsBackToBasicStringWhenBodyContainsTripleSingleQuotes() {
        String body = "Wrap with ''' like this, keep \"\"\" and \\n literal, and a tab\there.\nEnds with quote'";
        String src = "---\ndescription: Tricky body agent\n---\n" + body + "\n";
        String out = AgentContentTransformer.transform(src, "codex", "tricky");
        Map<String, Object> toml = TomlTestSupport.parse(out);
        assertEquals(body + "\n", toml.get("developer_instructions"));
    }

    @Test
    void codexBodyEndingInSingleQuoteStaysValid() {
        String src = "---\ndescription: Ends in quote agent\n---\nsay 'hi''\n";
        Map<String, Object> toml = TomlTestSupport.parse(AgentContentTransformer.transform(src, "codex", "e"));
        assertEquals("say 'hi''\n", toml.get("developer_instructions"));
    }

    @Test
    void codexHandlesContentWithoutFrontmatter() {
        Map<String, Object> toml = TomlTestSupport.parse(AgentContentTransformer.transform("Just a body.\n", "codex", "bare"));
        assertEquals("bare", toml.get("name"));
        assertEquals("Just a body.\n", toml.get("developer_instructions"));
        assertTrue(toml.get("description") instanceof String);
    }

    @Test
    void everyShippedAgentTransformsToValidGeminiAndCodexFiles() throws java.io.IOException {
        java.nio.file.Path agentsDir = java.nio.file.Path.of("../..").toAbsolutePath().normalize().resolve("agents");
        try (var files = java.nio.file.Files.list(agentsDir)) {
            List<java.nio.file.Path> mds = files.filter(p -> p.toString().endsWith(".md")).toList();
            assertFalse(mds.isEmpty());
            for (java.nio.file.Path md : mds) {
                String name = md.getFileName().toString().replace(".md", "");
                String src = java.nio.file.Files.readString(md);
                Map<String, Object> fm = geminiFrontmatter(AgentContentTransformer.transform(src, "gemini", name));
                assertEquals(name, fm.get("name"), md.toString());
                assertTrue(fm.get("description") instanceof String, md.toString());
                Map<String, Object> toml = TomlTestSupport.parse(AgentContentTransformer.transform(src, "codex", name));
                assertEquals(name, toml.get("name"));
                assertTrue(((String) toml.get("developer_instructions")).length() > 50, md.toString());
            }
        }
    }

    @Test
    void codexAgentFileNameIsToml() {
        assertEquals("pr-reviewer.toml", AgentFileNaming.fileName("pr-reviewer", "codex"));
        assertEquals("pr-reviewer.md", AgentFileNaming.fileName("pr-reviewer", "gemini"));
    }
}
