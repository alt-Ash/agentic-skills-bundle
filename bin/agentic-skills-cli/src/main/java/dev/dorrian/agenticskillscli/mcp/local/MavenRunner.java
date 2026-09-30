package dev.dorrian.agenticskillscli.mcp.local;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Java port of {@code bin/install.js}'s {@code checkMavenAvailable()} and
 * the shared {@code mvn -q -DskipTests package} invocation used by both the
 * issue-tickets and security-scanner Java-MCP installers.
 */
public final class MavenRunner {

    private MavenRunner() {
    }

    /**
     * Throws a clear, actionable error if {@code mvn} isn't on PATH. Unlike
     * npm/pnpm (interchangeable JS package managers with a documented
     * fallback), there's no equivalent alternate build tool to fall back to
     * for a Maven project, so this fails hard immediately rather than
     * surfacing an opaque process-start failure deep inside the package call.
     */
    public static void checkAvailable() {
        try {
            Process process = new ProcessBuilder("mvn", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MavenUnavailableException();
            }
        } catch (IOException | InterruptedException e) {
            throw new MavenUnavailableException();
        }
    }

    /** Builds the Maven project at {@code sourceDir}, skipping tests. */
    public static void packageProject(Path sourceDir) {
        try {
            Process process = new ProcessBuilder("mvn", "-q", "-DskipTests", "package")
                .directory(sourceDir.toFile())
                .inheritIO()
                .start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new RuntimeException("mvn -q -DskipTests package failed in " + sourceDir + " with exit code " + exit);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run Maven in " + sourceDir, e);
        }
    }

    public static final class MavenUnavailableException extends RuntimeException {
        MavenUnavailableException() {
            super(
                "Maven (`mvn`) is required to build the issue-tickets/security-scanner MCP servers "
                    + "(Java/Spring, not Node) but was not found on PATH. Install Java 21+ and Maven, then retry."
            );
        }
    }
}
