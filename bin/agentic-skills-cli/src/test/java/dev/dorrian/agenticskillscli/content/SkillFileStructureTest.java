package dev.dorrian.agenticskillscli.content;

import dev.dorrian.agenticskillscli.frontmatter.FrontmatterParser;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.dorrian.agenticskillscli.content.AgentFileStructureTest.assertRequiredHandoffSubsections;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Java port of {@code evals/structural/skills.test.ts} — validates every independently
 * installable {@code skills/<category>/<skill>/SKILL.md} file. Only exactly two levels deep,
 * matching {@code discoverSkills()}'s own recursion depth in {@code
 * dev.dorrian.agenticskillscli.discovery.SkillDiscovery} — anything nested deeper is a bundled
 * reference sub-guide for a parent skill, not a standalone skill, and is out of scope here.
 */
class SkillFileStructureTest {

    private static final Pattern SECTION_HEADING = Pattern.compile("^## ", Pattern.MULTILINE);
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/Users/|/home/[a-z]|C:\\\\Users\\\\");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    // Resolved independently of PackageRoot's shared mutable singleton state - see the same note
    // in AgentFileStructureTest.
    private static final Path SKILLS_DIR = Path.of("../..").toAbsolutePath().normalize().resolve("skills");

    private record SkillFile(String relPath, String raw) {
    }

    private static List<SkillFile> discoverSkillFiles() {
        Path skillsDir = SKILLS_DIR;
        List<Path> categoryDirs = listDirs(skillsDir);
        List<Path> skillMdFiles = new ArrayList<>();
        for (Path category : categoryDirs) {
            for (Path skillDir : listDirs(category)) {
                Path candidate = skillDir.resolve("SKILL.md");
                if (Files.isRegularFile(candidate)) {
                    skillMdFiles.add(candidate);
                }
            }
        }
        skillMdFiles.sort(Comparator.comparing(Path::toString));

        List<SkillFile> files = new ArrayList<>();
        for (Path p : skillMdFiles) {
            try {
                files.add(new SkillFile(skillsDir.relativize(p).toString(), Files.readString(p)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static List<Path> listDirs(Path parent) {
        List<Path> dirs = new ArrayList<>();
        if (!Files.isDirectory(parent)) {
            return dirs;
        }
        try (var stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    dirs.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dirs;
    }

    @Test
    void atLeastOneSkillFound() {
        assertFalse(discoverSkillFiles().isEmpty(), "at least one SKILL.md found");
    }

    @TestFactory
    Stream<DynamicContainer> perSkillFileChecks() {
        return discoverSkillFiles().stream().map(file -> {
            String raw = file.raw();
            Map<String, Object> frontmatter = FrontmatterParser.parse(raw);
            FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(raw);
            String body = split != null ? split.body() : raw;

            List<DynamicTest> tests = new ArrayList<>();

            tests.add(dynamicTest("is non-empty", () ->
                    assertTrue(!raw.strip().isEmpty())));

            tests.add(dynamicTest("frontmatter has required field: name", () -> {
                Object name = frontmatter.get("name");
                assertTrue(name instanceof String && !((String) name).isEmpty(),
                        "name must be a non-empty string");
            }));

            tests.add(dynamicTest("frontmatter has required field: description", () -> {
                Object description = frontmatter.get("description");
                assertTrue(description instanceof String && !((String) description).isEmpty(),
                        "description must be a non-empty string");
            }));

            tests.add(dynamicTest("version follows semver if present", () -> {
                Object version = frontmatter.get("version");
                if (version != null) {
                    assertTrue(SEMVER.matcher(String.valueOf(version)).matches(),
                            "version must follow semver, got: " + version);
                }
            }));

            tests.add(dynamicTest("has at least 2 ## sections", () -> {
                long count = SECTION_HEADING.matcher(body).results().count();
                assertTrue(count >= 2, "expected at least 2 ## sections, found " + count);
            }));

            tests.add(dynamicTest("has no hardcoded absolute paths", () ->
                    assertFalse(ABSOLUTE_PATH.matcher(raw).find(), "must not contain hardcoded absolute paths")));

            tests.add(dynamicTest("has ***CONTEXT BLOCK*** template", () ->
                    assertTrue(raw.contains("***CONTEXT BLOCK***"), "skill must define a ***CONTEXT BLOCK*** template")));

            tests.add(dynamicTest("CONTEXT BLOCK is properly closed", () -> {
                if (!raw.contains("***CONTEXT BLOCK***")) {
                    return;
                }
                assertTrue(raw.contains("***END CONTEXT BLOCK***"),
                        "CONTEXT BLOCK must have a matching ***END CONTEXT BLOCK***");
            }));

            tests.add(dynamicTest("has ***HANDOFF BLOCK*** template", () ->
                    assertTrue(raw.contains("***HANDOFF BLOCK***"), "skill must define a ***HANDOFF BLOCK*** template")));

            tests.add(dynamicTest("HANDOFF BLOCK is properly closed and contains all required subsections", () -> {
                if (!raw.contains("***HANDOFF BLOCK***")) {
                    return;
                }
                assertTrue(raw.contains("***END HANDOFF BLOCK***"),
                        "HANDOFF BLOCK must have a matching ***END HANDOFF BLOCK***");

                java.util.regex.Matcher m = Pattern.compile(
                        "\\*\\*\\*HANDOFF BLOCK\\*\\*\\*([\\s\\S]*?)\\*\\*\\*END HANDOFF BLOCK\\*\\*\\*"
                ).matcher(raw);
                String block = m.find() ? m.group(1) : "";
                assertRequiredHandoffSubsections(block, "Skill/Agent\\s*:|Skill\\s*:");
            }));

            return dynamicContainer(file.relPath(), tests.stream());
        });
    }
}
