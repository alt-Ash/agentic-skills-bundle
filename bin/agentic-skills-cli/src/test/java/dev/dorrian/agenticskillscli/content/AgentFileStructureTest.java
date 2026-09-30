package dev.dorrian.agenticskillscli.content;

import dev.dorrian.agenticskillscli.frontmatter.FrontmatterParser;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Java port of {@code evals/structural/agents.test.ts} — validates every {@code agents/*.md}
 * file's frontmatter and body structure. Deliberately does NOT reuse {@link
 * dev.dorrian.agenticskillscli.discovery.AgentDiscovery}, which silently skips files with no
 * {@code description} field (correct for install-time discovery, wrong here: this test must
 * validate every agent file, including a malformed one that's missing that very field).
 */
class AgentFileStructureTest {

    private static final Set<String> KNOWN_PERMISSION_KEYS = Set.of(
            "edit", "write", "bash", "read", "glob", "grep", "webfetch", "task", "mcp");
    private static final Set<String> VALID_PERMISSION_SCALARS = Set.of("allow", "deny", "ask");
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    private static final Pattern SECTION_HEADING = Pattern.compile("^## ", Pattern.MULTILINE);

    // Resolved independently of dev.dorrian.agenticskillscli.PackageRoot on purpose: that class
    // is a shared, mutable, JVM-wide singleton, and PackageRootTest (in the same Surefire fork)
    // repeatedly re-points it at fake/temp directories across its own tests. Depending on it here
    // would make these tests' results depend on unspecified cross-class execution order.
    private static final Path AGENTS_DIR = Path.of("../..").toAbsolutePath().normalize().resolve("agents");

    private record AgentFile(String name, Path path, String content) {
    }

    private static List<AgentFile> discoverAgentFiles() {
        Path agentsDir = AGENTS_DIR;
        List<Path> mdFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agentsDir, "*.md")) {
            for (Path entry : stream) {
                mdFiles.add(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mdFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<AgentFile> files = new ArrayList<>();
        for (Path p : mdFiles) {
            try {
                files.add(new AgentFile(p.getFileName().toString(), p, Files.readString(p)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    @Test
    void atLeastOneAgentFound() {
        assertFalse(discoverAgentFiles().isEmpty(), "at least one agent file found");
    }

    @TestFactory
    Stream<DynamicContainer> perAgentFileChecks() {
        return discoverAgentFiles().stream().map(file -> {
            Map<String, Object> frontmatter = FrontmatterParser.parse(file.content());
            FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(file.content());
            String body = split != null ? split.body() : file.content();

            List<DynamicTest> tests = new ArrayList<>();

            tests.add(dynamicTest("description is a non-empty string of at least 20 chars", () -> {
                Object description = frontmatter.get("description");
                assertTrue(description instanceof String, "description must be a string");
                assertTrue(((String) description).length() >= 20,
                        "description must be at least 20 chars");
            }));

            tests.add(dynamicTest("mode is subagent or primary", () -> {
                Object mode = frontmatter.get("mode");
                assertTrue("subagent".equals(mode) || "primary".equals(mode),
                        "mode must be \"subagent\" or \"primary\", got: " + mode);
            }));

            tests.add(dynamicTest("temperature is a number between 0 and 1", () -> {
                Object temperature = frontmatter.get("temperature");
                assertTrue(temperature instanceof Number, "temperature must be a number");
                double t = ((Number) temperature).doubleValue();
                assertTrue(t >= 0 && t <= 1, "temperature must be between 0 and 1, got: " + t);
            }));

            tests.add(dynamicTest("color is a valid hex color (#RGB or #RRGGBB)", () -> {
                Object color = frontmatter.get("color");
                assertTrue(color instanceof String, "color must be a string");
                assertTrue(HEX_COLOR.matcher((String) color).matches(),
                        "color must be a valid hex color, got: " + color);
            }));

            tests.add(dynamicTest("permission is a non-null plain object", () -> {
                Object permission = frontmatter.get("permission");
                assertTrue(permission instanceof Map, "permission must be a non-null object");
            }));

            tests.add(dynamicTest("permission keys are all from the known set", () -> {
                Map<?, ?> permission = asMap(frontmatter.get("permission"));
                List<String> unknownKeys = permission.keySet().stream()
                        .map(Object::toString)
                        .filter(k -> !KNOWN_PERMISSION_KEYS.contains(k))
                        .toList();
                assertTrue(unknownKeys.isEmpty(), "unknown permission keys: " + unknownKeys);
            }));

            tests.add(dynamicTest("permission values are valid (allow/deny/ask, boolean, or pattern-object)", () -> {
                Map<?, ?> permission = asMap(frontmatter.get("permission"));
                List<String> invalid = new ArrayList<>();
                for (Map.Entry<?, ?> entry : permission.entrySet()) {
                    if (!isValidPermissionValue(entry.getValue())) {
                        invalid.add(entry.getKey() + "=" + entry.getValue());
                    }
                }
                assertTrue(invalid.isEmpty(), "invalid permission values: " + invalid);
            }));

            tests.add(dynamicTest("body has at least 2 ## sections", () -> {
                long count = SECTION_HEADING.matcher(body).results().count();
                assertTrue(count >= 2, "expected at least 2 ## sections, found " + count);
            }));

            tests.add(dynamicTest("documents its output format", () -> {
                boolean hasHandoff = body.contains("***HANDOFF BLOCK***");
                boolean hasIssueResult = body.contains("issue_result:");
                boolean hasImplementResult = body.contains("implement_result:");
                boolean hasOutputSection = Pattern.compile("^## Output format", Pattern.MULTILINE).matcher(body).find();
                boolean hasHandoffSection = Pattern.compile("^### Handoff Block", Pattern.MULTILINE).matcher(body).find();
                assertTrue(hasHandoff || hasIssueResult || hasImplementResult || hasOutputSection || hasHandoffSection,
                        "agent must document its output via HANDOFF BLOCK, a named YAML result block, "
                                + "## Output format, or ### Handoff Block");
            }));

            tests.add(dynamicTest("HANDOFF BLOCK is properly closed and contains all required subsections", () -> {
                if (!body.contains("***HANDOFF BLOCK***")) {
                    return;
                }
                assertTrue(body.contains("***END HANDOFF BLOCK***"),
                        "HANDOFF BLOCK must have a matching ***END HANDOFF BLOCK***");

                java.util.regex.Matcher m = Pattern.compile(
                        "\\*\\*\\*HANDOFF BLOCK\\*\\*\\*([\\s\\S]*?)\\*\\*\\*END HANDOFF BLOCK\\*\\*\\*"
                ).matcher(body);
                String block = m.find() ? m.group(1) : "";

                assertRequiredHandoffSubsections(block, "Skill/Agent\\s*:");
            }));

            tests.add(dynamicTest("name field matches filename if present", () -> {
                Object name = frontmatter.get("name");
                if (name != null) {
                    String expected = file.name().substring(0, file.name().length() - ".md".length());
                    assertEquals(expected, name.toString());
                }
            }));

            return dynamicContainer(file.name(), tests.stream());
        });
    }

    static void assertRequiredHandoffSubsections(String block, String skillFieldPattern) {
        assertTrue(Pattern.compile(skillFieldPattern).matcher(block).find(),
                "HANDOFF BLOCK must have Skill/Agent field");
        assertTrue(Pattern.compile("Timestamp\\s*:").matcher(block).find(),
                "HANDOFF BLOCK must have Timestamp field");
        assertTrue(Pattern.compile("Status\\s*:").matcher(block).find(),
                "HANDOFF BLOCK must have Status field");
        assertTrue(block.contains("### What was done"), "HANDOFF BLOCK must have ### What was done");
        assertTrue(block.contains("### Artifacts produced"), "HANDOFF BLOCK must have ### Artifacts produced");
        assertTrue(block.contains("### Checks"), "HANDOFF BLOCK must have ### Checks");
        assertTrue(block.contains("### Blocked items"), "HANDOFF BLOCK must have ### Blocked items");
        assertTrue(block.contains("### For the next agent or step"),
                "HANDOFF BLOCK must have ### For the next agent or step");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<Object, Object>) m;
        }
        fail("expected a map, got: " + value);
        throw new AssertionError("unreachable");
    }

    private static boolean isValidPermissionValue(Object val) {
        if (val instanceof Boolean) {
            return true;
        }
        if (val instanceof String s) {
            return VALID_PERMISSION_SCALARS.contains(s);
        }
        if (val instanceof Map<?, ?> nested) {
            return nested.values().stream()
                    .allMatch(v -> v instanceof String s && VALID_PERMISSION_SCALARS.contains(s));
        }
        return false;
    }
}
