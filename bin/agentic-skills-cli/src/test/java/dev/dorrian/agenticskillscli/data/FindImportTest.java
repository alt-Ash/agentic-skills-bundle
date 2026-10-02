package dev.dorrian.agenticskillscli.data;

import dev.dorrian.usagestore.UsageDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindImportTest {

    private static String events(String id) {
        return "[{\"eventId\":\"" + id + "\",\"ts\":\"2026-10-01T10:00:00Z\",\"event\":\"session_start\",\"sessionId\":\"s\"},"
            + "{\"eventId\":\"" + id + "b\",\"ts\":\"2026-10-01T10:01:00Z\",\"event\":\"tool_use\",\"sessionId\":\"s\"}]";
    }

    private static void put(Path root, String rel, String json) throws Exception {
        Path dir = Files.createDirectories(root.resolve(rel));
        Files.writeString(dir.resolve("ai-usage-events.json"), json);
    }

    private record Out(int code, String out, String err) {
    }

    private static Out run(List<String> args, Path db, Path cwd) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = DataCommands.run(args, db, cwd, new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Out(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void findsNestedFilesHonouringDepthSkipsAndSymlinks(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        put(root, "a", events("a"));
        put(root, "x/y/z", events("z"));
        put(root, "d1/d2/d3/d4/d5", events("deep5"));            // file at depth 6 (find -maxdepth 6): included
        put(root, "d1/d2/d3/d4/d5/d6", events("deep6"));          // file at depth 7: excluded
        put(root, ".hidden/p", events("h"));
        put(root, "node_modules/p", events("n"));
        put(root, "p/target", events("t"));
        put(root, "p/build", events("b"));
        put(root, ".gradle/p", events("g"));
        put(root, ".m2/p", events("m"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        put(outside, "q", events("q"));
        Files.createSymbolicLink(root.resolve("link"), outside);

        List<Path> found = DataCommands.findLegacyDirs(root);

        assertEquals(List.of(root.resolve("a"), root.resolve("d1/d2/d3/d4/d5"), root.resolve("x/y/z")), found);
    }

    @Test
    void importsEveryFoundFileIdempotentlyAndPrintsCounts(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        put(root, "one", events("one"));
        put(root, "two/inner", events("two"));
        Path db = tmp.resolve("u.db");

        Out first = run(List.of("import", "--find", "root"), db, tmp);
        assertEquals(0, first.code());
        assertTrue(first.out().contains("Total: 2 file(s), 4 imported, 0 already present, 0 skipped"), first.out());

        Out second = run(List.of("import", "--find", root.toString()), db, tmp);
        assertEquals(0, second.code());
        assertTrue(second.out().contains("0 imported, 4 already present"), second.out());
        try (UsageDb d = UsageDb.open(db)) {
            assertTrue(Files.isRegularFile(db));
        }
    }

    @Test
    void invalidFileFailsTheRunButOthersStillImport(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        put(root, "good", events("good"));
        put(root, "bad", "{not json");

        Out r = run(List.of("import", "--find", "root"), tmp.resolve("u.db"), tmp);

        assertEquals(1, r.code());
        assertTrue(r.out().contains("2 imported"), r.out());
        assertTrue(r.out().contains("1 invalid"), r.out());
        assertTrue(r.err().contains("Not valid JSON"), r.err());
    }

    @Test
    void usageErrorsAndEmptyScan(@TempDir Path tmp) throws Exception {
        assertEquals(2, run(List.of("import", "--find"), tmp.resolve("u.db"), tmp).code());
        assertEquals(1, run(List.of("import", "--find", "nope"), tmp.resolve("u.db"), tmp).code());
        Files.createDirectories(tmp.resolve("empty"));
        Out r = run(List.of("import", "--find", "empty"), tmp.resolve("u.db"), tmp);
        assertEquals(0, r.code());
        assertFalse(r.out().contains("imported"));
        assertEquals(2, run(List.of("bogus"), tmp.resolve("u.db"), tmp).code());
    }
}
