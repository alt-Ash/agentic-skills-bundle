package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
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

class AgentInstallerTest {

    @Test
    void installWritesOpencodeAgentUnchangedWithMdExtension(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("plan.md");
        Files.writeString(src, "---\ndescription: A planning agent that is long enough\nmode: subagent\n---\nBody text");
        Path target = tempDir.resolve("installed-agents");

        List<OperationResult> results = AgentInstaller.install(
            List.of(new AgentDescriptor("plan", src, Map.of())), target, "opencode", tempDir
        );

        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertTrue(Files.exists(target.resolve("plan.md")));
    }

    @Test
    void installUsesDotAgentMdExtensionForVscode(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("plan.md");
        Files.writeString(src, "---\ndescription: A planning agent that is long enough\nmode: subagent\n---\nBody text");
        Path target = tempDir.resolve("installed-agents");

        AgentInstaller.install(List.of(new AgentDescriptor("plan", src, Map.of())), target, "vscode", tempDir);

        assertTrue(Files.exists(target.resolve("plan.agent.md")));
        assertFalse(Files.exists(target.resolve("plan.md")));
    }

    @Test
    void installCopiesAndChmodsCompanionFilesForSecurityAuditor(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents-src");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("security-auditor.md"), "---\ndescription: Security auditor agent, long enough\nmode: subagent\n---\nBody");
        Files.writeString(agentsDir.resolve("audit-triage.sh"), "#!/bin/sh\necho hi");
        Files.writeString(agentsDir.resolve("security-scan.sh"), "#!/bin/sh\necho scan");

        Path target = tempDir.resolve("installed-agents");
        List<OperationResult> results = AgentInstaller.install(
            List.of(new AgentDescriptor("security-auditor", agentsDir.resolve("security-auditor.md"), Map.of())),
            target, "opencode", agentsDir
        );

        // Companion copy success is silent — only the main agent file produces a result.
        assertEquals(1, results.size());
        assertTrue(Files.exists(target.resolve("audit-triage.sh")));
        assertTrue(Files.exists(target.resolve("security-scan.sh")));
        assertTrue(Files.isExecutable(target.resolve("audit-triage.sh")));
    }

    @Test
    void removeDeletesAgentFileAndCompanions(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("installed-agents");
        Files.createDirectories(target);
        Files.writeString(target.resolve("security-auditor.md"), "x");
        Files.writeString(target.resolve("audit-triage.sh"), "x");
        Files.writeString(target.resolve("security-scan.sh"), "x");

        OperationResult result = AgentInstaller.remove(
            new AgentDescriptor("security-auditor", Path.of("/unused"), Map.of()), target, "opencode"
        );

        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(target.resolve("security-auditor.md")));
        assertFalse(Files.exists(target.resolve("audit-triage.sh")));
        assertFalse(Files.exists(target.resolve("security-scan.sh")));
    }

    @Test
    void removeIsSkippedWhenAgentFileNotInstalled(@TempDir Path tempDir) {
        OperationResult result = AgentInstaller.remove(
            new AgentDescriptor("plan", Path.of("/unused"), Map.of()), tempDir, "opencode"
        );

        assertTrue(result.success());
        assertTrue(result.skipped());
    }
}
