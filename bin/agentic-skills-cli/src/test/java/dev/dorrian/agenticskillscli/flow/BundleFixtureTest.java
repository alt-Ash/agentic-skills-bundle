package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleFixtureTest {

    @TempDir Path tmp;

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void skillFileHasExactContent() throws IOException {
        BundleFixture.Bundle b = BundleFixture.create(tmp, Map.of("workflow/my-skill", "body text"), null, null, null);

        assertEquals("---\nname: my-skill\ndescription: Fixture skill my-skill.\n---\nbody text\n",
            read(tmp.resolve("skills/workflow/my-skill/SKILL.md")));
        assertEquals(tmp.resolve("skills"), b.skillsDir());
        assertEquals(1, SkillDiscovery.discover(b.skillsDir()).size());
    }

    @Test
    void agentFileHasExactContent() throws IOException {
        BundleFixture.Bundle b = BundleFixture.create(tmp, null, Map.of("my-agent", "## Body\ntext"), null, null);

        assertEquals("---\ndescription: Fixture agent my-agent used by upgrade tests.\nmode: subagent\n"
                + "temperature: 0.1\ncolor: \"#112233\"\npermission:\n  edit: deny\n  write: deny\n  bash: deny\n"
                + "---\n\n## Body\ntext\n",
            read(tmp.resolve("agents/my-agent.md")));
        assertEquals(1, AgentDiscovery.discover(b.agentsDir()).size());
    }

    @Test
    void commandFileHasExactContent() throws IOException {
        BundleFixture.Bundle b = BundleFixture.create(tmp, null, null, Map.of("my-cmd", "do it"), null);

        assertEquals("---\ndescription: Fixture command my-cmd.\n---\ndo it\n",
            read(tmp.resolve(".opencode/commands/my-cmd.md")));
        assertEquals(tmp.resolve(".opencode/commands"), b.commandsDir());
    }

    @Test
    void directoriesAndTemplatesAreAlwaysCreated() throws IOException {
        BundleFixture.Bundle b = BundleFixture.create(tmp, null, null, null, null);

        for (String d : new String[] {"skills", "agents", ".opencode/commands", "templates", "mcp"}) {
            assertTrue(Files.isDirectory(tmp.resolve(d)), d);
        }
        assertEquals("fixture templates\n", read(tmp.resolve("templates/README.md")));
        assertEquals(tmp, b.root());
        assertEquals(tmp.resolve("templates"), b.templatesDir());
        assertTrue(b.mcpJars().isEmpty());
    }

    @Test
    void mcpJarsAreWrittenAndReturnedMapIsUnmodifiable() throws IOException {
        BundleFixture.Bundle b = BundleFixture.create(tmp, null, null, null, Map.of("some-mcp", "jar bytes"));

        assertEquals(tmp.resolve("mcp/some-mcp.jar"), b.mcpJars().get("some-mcp"));
        assertEquals("jar bytes", read(b.mcpJars().get("some-mcp")));
        assertThrows(UnsupportedOperationException.class, () -> b.mcpJars().put("x", tmp));
    }

    @Test
    void createOverwritesExistingFiles() throws IOException {
        BundleFixture.create(tmp, Map.of("c/s", "one"), Map.of("a", "one"), Map.of("k", "one"), Map.of("m", "one"));
        BundleFixture.create(tmp, Map.of("c/s", "two"), Map.of("a", "two"), Map.of("k", "two"), Map.of("m", "two"));

        assertTrue(read(tmp.resolve("skills/c/s/SKILL.md")).endsWith("---\ntwo\n"));
        assertTrue(read(tmp.resolve("agents/a.md")).endsWith("---\n\ntwo\n"));
        assertTrue(read(tmp.resolve(".opencode/commands/k.md")).endsWith("---\ntwo\n"));
        assertEquals("two", read(tmp.resolve("mcp/m.jar")));
    }

    @Test
    void nullRootIsRejected() {
        assertThrows(NullPointerException.class, () -> BundleFixture.create(null, null, null, null, null));
    }
}
