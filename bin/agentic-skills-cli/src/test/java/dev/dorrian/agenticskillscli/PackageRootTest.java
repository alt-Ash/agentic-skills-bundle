package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(fakePackage.resolve("agentic-skills-hooks.jar"), PackageRoot.hooksJar());
        assertEquals(fakePackage.resolve("issue-tickets.jar"), PackageRoot.issueTicketsMcpJar());
        assertEquals(fakePackage.resolve("security-scanner.jar"), PackageRoot.securityScannerMcpJar());
    }

    @Test
    void initFromArgsParsesThePackageRootFlag(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        PackageRoot.initFromArgs(new String[] {"--package-root", tempDir.toString(), "quick-install"});
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("skills"), PackageRoot.skillsDir());
    }

    /**
     * Without --package-root, the bundle on the classpath is used. Under Maven the test
     * classpath holds target/classes/bundle as a plain directory (copied at process-resources),
     * which is used in place; the jar case (extract to ~/.agentic-skills/dist/<version>) is
     * covered by BundleExtractorTest and AgenticSkillsJarIT.
     */
    @Test
    void initFromArgsWithoutFlagUsesTheClasspathBundle() {
        PackageRoot.initFromArgs(new String[] {"quick-install"});
        assertTrue(Files.isDirectory(PackageRoot.skillsDir()), "skills dir missing under " + PackageRoot.skillsDir());
        assertTrue(Files.isDirectory(PackageRoot.agentsDir()));
    }
}
