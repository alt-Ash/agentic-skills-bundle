package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCommandTest {

    @TempDir Path tmp;

    private Path bundle;
    private Path home;
    private AgentToolDef claude;
    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        bundle = tmp.resolve("bundle");
        home = tmp.resolve("home");
        write(bundle.resolve("skills/cat/skill-a/SKILL.md"), "new skill-a");
        write(bundle.resolve("skills/cat/skill-b/SKILL.md"), "new skill-b");
        write(bundle.resolve("agents/pr-reviewer.md"),
            "---\ndescription: Reviews pending changes before a pull request is opened\nmode: subagent\n---\n\n## Body\nnew agent\n");
        write(bundle.resolve("commands/pr-check.md"), "new command");
        for (String f : List.of("AGENT.md", "ARCHITECTURE.md", "CLAUDE.md", "DESIGN.md", "GLOSSARY.md", "MEMORY.md")) {
            write(bundle.resolve("templates/" + f), "new template " + f);
        }
        claude = new AgentToolDef("claude", "Claude Code",
            home.resolve("skills"), ".claude/skills",
            home.resolve("commands"), ".claude/commands",
            home.resolve("agents"), ".claude/agents",
            null, null, true, true, home);
    }

    private UpgradeEnvironment env(FakeHooks hooks, List<UpgradeEnvironment.McpJar> mcps) {
        return new UpgradeEnvironment(Map.of("claude", claude), Set.of("claude"),
            bundle.resolve("skills"), bundle.resolve("agents"), bundle.resolve("commands"),
            bundle.resolve("templates"), hooks, mcps);
    }

    private int run(UpgradeEnvironment env, String... args) {
        return UpgradeCommand.run(List.of(args), env, out, err);
    }

    private String output() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void installOldGlobalCopy() throws IOException {
        write(home.resolve("skills/skill-a/SKILL.md"), "old skill-a");
        write(home.resolve("skills/skill-a/stale.txt"), "stale");
        write(home.resolve("agents/pr-reviewer.md"), "old agent");
        write(home.resolve("commands/pr-check.md"), "old command");
        write(home.resolve("agents/templates/AGENT.md"), "old template");
    }

    @Test
    void nothingInstalledIsACleanNoOp() {
        int code = run(env(new FakeHooks(false), List.of()));

        assertEquals(0, code);
        assertTrue(output().contains("Nothing to upgrade"));
        assertFalse(Files.exists(home));
    }

    @Test
    void refreshOverwritesInstalledContentOnlyAndLeavesStaleFiles() throws IOException {
        installOldGlobalCopy();

        int code = run(env(new FakeHooks(false), List.of()));

        assertEquals(0, code, output());
        assertEquals("new skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(Files.exists(home.resolve("skills/skill-a/stale.txt")), "stale files are not deleted");
        assertFalse(Files.exists(home.resolve("skills/skill-b")), "uninstalled skills are not added");
        assertEquals("new command", Files.readString(home.resolve("commands/pr-check.md")));
        assertTrue(Files.readString(home.resolve("agents/pr-reviewer.md")).contains("new agent"));
        assertEquals("new template AGENT.md", Files.readString(home.resolve("agents/templates/AGENT.md")));
        assertTrue(output().contains("refresh skill skill-a for claude"));
        assertTrue(output().contains("0 failed"));
    }

    @Test
    void secondRunIsIdempotent() throws IOException {
        installOldGlobalCopy();
        UpgradeEnvironment env = env(new FakeHooks(false), List.of());

        assertEquals(0, run(env));
        String first = Files.readString(home.resolve("agents/pr-reviewer.md"));
        assertEquals(0, run(env));
        assertEquals(first, Files.readString(home.resolve("agents/pr-reviewer.md")));
    }

    @Test
    void dryRunPrintsThePlanAndWritesNothing() throws IOException {
        installOldGlobalCopy();
        FakeHooks hooks = new FakeHooks(true);

        int code = run(env(hooks, List.of()), "--dry-run");

        assertEquals(0, code);
        assertTrue(output().contains("refresh skill skill-a for claude"));
        assertTrue(output().contains("refresh agent pr-reviewer for claude"));
        assertTrue(output().contains("refresh hooks for claude"));
        assertEquals("old skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertEquals("old agent", Files.readString(home.resolve("agents/pr-reviewer.md")));
        assertEquals("old command", Files.readString(home.resolve("commands/pr-check.md")));
        assertTrue(hooks.installed.isEmpty());
    }

    @Test
    void hooksAreReinstalledWithTheirCurrentOptIns() throws IOException {
        installOldGlobalCopy();
        FakeHooks hooks = new FakeHooks(true);
        hooks.options = new HookInstallOptions(true, false, true);

        assertEquals(0, run(env(hooks, List.of())));

        assertEquals(List.of(new HookInstallOptions(true, false, true)), hooks.installed);
    }

    @Test
    void mcpJarIsRefreshedOnlyIfAlreadyInstalled() throws IOException {
        write(bundle.resolve("issue-tickets.jar"), "new jar");
        write(bundle.resolve("security-scanner.jar"), "new jar");
        Path installedDir = tmp.resolve("mcp/issue-tickets");
        Path absentDir = tmp.resolve("mcp/security-scanner");
        write(installedDir.resolve("issue-tickets.jar"), "old jar");
        List<UpgradeEnvironment.McpJar> mcps = List.of(
            new UpgradeEnvironment.McpJar("issue-tickets", bundle.resolve("issue-tickets.jar"), installedDir,
                "issue-tickets.jar", UpgradeCommandTest::copyJar),
            new UpgradeEnvironment.McpJar("security-scanner", bundle.resolve("security-scanner.jar"), absentDir,
                "security-scanner.jar", UpgradeCommandTest::copyJar));

        assertEquals(0, run(env(new FakeHooks(false), mcps)));

        assertEquals("new jar", Files.readString(installedDir.resolve("issue-tickets.jar")));
        assertFalse(Files.exists(absentDir));
    }

    private static void copyJar(Path bundled, Path dir) {
        try {
            Files.createDirectories(dir);
            Files.copy(bundled, dir.resolve(bundled.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void oneFailingOpGivesExitOneAndTheOthersStillRun() throws IOException {
        installOldGlobalCopy();
        FakeHooks hooks = new FakeHooks(true);
        hooks.fail = true;

        int code = run(env(hooks, List.of()));

        assertEquals(1, code);
        assertTrue(output().contains("FAILED refresh hooks for claude"));
        assertEquals("new skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(Files.readString(home.resolve("agents/pr-reviewer.md")).contains("new agent"));
        assertTrue(output().contains("1 failed"));
    }

    @Test
    void projectFlagRefreshesProjectInstallsViaTheProjectFolders() throws IOException {
        Path project = tmp.resolve("proj");
        write(project.resolve(".claude/skills/skill-b/SKILL.md"), "old skill-b");
        write(project.resolve(".claude/commands/pr-check.md"), "old command");
        write(project.resolve(".claude/agents/pr-reviewer.md"), "old agent");

        int code = run(env(new FakeHooks(false), List.of()), "--project", project.toString());

        assertEquals(0, code, output());
        assertEquals("new skill-b", Files.readString(project.resolve(".claude/skills/skill-b/SKILL.md")));
        assertEquals("new command", Files.readString(project.resolve(".claude/commands/pr-check.md")));
        assertTrue(Files.readString(project.resolve(".claude/agents/pr-reviewer.md")).contains("new agent"));
        assertTrue(output().contains("[project " + project.toAbsolutePath().normalize() + "]"));
        assertFalse(Files.exists(home), "global locations untouched");
    }

    @Test
    void badArgumentsExitTwo() {
        UpgradeEnvironment env = env(new FakeHooks(false), List.of());

        assertEquals(2, run(env, "--bogus"));
        assertEquals(2, run(env, "--project"));
        assertEquals(2, run(env, "--project", tmp.resolve("does-not-exist").toString()));
        assertEquals(2, run(env, "--package-root"));
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("usage: agentic-skills upgrade"));
    }

    private static final class FakeHooks implements UpgradeEnvironment.Hooks {
        final boolean present;
        boolean fail;
        HookInstallOptions options = HookInstallOptions.NONE;
        final List<HookInstallOptions> installed = new ArrayList<>();

        FakeHooks(boolean present) {
            this.present = present;
        }

        @Override public boolean supports(String toolKey) {
            return "claude".equals(toolKey);
        }

        @Override public boolean isInstalled(String toolKey) {
            return present;
        }

        @Override public HookInstallOptions currentOptions(String toolKey) {
            return options;
        }

        @Override public void install(String toolKey, HookInstallOptions opts) {
            if (fail) throw new IllegalStateException("boom");
            installed.add(opts);
        }
    }
}
