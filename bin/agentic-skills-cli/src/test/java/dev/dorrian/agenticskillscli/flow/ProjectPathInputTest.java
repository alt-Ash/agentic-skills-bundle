package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectPathInputTest {

    private static final Path CWD = Path.of("/work/current");
    private static final Path HOME = Path.of("/Users/me");

    private static Path resolve(String raw) {
        return ProjectPathInput.resolve(raw, CWD, HOME);
    }

    @Test
    void blankInputMeansTheCurrentDirectory() {
        assertEquals(CWD, resolve(""));
        assertEquals(CWD, resolve("   "));
        assertEquals(CWD, resolve(null));
    }

    @Test
    void absolutePathIsUsedAsIs() {
        assertEquals(Path.of("/Users/me/Development/app"), resolve("/Users/me/Development/app"));
        assertEquals(Path.of("/Users/me/Development/app"), resolve("/Users/me/Development/app/"));
    }

    @Test
    void tildeExpandsToTheHomeDirectoryInsteadOfBeingAppendedToTheCurrentOne() {
        // Regression: "~/Development/x" used to become "<cwd>/~/Development/x".
        assertEquals(Path.of("/Users/me/Development/Projects/agentic-skills-bundle"),
            resolve("~/Development/Projects/agentic-skills-bundle"));
        assertEquals(HOME, resolve("~"));
        assertEquals(HOME, resolve("~/"));
    }

    @Test
    void homeVariableExpands() {
        assertEquals(Path.of("/Users/me/code"), resolve("$HOME/code"));
        assertEquals(Path.of("/Users/me/code"), resolve("${HOME}/code"));
    }

    @Test
    void relativePathResolvesAgainstTheCurrentDirectory() {
        assertEquals(Path.of("/work/current/sub/project"), resolve("sub/project"));
        assertEquals(Path.of("/work/sibling"), resolve("../sibling"));
        assertEquals(CWD, resolve("."));
    }

    @Test
    void surroundingWhitespaceAndQuotesFromPastingAreStripped() {
        assertEquals(Path.of("/Users/me/My Projects/app"), resolve("  '/Users/me/My Projects/app'  "));
        assertEquals(Path.of("/Users/me/My Projects/app"), resolve("\"/Users/me/My Projects/app\""));
        assertEquals(Path.of("/Users/me/My Projects/app"), resolve("\"~/My Projects/app\""));
    }

    @Test
    void backslashEscapedSpacesFromTerminalDragAndDropAreUnescaped() {
        assertEquals(Path.of("/Users/me/My Projects/app"), resolve("/Users/me/My\\ Projects/app"));
    }

    @Test
    void aTildeInsideAPathIsLeftAlone() {
        assertEquals(Path.of("/work/current/backup~/x"), resolve("backup~/x"));
    }
}
