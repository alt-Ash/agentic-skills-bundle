package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.registry.TemplateRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateInstallerTest {

    @Test
    void copiesEveryRegisteredTemplateIntoAgentsGlobalPathTemplatesSubdir(@TempDir Path tempDir) throws IOException {
        Path projectTemplatesDir = tempDir.resolve("templates-src");
        Files.createDirectories(projectTemplatesDir);
        for (String file : TemplateRegistry.FILES) {
            Files.writeString(projectTemplatesDir.resolve(file), "content of " + file);
        }

        Path agentsGlobalPath = tempDir.resolve("agents-global");
        List<OperationResult> results = TemplateInstaller.install(agentsGlobalPath, projectTemplatesDir);

        assertEquals(TemplateRegistry.FILES.size(), results.size());
        assertTrue(results.stream().allMatch(OperationResult::success));
        for (String file : TemplateRegistry.FILES) {
            assertEquals("content of " + file, Files.readString(agentsGlobalPath.resolve("templates").resolve(file)));
        }
    }

    @Test
    void reportsFailureForATemplateMissingFromSource(@TempDir Path tempDir) {
        Path projectTemplatesDir = tempDir.resolve("empty-templates-src");
        Path agentsGlobalPath = tempDir.resolve("agents-global");

        List<OperationResult> results = TemplateInstaller.install(agentsGlobalPath, projectTemplatesDir);

        assertEquals(TemplateRegistry.FILES.size(), results.size());
        assertTrue(results.stream().noneMatch(OperationResult::success));
    }
}
