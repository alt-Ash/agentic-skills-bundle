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
        return env(hooks, mcps, null);
    }

    private UpgradeEnvironment env(FakeHooks hooks, List<UpgradeEnvironment.McpJar> mcps, Path manifestFile) {
        return new UpgradeEnvironment(Map.of("claude", claude), Set.of("claude"),
            bundle.resolve("skills"), bundle.resolve("agents"), bundle.resolve("commands"),
            bundle.resolve("templates"), hooks, mcps, manifestFile, "test");
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
        assertTrue(output().contains("refreshed") && output().contains("skill  skill-a"), output());
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
        assertTrue(output().startsWith("Dry run: nothing written; 'refreshed' lines show what would be refreshed."), output());
        assertTrue(output().contains("skill  skill-a"), output());
        assertTrue(output().contains("agent  pr-reviewer"), output());
        assertTrue(output().contains("hooks  analytics"), output());
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
        assertTrue(output().contains("FAILED") && output().contains("hooks  analytics: boom"), output());
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

    // ---- diff-aware behaviour ----

    /** Stale files inside a skill directory make its tree differ from the bundle forever, so drop it. */
    private void installOldGlobalCopyWithoutStaleFile() throws IOException {
        installOldGlobalCopy();
        Files.delete(home.resolve("skills/skill-a/stale.txt"));
    }

    private Path manifestFile() {
        return tmp.resolve("state/installed.json");
    }

    private void ageFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000L));
            }
        }
    }

    private static long mtime(Path p) throws IOException {
        return Files.getLastModifiedTime(p).toMillis();
    }

    private void resetOutput() {
        outBuf.reset();
        errBuf.reset();
    }

    @Test
    void secondRealRunReportsZeroRefreshedAndDoesNotRewrite() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());

        assertEquals(0, run(env), output());
        assertTrue(Files.exists(manifestFile()));
        ageFiles(home);
        long skillTime = mtime(home.resolve("skills/skill-a/SKILL.md"));
        long agentTime = mtime(home.resolve("agents/pr-reviewer.md"));
        resetOutput();

        assertEquals(0, run(env), output());

        assertEquals(skillTime, mtime(home.resolve("skills/skill-a/SKILL.md")));
        assertEquals(agentTime, mtime(home.resolve("agents/pr-reviewer.md")));
        assertTrue(output().contains("0 refreshed"), output());
        assertTrue(output().contains("unchanged"), output());
    }

    @Test
    void extraStaleFileInASkillDirIsNotRewrittenOnASecondRun() throws IOException {
        installOldGlobalCopy(); // keeps skills/skill-a/stale.txt
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());

        assertEquals(0, run(env), output());
        ageFiles(home);
        long skillTime = mtime(home.resolve("skills/skill-a/SKILL.md"));
        resetOutput();

        assertEquals(0, run(env), output());

        assertEquals(skillTime, mtime(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(Files.exists(home.resolve("skills/skill-a/stale.txt")));
        assertTrue(output().contains("0 refreshed"), output());
    }

    @Test
    void fullSecondRunWithCurrentHooksReportsZeroRefreshed() throws IOException {
        installOldGlobalCopy();
        FakeHooks hooks = new FakeHooks(true);
        UpgradeEnvironment env = env(hooks, List.of(), manifestFile());

        assertEquals(0, run(env), output());
        assertEquals(1, hooks.installed.size(), "stale jar: hooks refreshed once");
        hooks.current = true;
        resetOutput();

        assertEquals(0, run(env), output());

        assertEquals(1, hooks.installed.size(), "current jar: hooks not reinstalled");
        assertTrue(output().contains("0 refreshed"), output());
    }

    @Test
    void changedSkillAndAgentAreRefreshedAndOthersAreLeftAlone() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());
        assertEquals(0, run(env));
        ageFiles(home);
        long commandTime = mtime(home.resolve("commands/pr-check.md"));
        write(bundle.resolve("skills/cat/skill-a/SKILL.md"), "newer skill-a");
        write(bundle.resolve("agents/pr-reviewer.md"),
            "---\ndescription: Reviews pending changes before a pull request is opened\nmode: subagent\n---\n\n## Body\nnewer agent\n");
        resetOutput();

        assertEquals(0, run(env), output());

        assertEquals("newer skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(Files.readString(home.resolve("agents/pr-reviewer.md")).contains("newer agent"));
        assertEquals(commandTime, mtime(home.resolve("commands/pr-check.md")), "unchanged command untouched");
        assertTrue(output().contains("2 refreshed"), output());
    }

    @Test
    void locallyModifiedItemIsSkippedAndReported() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());
        assertEquals(0, run(env));
        write(home.resolve("skills/skill-a/SKILL.md"), "my local edit");
        write(bundle.resolve("skills/cat/skill-a/SKILL.md"), "newer skill-a");
        resetOutput();

        assertEquals(0, run(env), output());

        assertEquals("my local edit", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(output().contains("skipped"), output());
        assertTrue(output().contains("skill-a"), output());
        assertTrue(output().contains("1 skipped (modified locally)"), output());
    }

    @Test
    void newMcpJarAndNewSkillAreListedButNotInstalled() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        write(bundle.resolve("issue-tickets.jar"), "jar");
        Path installedDir = tmp.resolve("mcp/issue-tickets");
        write(installedDir.resolve("issue-tickets.jar"), "jar");
        UpgradeEnvironment.McpJar tickets = new UpgradeEnvironment.McpJar("issue-tickets",
            bundle.resolve("issue-tickets.jar"), installedDir, "issue-tickets.jar", UpgradeCommandTest::copyJar);
        assertEquals(0, run(env(new FakeHooks(false), List.of(tickets), manifestFile())), output());

        write(bundle.resolve("skills/cat/skill-c/SKILL.md"), "brand new skill");
        write(bundle.resolve("scanner.jar"), "scanner");
        Path scannerDir = tmp.resolve("mcp/security-scanner");
        UpgradeEnvironment.McpJar scanner = new UpgradeEnvironment.McpJar("security-scanner",
            bundle.resolve("scanner.jar"), scannerDir, "scanner.jar", UpgradeCommandTest::copyJar);
        resetOutput();

        assertEquals(0, run(env(new FakeHooks(false), List.of(tickets, scanner), manifestFile())), output());

        assertTrue(output().contains("Not installed by upgrade"), output());
        assertTrue(output().contains("skill-c"), output());
        assertTrue(output().contains("security-scanner"), output());
        assertFalse(output().contains("skill-b"), "known-before but uninstalled items are not listed");
        assertFalse(Files.exists(home.resolve("skills/skill-c")));
        assertFalse(Files.exists(scannerDir));
        assertFalse(output().contains("No previous install record"));
    }

    @Test
    void preManifestInstallPrintsTheBaselineNote() throws IOException {
        installOldGlobalCopyWithoutStaleFile();

        assertEquals(0, run(env(new FakeHooks(false), List.of(), manifestFile())), output());
        assertTrue(output().contains("No previous install record, so new items cannot be told apart. "
            + "Run `agentic-skills` and choose Install to see everything not installed."), output());
        assertFalse(output().contains("Not installed by upgrade"));

        resetOutput();
        assertEquals(0, run(env(new FakeHooks(false), List.of(), manifestFile())));
        assertFalse(output().contains("No previous install record"), "baseline recorded by the first run");
    }

    @Test
    void dryRunWritesNothingNotEvenTheManifest() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        ageFiles(home);
        long skillTime = mtime(home.resolve("skills/skill-a/SKILL.md"));

        assertEquals(0, run(env(new FakeHooks(false), List.of(), manifestFile()), "--dry-run"), output());

        assertFalse(Files.exists(manifestFile()));
        assertEquals("old skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertEquals(skillTime, mtime(home.resolve("skills/skill-a/SKILL.md")));
        assertTrue(output().contains("refreshed"), output());
    }

    @Test
    void failingMcpInstallerDoesNotStopTheRestAndExitsOne() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        write(bundle.resolve("issue-tickets.jar"), "new jar");
        Path installedDir = tmp.resolve("mcp/issue-tickets");
        write(installedDir.resolve("issue-tickets.jar"), "old jar");
        UpgradeEnvironment.McpJar bad = new UpgradeEnvironment.McpJar("issue-tickets",
            bundle.resolve("issue-tickets.jar"), installedDir, "issue-tickets.jar",
            (b, d) -> { throw new IllegalStateException("disk full"); });

        int code = run(env(new FakeHooks(false), List.of(bad), manifestFile()));

        assertEquals(1, code);
        assertTrue(output().contains("mcp  issue-tickets: disk full"), output());
        assertEquals("new skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
        assertEquals("old jar", Files.readString(installedDir.resolve("issue-tickets.jar")));
    }

    @Test
    void manifestSaveFailureOnlyWarns() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        Path blocker = tmp.resolve("blocker");
        write(blocker, "a file, not a directory");

        int code = run(env(new FakeHooks(false), List.of(), blocker.resolve("installed.json")));

        assertEquals(0, code, output());
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("could not save the install record"));
    }

    private static final class FakeHooks implements UpgradeEnvironment.Hooks {
        final boolean present;
        boolean fail;
        boolean current;
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

        @Override public boolean isCurrent(String toolKey) {
            return current;
        }

        @Override public void install(String toolKey, HookInstallOptions opts) {
            if (fail) throw new IllegalStateException("boom");
            installed.add(opts);
        }
    }

    @Test
    void unselectedNewItemStaysListedOnTheNextUpgrade() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());
        assertEquals(0, run(env), output());
        write(bundle.resolve("skills/cat/skill-c/SKILL.md"), "brand new skill");

        for (int i = 0; i < 2; i++) {
            resetOutput();
            assertEquals(0, run(env), output());
            assertTrue(output().contains("Not installed by upgrade") && output().contains("skill-c"),
                "run " + i + ": " + output());
        }
    }

    @Test
    void failedRunDoesNotAdvanceTheBundleVersionButKeepsItemHashes() throws IOException {
        installOldGlobalCopyWithoutStaleFile();
        assertEquals(0, run(env(new FakeHooks(false), List.of(), manifestFile())), output());
        var before = dev.dorrian.agenticskillscli.state.InstallManifest.load(manifestFile());
        assertEquals("test", before.bundleVersion());

        write(bundle.resolve("skills/cat/skill-a/SKILL.md"), "newer skill-a");
        FakeHooks hooks = new FakeHooks(true);
        hooks.fail = true;
        UpgradeEnvironment base = env(hooks, List.of(), manifestFile());
        UpgradeEnvironment v2 = new UpgradeEnvironment(base.tools(), base.detectedTools(), base.skillsDir(),
            base.agentsDir(), base.commandsDir(), base.templatesDir(), hooks, List.of(), manifestFile(), "v2");

        assertEquals(1, run(v2), output());

        var after = dev.dorrian.agenticskillscli.state.InstallManifest.load(manifestFile());
        assertEquals("test", after.bundleVersion(), "failed run keeps the old version");
        assertEquals("newer skill-a", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
    }

    @Test
    void upToDateItemsWithoutARecordGetTheirLiveHashRecordedOnRealRunsOnly() throws IOException {
        write(home.resolve("skills/skill-a/SKILL.md"), "new skill-a");
        write(home.resolve("commands/pr-check.md"), "new command");
        String skillKey = dev.dorrian.agenticskillscli.state.InstallManifest.key(
            "global", "claude", dev.dorrian.agenticskillscli.state.ItemKind.SKILL, "skill-a");
        UpgradeEnvironment env = env(new FakeHooks(false), List.of(), manifestFile());

        assertEquals(0, run(env, "--dry-run"), output());
        assertFalse(Files.exists(manifestFile()));

        assertEquals(0, run(env), output());
        var m = dev.dorrian.agenticskillscli.state.InstallManifest.load(manifestFile());
        assertTrue(m.hash(skillKey).isPresent());
        assertEquals(java.util.List.of("SKILL.md"), m.files(skillKey).orElseThrow());

        // a later local edit followed by a newer bundle is now seen as modified, not clobbered
        write(home.resolve("skills/skill-a/SKILL.md"), "my edit");
        write(bundle.resolve("skills/cat/skill-a/SKILL.md"), "newer skill-a");
        resetOutput();
        assertEquals(0, run(env), output());
        assertEquals("my edit", Files.readString(home.resolve("skills/skill-a/SKILL.md")));
    }
}
