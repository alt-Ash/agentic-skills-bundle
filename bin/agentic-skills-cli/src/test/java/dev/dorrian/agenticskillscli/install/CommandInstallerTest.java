package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandInstallerTest {

    @Test
    void resolveForSkillsOnlyReturnsCommandsThatExistOnDisk(@TempDir Path commandsDir) throws IOException {
        // migrate-node.md exists; a made-up skill with no mapped commands contributes nothing.
        Files.writeString(commandsDir.resolve("migrate-node.md"), "cmd body");

        List<CommandDescriptor> resolved = CommandInstaller.resolveForSkills(
            List.of(new SkillDescriptor("backend", "nodejs-version-migrator", Path.of("/unused"))), commandsDir
        );

        assertEquals(1, resolved.size());
        assertEquals("migrate-node", resolved.get(0).name());
    }

    @Test
    void resolveForSkillsSkipsMappedCommandsMissingOnDisk(@TempDir Path commandsDir) {
        // No migrate-node.md written this time — the mapping exists in the registry but the file doesn't.
        List<CommandDescriptor> resolved = CommandInstaller.resolveForSkills(
            List.of(new SkillDescriptor("backend", "nodejs-version-migrator", Path.of("/unused"))), commandsDir
        );

        assertEquals(List.of(), resolved);
    }

    @Test
    void resolveForAgentsOnlyReturnsCommandsThatExistOnDisk(@TempDir Path commandsDir) throws IOException {
        Files.writeString(commandsDir.resolve("new-issue.md"), "cmd body");

        List<CommandDescriptor> resolved = CommandInstaller.resolveForAgents(
            List.of(new AgentDescriptor("issue-architect", Path.of("/unused"), Map.of())), commandsDir
        );

        assertEquals(1, resolved.size());
        assertEquals("new-issue", resolved.get(0).name());
    }

    @Test
    void installCopiesEachCommandFile(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("migrate-node.md");
        Files.writeString(src, "command markdown");
        Path target = tempDir.resolve("installed-commands");

        List<OperationResult> results = CommandInstaller.install(List.of(new CommandDescriptor("migrate-node", src)), target);

        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("command markdown", Files.readString(target.resolve("migrate-node.md")));
    }

    @Test
    void removeDeletesInstalledCommandFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("migrate-node.md"), "x");

        OperationResult result = CommandInstaller.remove("migrate-node", tempDir);

        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(tempDir.resolve("migrate-node.md")));
    }

    @Test
    void removeIsSkippedWhenNotInstalled(@TempDir Path tempDir) {
        OperationResult result = CommandInstaller.remove("never-installed", tempDir);

        assertTrue(result.success());
        assertTrue(result.skipped());
    }
}
