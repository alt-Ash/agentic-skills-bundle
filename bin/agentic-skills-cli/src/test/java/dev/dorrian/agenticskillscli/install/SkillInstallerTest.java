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

    // ─── Legacy locations (Windsurf project skills used to go to .windsurf/rules) ───

    @Test
    void removeFromWindsurfSkillsAlsoRemovesALegacyRulesCopy(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Path current = project.resolve(".windsurf").resolve("skills");
        Path legacy = project.resolve(".windsurf").resolve("rules");
        Files.createDirectories(current.resolve("react-migration"));
        Files.writeString(current.resolve("react-migration").resolve("SKILL.md"), "x");
        Files.createDirectories(legacy.resolve("react-migration"));
        Files.writeString(legacy.resolve("react-migration").resolve("SKILL.md"), "x");
        Files.writeString(legacy.resolve("my-own-rule.md"), "user content");

        OperationResult result = SkillInstaller.remove("react-migration", current);

        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(current.resolve("react-migration")));
        assertFalse(Files.exists(legacy.resolve("react-migration")));
        assertTrue(Files.exists(legacy.resolve("my-own-rule.md"))); // user's own rules untouched
    }

    @Test
    void removeCleansUpAnInstallThatOnlyExistsAtTheLegacyRulesLocation(@TempDir Path tempDir) throws IOException {
        Path current = tempDir.resolve(".windsurf").resolve("skills");
        Path legacy = tempDir.resolve(".windsurf").resolve("rules");
        Files.createDirectories(legacy.resolve("react-migration"));
        Files.writeString(legacy.resolve("react-migration").resolve("SKILL.md"), "x");

        OperationResult result = SkillInstaller.remove("react-migration", current);

        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(legacy.resolve("react-migration")));
    }

    @Test
    void legacyRulesDirectoryWithoutASkillFileIsLeftAlone(@TempDir Path tempDir) throws IOException {
        Path current = tempDir.resolve(".windsurf").resolve("skills");
        Path legacy = tempDir.resolve(".windsurf").resolve("rules");
        Files.createDirectories(legacy.resolve("react-migration"));
        Files.writeString(legacy.resolve("react-migration").resolve("notes.md"), "a user's own rule folder");

        OperationResult result = SkillInstaller.remove("react-migration", current);

        assertTrue(result.skipped());
        assertTrue(Files.exists(legacy.resolve("react-migration").resolve("notes.md")));
    }

    @Test
    void legacyLocationsOnlyApplyToTheWindsurfSkillsFolder(@TempDir Path tempDir) {
        assertEquals(List.of(tempDir.resolve(".windsurf").resolve("rules")),
            LegacySkillLocations.forTarget(tempDir.resolve(".windsurf").resolve("skills")));
        assertEquals(List.of(), LegacySkillLocations.forTarget(tempDir.resolve(".claude").resolve("skills")));
        assertEquals(List.of(), LegacySkillLocations.forTarget(tempDir.resolve("skills")));
    }
}
