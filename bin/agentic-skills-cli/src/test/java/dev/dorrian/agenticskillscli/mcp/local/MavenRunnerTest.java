package dev.dorrian.agenticskillscli.mcp.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenRunnerTest {

    @Test
    void checkAvailableDoesNotThrowWhenMavenIsOnPath() {
        // This test suite itself runs via `mvn test`, so mvn is guaranteed to
        // be resolvable on PATH in this execution environment.
        assertDoesNotThrow(MavenRunner::checkAvailable);
    }

    @Test
    void packageProjectThrowsWhenDirectoryHasNoPomXml(@TempDir Path emptyDir) {
        assertThrows(RuntimeException.class, () -> MavenRunner.packageProject(emptyDir));
    }
}
