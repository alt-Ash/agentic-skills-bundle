package dev.dorrian.agenticskillscli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallManifestTest {

    @TempDir Path tmp;

    @Test
    void roundTrip() throws IOException {
        Path file = tmp.resolve("state/installed.json");
        InstallManifest m = InstallManifest.load(file);
        String key = InstallManifest.key("global", "claude", ItemKind.SKILL, "tdd");
        assertEquals("global|claude|SKILL|tdd", key);
        m.record(key, "abc", "1.0");
        m.recordBundle("1.0", List.of("SKILL:tdd", "AGENT:a"));
        m.save();

        InstallManifest back = InstallManifest.load(file);
        assertEquals("abc", back.hash(key).orElseThrow());
        assertEquals("1.0", back.bundleVersion());
        assertEquals(Set.of("SKILL:tdd", "AGENT:a"), back.bundleItems());
        assertTrue(back.hash("other").isEmpty());
    }

    @Test
    void missingFileGivesEmpty() {
        InstallManifest m = InstallManifest.load(tmp.resolve("nope.json"));
        assertNull(m.bundleVersion());
        assertTrue(m.bundleItems().isEmpty());
        assertTrue(m.hash("x").isEmpty());
    }

    @Test
    void corruptOrWrongSchemaGivesEmpty() throws IOException {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{not json");
        assertTrue(InstallManifest.load(file).hash("x").isEmpty());
        Files.writeString(file, "{\"schema\":2,\"items\":{\"x\":{\"hash\":\"h\"}}}");
        assertTrue(InstallManifest.load(file).hash("x").isEmpty());
        Files.writeString(file, "[1,2]");
        assertTrue(InstallManifest.load(file).hash("x").isEmpty());
        Files.writeString(file, "{\"schema\":1,\"items\":{\"x\":5}}");
        assertTrue(InstallManifest.load(file).hash("x").isEmpty());
    }

    @Test
    void saveReplacesAndLeavesNoTempFile() throws IOException {
        Path file = tmp.resolve("installed.json");
        InstallManifest m = InstallManifest.load(file);
        m.record("k", "h1", "1");
        m.save();
        m.record("k", "h2", "2");
        m.save();
        assertEquals("h2", InstallManifest.load(file).hash("k").orElseThrow());
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(List.of("installed.json"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void recordBundleReplacesList() {
        InstallManifest m = InstallManifest.load(tmp.resolve("m.json"));
        m.recordBundle("1", List.of("SKILL:a", "SKILL:b"));
        m.recordBundle("2", List.of("SKILL:c"));
        assertEquals("2", m.bundleVersion());
        assertEquals(Set.of("SKILL:c"), m.bundleItems());
    }

    @Test
    void filesRoundTripAndEntriesWithoutFilesStillLoad() throws IOException {
        Path file = tmp.resolve("m.json");
        InstallManifest m = InstallManifest.load(file);
        m.record("k1", "h1", "1", List.of("SKILL.md", "sub/f.txt"));
        m.record("k2", "h2", "1");
        m.save();
        InstallManifest back = InstallManifest.load(file);
        assertEquals(List.of("SKILL.md", "sub/f.txt"), back.files("k1").orElseThrow());
        assertTrue(back.files("k2").isEmpty());
        assertEquals("h2", back.hash("k2").orElseThrow());

        Files.writeString(file, "{\"schema\":1,\"bundleVersion\":null,\"bundleItems\":[],"
            + "\"items\":{\"old\":{\"hash\":\"x\",\"version\":\"0\"}}}");
        InstallManifest old = InstallManifest.load(file);
        assertEquals("x", old.hash("old").orElseThrow());
        assertTrue(old.files("old").isEmpty());
    }

    @Test
    void advanceBundleEstablishesBaselineThenOnlyAddsInstalled() {
        InstallManifest m = InstallManifest.load(tmp.resolve("m.json"));
        m.advanceBundle("1", List.of("SKILL:a", "SKILL:b"), List.of("SKILL:a"));
        assertEquals("1", m.bundleVersion());
        assertEquals(Set.of("SKILL:a", "SKILL:b"), m.bundleItems());

        m.advanceBundle("2", List.of("SKILL:a", "SKILL:b", "SKILL:c", "SKILL:d"), List.of("SKILL:c"));
        assertEquals("2", m.bundleVersion());
        assertEquals(Set.of("SKILL:a", "SKILL:b", "SKILL:c"), m.bundleItems());
    }
}
