package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleExtractorTest {

    private static Path fakeBundle(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("skills").resolve("core").resolve("demo"));
        Files.writeString(dir.resolve("skills").resolve("core").resolve("demo").resolve("SKILL.md"), "skill");
        Files.createDirectories(dir.resolve("agents"));
        Files.writeString(dir.resolve("agents").resolve("a.md"), "agent");
        Files.createDirectories(dir.resolve(".opencode").resolve("commands"));
        Files.writeString(dir.resolve(".opencode").resolve("commands").resolve("c.md"), "cmd");
        Files.writeString(dir.resolve("agentic-skills-hooks.jar"), "hooks-jar-bytes");
        return dir;
    }

    @Test
    void distDirIsVersionedUnderDotAgenticSkills(@TempDir Path home) {
        assertEquals(home.resolve(".agentic-skills").resolve("dist").resolve("2.0.0"),
            BundleExtractor.distDir(home, "2.0.0"));
    }

    @Test
    void extractsTheWholeBundleAndWritesTheCompletionMarker(@TempDir Path tmp) throws IOException {
        Path source = fakeBundle(tmp.resolve("src"));
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");

        Path root = BundleExtractor.extract(source, dist, "fp-1");

        assertEquals(dist, root);
        assertEquals("skill", Files.readString(dist.resolve("skills/core/demo/SKILL.md")));
        assertEquals("agent", Files.readString(dist.resolve("agents/a.md")));
        assertEquals("cmd", Files.readString(dist.resolve(".opencode/commands/c.md")));
        assertEquals("hooks-jar-bytes", Files.readString(dist.resolve("agentic-skills-hooks.jar")));
        assertTrue(Files.isRegularFile(dist.resolve(BundleExtractor.COMPLETE_MARKER)));
    }

    @Test
    void extractsOnlyOncePerVersion(@TempDir Path tmp) throws IOException {
        Path source = fakeBundle(tmp.resolve("src"));
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");
        BundleExtractor.extract(source, dist, "fp-1");

        // A local edit survives a second call: nothing was re-copied.
        Files.writeString(dist.resolve("agents/a.md"), "locally-touched");
        BundleExtractor.extract(source, dist, "fp-1");

        assertEquals("locally-touched", Files.readString(dist.resolve("agents/a.md")));
    }

    @Test
    void reExtractsWhenTheCompletionMarkerIsMissing(@TempDir Path tmp) throws IOException {
        Path source = fakeBundle(tmp.resolve("src"));
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");
        BundleExtractor.extract(source, dist, "fp-1");

        // Simulate an interrupted/partial extraction: marker gone, stray + truncated files left.
        Files.delete(dist.resolve(BundleExtractor.COMPLETE_MARKER));
        Files.writeString(dist.resolve("agents/a.md"), "trunc");
        Files.writeString(dist.resolve("stray.tmp"), "junk");

        BundleExtractor.extract(source, dist, "fp-1");

        assertEquals("agent", Files.readString(dist.resolve("agents/a.md")));
        assertFalse(Files.exists(dist.resolve("stray.tmp")));
        assertTrue(Files.isRegularFile(dist.resolve(BundleExtractor.COMPLETE_MARKER)));
    }

    @Test
    void reExtractsWhenTheBundleFingerprintChanged(@TempDir Path tmp) throws IOException {
        Path source = fakeBundle(tmp.resolve("src"));
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");
        BundleExtractor.extract(source, dist, "fp-1");

        Files.writeString(source.resolve("agents/a.md"), "agent-v2");
        BundleExtractor.extract(source, dist, "fp-2");

        assertEquals("agent-v2", Files.readString(dist.resolve("agents/a.md")));
    }

    @Test
    void leavesNoTempSiblingsBehind(@TempDir Path tmp) throws IOException {
        Path source = fakeBundle(tmp.resolve("src"));
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");
        BundleExtractor.extract(source, dist, "fp-1");

        try (var siblings = Files.list(dist.getParent())) {
            assertEquals(List.of(dist), siblings.toList());
        }
    }

    @Test
    void extractsTheBundleDirectoryOutOfAJar(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("agentic-skills.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "dev/dorrian/Unrelated.class", "not-bundle");
            putEntry(zip, "bundle/agents/a.md", "agent");
            putEntry(zip, "bundle/skills/core/demo/SKILL.md", "skill");
            putEntry(zip, "bundle/issue-tickets.jar", "mcp-jar");
        }
        Path dist = BundleExtractor.distDir(tmp.resolve("home"), "2.0.0");

        Optional<Path> root = BundleExtractor.extractFromJar(jar, dist);

        assertEquals(Optional.of(dist), root);
        assertEquals("agent", Files.readString(dist.resolve("agents/a.md")));
        assertEquals("skill", Files.readString(dist.resolve("skills/core/demo/SKILL.md")));
        assertEquals("mcp-jar", Files.readString(dist.resolve("issue-tickets.jar")));
        assertFalse(Files.exists(dist.resolve("dev")));
    }

    @Test
    void jarWithoutABundleDirectoryYieldsEmpty(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("plain.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "dev/dorrian/Unrelated.class", "x");
        }
        assertEquals(Optional.empty(), BundleExtractor.extractFromJar(jar, BundleExtractor.distDir(tmp, "2.0.0")));
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
