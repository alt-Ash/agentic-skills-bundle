package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardConfigTest {

    private static Optional<GuardHook.Denial> bash(GuardConfig config, String command) {
        return GuardHook.evaluate(HookInput.parse("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\""
            + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}"), config);
    }

    private static Optional<GuardHook.Denial> path(GuardConfig config, String tool, String path) {
        return GuardHook.evaluate(HookInput.parse("{\"tool_name\":\"" + tool + "\",\"tool_input\":{\"file_path\":\""
            + path.replace("\\", "\\\\") + "\"}}"), config);
    }

    private static void write(Path dir, String json) throws Exception {
        Path f = dir.resolve(".agentic-skills/guard.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json);
    }

    @Test
    void noFilesMeansDefaults(@TempDir Path home, @TempDir Path project) {
        GuardConfig c = GuardConfig.load(home, project);
        assertTrue(bash(c, "rm -rf /").isPresent());
        assertTrue(c.denyRules().isEmpty());
    }

    @Test
    void disableTurnsOffOnlyThatRule() {
        GuardConfig c = GuardConfig.parse("{\"disable\":[\"force-push\",\"secrets-file\"]}");
        assertTrue(bash(c, "git push -f origin main").isEmpty());
        assertTrue(path(c, "Read", "/p/.env").isEmpty());
        assertTrue(bash(c, "rm -rf /").isPresent());
        assertTrue(bash(c, "cat .env").isPresent());
    }

    @Test
    void allowExemptsFromAllRulesIncludingCustomDeny() {
        GuardConfig c = GuardConfig.parse(
            "{\"allow\":[\"^rm -rf /\\\\s*$\", \"/safe/.env$\"],\"deny\":[{\"pattern\":\"rm\",\"reason\":\"r\"}]}");
        assertTrue(bash(c, "rm -rf /").isEmpty());
        assertTrue(path(c, "Read", "/safe/.env").isEmpty());
        assertTrue(path(c, "Read", "/other/.env").isPresent());
    }

    @Test
    void customDenyIsPrefixedAndScopedToItsTools() {
        GuardConfig c = GuardConfig.parse(
            "{\"deny\":[{\"pattern\":\"terraform\\\\s+destroy\",\"reason\":\"no destroys\"},"
                + "{\"pattern\":\"\\\\.tfstate$\",\"reason\":\"state\",\"tools\":[\"Read\"]}]}");
        assertEquals("custom: no destroys", bash(c, "terraform destroy -auto-approve").orElseThrow().rule());
        assertTrue(bash(c, "terraform plan").isEmpty());
        assertTrue(bash(c, "cat prod.tfstate").isEmpty());
        assertEquals("custom: state", path(c, "Read", "/p/prod.tfstate").orElseThrow().rule());
        assertTrue(path(c, "Write", "/p/prod.tfstate").isEmpty());
    }

    @Test
    void builtInRulesWinOverCustomOnes() {
        GuardConfig c = GuardConfig.parse("{\"deny\":[{\"pattern\":\"rm\",\"reason\":\"x\"}]}");
        assertEquals("recursive delete of a root, home or wildcard path", bash(c, "rm -rf /").orElseThrow().rule());
    }

    @Test
    void projectExtendsUserAndOverridesSamePattern(@TempDir Path home, @TempDir Path project) throws Exception {
        write(home, "{\"disable\":[\"env-read\"],\"allow\":[\"^ls\"],"
            + "\"deny\":[{\"pattern\":\"foo\",\"reason\":\"user foo\"},{\"pattern\":\"bar\",\"reason\":\"user bar\"}]}");
        write(project, "{\"disable\":[\"force-push\"],\"deny\":[{\"pattern\":\"foo\",\"reason\":\"project foo\"}]}");
        GuardConfig c = GuardConfig.load(home, project);

        assertTrue(c.isDisabled("env-read"));
        assertTrue(c.isDisabled("force-push"));
        assertFalse(c.isDisabled("recursive-delete"));
        assertTrue(c.isAllowed("ls -la"));
        assertEquals("custom: project foo", bash(c, "foo").orElseThrow().rule());
        assertEquals("custom: user bar", bash(c, "bar").orElseThrow().rule());
        assertEquals(2, c.denyRules().size());
    }

    @Test
    void invalidAndCatastrophicRegexesAreIgnoredButOthersKept() {
        GuardConfig c = GuardConfig.parse("{\"allow\":[\"([unclosed\",\"(a+)+$\",\"^ls\"],"
            + "\"deny\":[{\"pattern\":\"(x*)*y\",\"reason\":\"bad\"},{\"pattern\":\"[\",\"reason\":\"bad\"},"
            + "{\"pattern\":\"ok\",\"reason\":\"good\"}]}");
        assertTrue(c.isAllowed("ls"));
        assertFalse(c.isAllowed("aaaa"));
        assertEquals(1, c.denyRules().size());
        assertNull(GuardConfig.safeCompile("(a+)+"));
        assertNull(GuardConfig.safeCompile("x".repeat(GuardConfig.MAX_PATTERN_LENGTH + 1)));
    }

    @Test
    void malformedContentFallsBackToDefaults() {
        for (String bad : new String[] {"", "not json", "[]", "\"x\"", "{\"disable\":\"env-read\"}",
            "{\"deny\":[1,{\"pattern\":5},{\"reason\":\"no pattern\"}],\"allow\":[null,3]}"}) {
            GuardConfig c = GuardConfig.parse(bad);
            assertTrue(c.denyRules().isEmpty(), bad);
            assertFalse(c.isDisabled("env-read"), bad);
            assertTrue(bash(c, "rm -rf /").isPresent(), bad);
        }
    }

    @Test
    void oversizedFileIsIgnored(@TempDir Path home, @TempDir Path project) throws Exception {
        String pad = " ".repeat((int) GuardConfig.MAX_FILE_BYTES);
        write(project, "{\"disable\":[\"recursive-delete\"]" + pad + "}");
        assertTrue(bash(GuardConfig.load(home, project), "rm -rf /").isPresent());
        write(project, "{\"disable\":[\"recursive-delete\"]}");
        assertTrue(bash(GuardConfig.load(home, project), "rm -rf /").isEmpty());
    }

    @Test
    void timedOutMatchCountsAsNoMatch() {
        // (a|aa)+ is exponential on a long run of 'a' followed by a mismatch; the nested-quantifier
        // heuristic does not catch this alternation form, so the runtime budget must.
        Pattern evil = Pattern.compile("^(a|aa)+$");
        String input = "a".repeat(5_000) + "!";
        long start = System.nanoTime();
        assertFalse(BoundedMatcher.find(evil, input, 50_000_000L));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000, "matcher must give up promptly");
    }

    @Test
    void overlongInputIsClippedToHeadAndTail() {
        String big = "a".repeat(200_000);
        assertTrue(BoundedMatcher.clip(big).length() <= BoundedMatcher.MAX_INPUT_CHARS + 1);
        assertTrue(BoundedMatcher.find(Pattern.compile("^a+$"), "a".repeat(10)));
    }
}
