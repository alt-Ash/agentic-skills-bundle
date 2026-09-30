package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillInstallerTest {

    @Test
    void installCopiesSkillDirectoryRecursively(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("src").resolve("react-migration");
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("SKILL.md"), "skill body");
        Files.writeString(source.resolve("references").resolve("notes.md"), "notes");

        Path target = tempDir.resolve("installed");
        List<OperationResult> results = SkillInstaller.install(
            List.of(new SkillDescriptor("frontend", "react-migration", source)), target
        );

        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("skill body", Files.readString(target.resolve("react-migration").resolve("SKILL.md")));
        assertEquals("notes", Files.readString(target.resolve("react-migration").resolve("references").resolve("notes.md")));
    }

    @Test
    void removeDeletesInstalledSkillRecursively(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("installed");
        Files.createDirectories(target.resolve("react-migration").resolve("nested"));
        Files.writeString(target.resolve("react-migration").resolve("nested").resolve("f.md"), "x");

        OperationResult result = SkillInstaller.remove("react-migration", target);

        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(target.resolve("react-migration")));
    }

    @Test
    void removeIsSkippedWhenNotInstalled(@TempDir Path tempDir) {
        OperationResult result = SkillInstaller.remove("never-installed", tempDir);

        assertTrue(result.success());
        assertTrue(result.skipped());
    }
}
