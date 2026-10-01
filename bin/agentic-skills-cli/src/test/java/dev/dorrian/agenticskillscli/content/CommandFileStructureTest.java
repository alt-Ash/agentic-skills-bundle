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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Java port of {@code evals/structural/commands.test.ts} — validates every {@code
 * .opencode/commands/*.md} file. This directory is the single source of truth installed to
 * every tool with {@code supportsCommands} (currently OpenCode and Claude Code), so unknown
 * frontmatter keys must be ones every supported tool tolerates, not opencode-only fields that
 * could break another tool's command parser.
 */
class CommandFileStructureTest {

    private static final Set<String> KNOWN_KEYS = Set.of("description", "subtask");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/Users/|/home/[a-z]|C:\\\\Users\\\\");

    // Resolved independently of PackageRoot's shared mutable singleton state - see the same note
    // in AgentFileStructureTest.
    private static final Path COMMANDS_DIR = Path.of("../..").toAbsolutePath().normalize()
            .resolve(".opencode").resolve("commands");

    private record CommandFile(String name, String raw) {
    }

    private static List<CommandFile> discoverCommandFiles() {
        Path commandsDir = COMMANDS_DIR;
        List<Path> mdFiles = new ArrayList<>();
        if (Files.isDirectory(commandsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(commandsDir, "*.md")) {
                for (Path entry : stream) {
                    mdFiles.add(entry);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        mdFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<CommandFile> files = new ArrayList<>();
        for (Path p : mdFiles) {
            try {
                files.add(new CommandFile(p.getFileName().toString(), Files.readString(p)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    @Test
    void atLeastOneCommandFound() {
        assertFalse(discoverCommandFiles().isEmpty(), "at least one command file found");
    }

    @TestFactory
    Stream<DynamicContainer> perCommandFileChecks() {
        return discoverCommandFiles().stream().map(file -> {
            String raw = file.raw();
            Map<String, Object> frontmatter = FrontmatterParser.parse(raw);
            FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(raw);
            String body = split != null ? split.body() : raw;

            List<DynamicTest> tests = new ArrayList<>();

            tests.add(dynamicTest("is non-empty", () ->
                    assertTrue(!raw.strip().isEmpty())));

            tests.add(dynamicTest("frontmatter has required field: description", () -> {
                Object description = frontmatter.get("description");
                assertTrue(description instanceof String && !((String) description).isEmpty(),
                        "description must be a non-empty string");
            }));

            tests.add(dynamicTest("has no unknown frontmatter keys", () -> {
                List<String> unknown = frontmatter.keySet().stream()
                        .filter(k -> !KNOWN_KEYS.contains(k))
                        .toList();
                assertTrue(unknown.isEmpty(), "unexpected frontmatter keys: " + unknown);
            }));

            tests.add(dynamicTest("has a non-empty body", () ->
                    assertTrue(!body.strip().isEmpty())));

            tests.add(dynamicTest("has no hardcoded absolute paths", () ->
                    assertFalse(ABSOLUTE_PATH.matcher(raw).find(), "must not contain hardcoded absolute paths")));

            return dynamicContainer(file.name(), tests.stream());
        });
    }
}
