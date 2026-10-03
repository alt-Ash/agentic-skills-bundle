package dev.dorrian.agenticskillscli.flow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Writes a minimal, discoverable bundle layout (skills, agents, commands, templates, mcp jars) for tests. */
final class BundleFixture {

    /** The directories of a written bundle; {@code mcpJars} maps jar name to its file and is unmodifiable. */
    record Bundle(Path root, Path skillsDir, Path agentsDir, Path commandsDir, Path templatesDir,
                  Map<String, Path> mcpJars) {
    }

    private BundleFixture() {
    }

    /**
     * @param skills   key {@code <category>/<name>}, value body
     * @param agents   key name, value body
     * @param commands key name, value body
     * @param mcpJars  key name, value file content
     */
    static Bundle create(Path root, Map<String, String> skills, Map<String, String> agents,
                         Map<String, String> commands, Map<String, String> mcpJars) throws IOException {
        Objects.requireNonNull(root, "root");
        Path skillsDir = root.resolve("skills");
        Path agentsDir = root.resolve("agents");
        Path commandsDir = root.resolve(".opencode").resolve("commands");
        Path templatesDir = root.resolve("templates");
        Path mcpDir = root.resolve("mcp");
        for (Path dir : new Path[] {skillsDir, agentsDir, commandsDir, templatesDir, mcpDir}) {
            Files.createDirectories(dir);
        }

        for (Map.Entry<String, String> e : orEmpty(skills).entrySet()) {
            String name = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
            write(skillsDir.resolve(e.getKey()).resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: Fixture skill " + name + ".\n---\n" + e.getValue() + "\n");
        }
        for (Map.Entry<String, String> e : orEmpty(agents).entrySet()) {
            write(agentsDir.resolve(e.getKey() + ".md"),
                "---\ndescription: Fixture agent " + e.getKey() + " used by upgrade tests.\n"
                    + "mode: subagent\ntemperature: 0.1\ncolor: \"#112233\"\npermission:\n"
                    + "  edit: deny\n  write: deny\n  bash: deny\n---\n\n" + e.getValue() + "\n");
        }
        for (Map.Entry<String, String> e : orEmpty(commands).entrySet()) {
            write(commandsDir.resolve(e.getKey() + ".md"),
                "---\ndescription: Fixture command " + e.getKey() + ".\n---\n" + e.getValue() + "\n");
        }
        write(templatesDir.resolve("README.md"), "fixture templates\n");

        Map<String, Path> jars = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : orEmpty(mcpJars).entrySet()) {
            Path jar = mcpDir.resolve(e.getKey() + ".jar");
            write(jar, e.getValue());
            jars.put(e.getKey(), jar);
        }
        return new Bundle(root, skillsDir, agentsDir, commandsDir, templatesDir, Map.copyOf(jars));
    }

    private static Map<String, String> orEmpty(Map<String, String> m) {
        return m == null ? Map.of() : m;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
