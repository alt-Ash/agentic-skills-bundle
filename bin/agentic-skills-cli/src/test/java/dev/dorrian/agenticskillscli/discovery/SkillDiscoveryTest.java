package dev.dorrian.agenticskillscli.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDiscoveryTest {

    @Test
    void discoversSkillsTwoLevelsDeep(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("frontend").resolve("react-migration"));
        Files.createDirectories(tempDir.resolve("backend").resolve("java-version-migrator"));
        // A stray file directly under skills/ (not a category dir) must be ignored.
        Files.writeString(tempDir.resolve("README.md"), "not a category");

        List<SkillDescriptor> skills = SkillDiscovery.discover(tempDir);

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.category().equals("frontend") && s.name().equals("react-migration")));
        assertTrue(skills.stream().anyMatch(s -> s.category().equals("backend") && s.name().equals("java-version-migrator")));
    }

    @Test
    void returnsEmptyListWhenSkillsDirDoesNotExist(@TempDir Path tempDir) {
        assertEquals(List.of(), SkillDiscovery.discover(tempDir.resolve("does-not-exist")));
    }

    @Test
    void ignoresFilesDirectlyUnderACategoryDirectory(@TempDir Path tempDir) throws IOException {
        Path category = tempDir.resolve("frontend");
        Files.createDirectories(category);
        Files.writeString(category.resolve("stray-file.md"), "not a skill dir");

        assertEquals(List.of(), SkillDiscovery.discover(tempDir));
    }
}
