package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box test of the distributable: spawns {@code java -jar agentic-skills.jar} with no
 * {@code --package-root}, a scratch {@code AGENTIC_SKILLS_HOME_OVERRIDE}, and stdin closed, then
 * asserts the bundle was extracted to {@code <home>/.agentic-skills/dist/<version>/} and the
 * top-level menu rendered. Run by failsafe after {@code package}, so it exercises the real shaded
 * jar (manifest, main class, bundled resources) rather than classes on the test classpath.
 */
class AgenticSkillsJarIT {

    private static final Path JAR = Path.of(System.getProperty("agentic.skills.jar", "target/agentic-skills.jar"));
    private static final String VERSION = System.getProperty("agentic.skills.version", "3.1.0");

    private record Run(int exitCode, String output) {
    }

    @BeforeAll
    static void jarExists() {
        assertTrue(Files.isRegularFile(JAR), "packaged jar not found: " + JAR.toAbsolutePath());
    }

    private static Run runJar(Path home, String... args) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(java.toString(), "-jar", JAR.toAbsolutePath().toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(home.toFile()) // not the repo: the user.dir fallback must not be what's used
            .redirectErrorStream(true);
        pb.environment().put("AGENTIC_SKILLS_HOME_OVERRIDE", home.toString());
        pb.environment().put("AGENTIC_SKILLS_CLAUDE_CLI_OVERRIDE", home.resolve("no-such-claude").toString());
        Process process = pb.start();
        process.getOutputStream().close(); // stdin closed: the first prompt hits EOF
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "jar did not exit");
        return new Run(process.exitValue(), output);
    }

    @Test
    void firstRunExtractsTheBundleAndRendersTheMenu(@TempDir Path home) throws Exception {
        Run run = runJar(home);

        assertTrue(run.output().contains("Agentic  Skills Bundle Installer"), run.output());
        assertTrue(run.output().contains("What do you want to do?"), run.output());
        assertTrue(run.output().contains("Quick install"), run.output());
        assertTrue(run.output().contains("No input received"), run.output());
        assertEquals(1, run.exitCode(), run.output());

        Path dist = home.resolve(".agentic-skills").resolve("dist").resolve(VERSION);
        assertTrue(Files.isDirectory(dist.resolve("skills")), "skills not extracted to " + dist);
        assertTrue(Files.isDirectory(dist.resolve("agents")));
        assertTrue(Files.isDirectory(dist.resolve(".opencode").resolve("commands")));
        assertTrue(Files.isDirectory(dist.resolve("templates").resolve("project")));
        assertTrue(Files.isRegularFile(dist.resolve(BundleExtractor.COMPLETE_MARKER)));

        try (JarFile hooks = new JarFile(dist.resolve("agentic-skills-hooks.jar").toFile())) {
            assertNotNull(hooks.getManifest().getMainAttributes().getValue("Main-Class"));
        }
        for (String mcp : List.of("issue-tickets.jar", "security-scanner.jar")) {
            try (JarFile jar = new JarFile(dist.resolve(mcp).toFile())) {
                // Spring Boot repackaged fat jar, not the thin pre-repackage artifact.
                assertNotNull(jar.getEntry("BOOT-INF/lib/"), mcp + " is not a Spring Boot fat jar");
            }
        }
    }

    @Test
    void secondRunReusesTheExtraction(@TempDir Path home) throws Exception {
        runJar(home);
        Path marker = home.resolve(".agentic-skills/dist").resolve(VERSION).resolve(BundleExtractor.COMPLETE_MARKER);
        FileTime first = Files.getLastModifiedTime(marker);

        Run second = runJar(home);

        assertTrue(second.output().contains("What do you want to do?"), second.output());
        assertEquals(first, Files.getLastModifiedTime(marker));
    }

    @Test
    void versionFlagPrintsTheVersionWithoutExtracting(@TempDir Path home) throws Exception {
        // Homebrew's generated formula test runs `agentic-skills --version` and expects the version.
        Run run = runJar(home, "--version");

        assertEquals(0, run.exitCode(), run.output());
        assertEquals(VERSION, run.output().strip());
        assertTrue(Files.notExists(home.resolve(".agentic-skills")), "--version must not extract the bundle");
    }

    @Test
    void uninstallFlagSkipsTheMenu(@TempDir Path home) throws Exception {
        Run run = runJar(home, "--uninstall");

        assertTrue(run.output().contains("Agentic  Skills Bundle Installer"), run.output());
        assertTrue(!run.output().contains("What do you want to do?"), run.output());
    }
}
