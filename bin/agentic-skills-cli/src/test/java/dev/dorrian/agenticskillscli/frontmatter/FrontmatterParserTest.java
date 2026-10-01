package dev.dorrian.agenticskillscli.frontmatter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterParserTest {

    @Test
    void parsesAScalarFrontmatterBlock() {
        String content = """
            ---
            description: Does a thing
            mode: subagent
            temperature: 0.2
            ---
            Body.
            """;
        Map<String, Object> fm = FrontmatterParser.parse(content);
        assertEquals("Does a thing", fm.get("description"));
        assertEquals("subagent", fm.get("mode"));
        assertEquals(0.2, fm.get("temperature"));
    }

    @Test
    void parsesNestedPermissionMapping() {
        String content = """
            ---
            description: x
            permission:
              edit: allow
              bash:
                '*': deny
            ---
            """;
        Map<String, Object> fm = FrontmatterParser.parse(content);
        @SuppressWarnings("unchecked")
        Map<String, Object> perm = (Map<String, Object>) fm.get("permission");
        assertEquals("allow", perm.get("edit"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bash = (Map<String, Object>) perm.get("bash");
        assertEquals("deny", bash.get("*"));
    }

    @Test
    void returnsEmptyMapWhenNoFrontmatterBlockPresent() {
        assertTrue(FrontmatterParser.parse("# Just a heading\n\nNo frontmatter.").isEmpty());
    }

    @Test
    void returnsEmptyMapOnMalformedYaml() {
        String content = "---\ndescription: [unterminated\n---\nBody\n";
        assertTrue(FrontmatterParser.parse(content).isEmpty());
    }

    @Test
    void splitFrontmatterAndBodySeparatesCorrectly() {
        String content = "---\ndescription: x\n---\nHello body\n";
        FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(content);
        assertNotNull(split);
        assertEquals("description: x", split.rawFrontmatter());
        assertEquals("Hello body\n", split.body());
    }

    @Test
    void splitFrontmatterAndBodyReturnsNullWhenNoFrontmatter() {
        assertNull(FrontmatterParser.splitFrontmatterAndBody("no frontmatter here"));
    }
}
