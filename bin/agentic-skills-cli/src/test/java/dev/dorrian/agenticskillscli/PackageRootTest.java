package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageRootTest {

    @Test
    void resolvesEveryPathRelativeToAnExplicitPackageRoot(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path fakePackage = tempDir.resolve("agentic-skills-bundle");
        Files.createDirectories(fakePackage);

        PackageRoot.init(fakePackage);

        assertEquals(fakePackage.resolve("skills"), PackageRoot.skillsDir());
        assertEquals(fakePackage.resolve("agents"), PackageRoot.agentsDir());
        assertEquals(fakePackage.resolve(".opencode").resolve("commands"), PackageRoot.commandsDir());
        assertEquals(fakePackage.resolve("templates").resolve("project"), PackageRoot.templatesDir());
        assertEquals(fakePackage.resolve("mcp").resolve("issue-tickets"), PackageRoot.obTicketsMcpSrc());
        assertEquals(fakePackage.resolve("mcp").resolve("security-scanner"), PackageRoot.securityScannerMcpSrc());
    }

    @Test
    void initFromArgsParsesThePackageRootFlag(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        PackageRoot.initFromArgs(new String[] {"--package-root", tempDir.toString(), "quick-install"});
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("skills"), PackageRoot.skillsDir());
    }

    @Test
    void initFromArgsFallsBackToCurrentWorkingDirectoryWhenFlagAbsent() {
        PackageRoot.initFromArgs(new String[] {"quick-install"});
        assertEquals(Path.of(System.getProperty("user.dir")).resolve("skills"), PackageRoot.skillsDir());
    }
}
