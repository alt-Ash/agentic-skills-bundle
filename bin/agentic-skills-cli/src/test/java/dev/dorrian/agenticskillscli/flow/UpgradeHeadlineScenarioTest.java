package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.install.TemplateInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.TemplateRegistry;
import dev.dorrian.agenticskillscli.state.BundleCatalog;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallManifest;
import dev.dorrian.agenticskillscli.state.ItemKind;
import dev.dorrian.agenticskillscli.state.ManifestRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The headline upgrade scenario, end to end, against temp directories only. */
class UpgradeHeadlineScenarioTest {

    private static final FileTime OLD = FileTime.fromMillis(1_000_000_000L);
    // Upgrade only sees commands that CommandRegistry maps, so the "stable command" is the registered pr-check.
    private static final String CMD = "pr-check";
    private static final byte[] CONFIG_BYTES = "{\"mcpServers\":{\"old-mcp\":{\"command\":\"java\"}}}\n"
        .getBytes(StandardCharsets.UTF_8);

    @TempDir Path tmp;

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

    /** One installed "3.0.0" home plus the "3.1.0" bundle to upgrade it to. */
    private static final class Setup {
        Path home;
        Path manifestFile;
        Path config;
        BundleFixture.Bundle v31;
        AgentToolDef claude;
        UpgradeEnvironment env;
    }

    private static Map<String, String> oldSkills() {
        return Map.of("workflow/stable-skill", "stable skill body", "workflow/changing-skill", "old changing skill");
    }

    private static Map<String, String> oldAgents() {
        return Map.of("stable-agent", "## Body\nstable agent body", "changing-agent", "## Body\nold changing agent");
    }

    private Setup setup(Path base, boolean withManifest) throws IOException {
        Setup s = new Setup();
        s.home = base.resolve("home");
        s.manifestFile = base.resolve("state/installed.json");
        s.config = s.home.resolve("config/mcp.json");

        BundleFixture.Bundle v30 = BundleFixture.create(base.resolve("bundle-3.0.0"), oldSkills(), oldAgents(),
            Map.of(CMD, "stable command body"), Map.of("old-mcp", "old-mcp jar bytes"));
        writeTemplates(v30);
        s.v31 = BundleFixture.create(base.resolve("bundle-3.1.0"),
            Map.of("workflow/stable-skill", "stable skill body", "workflow/changing-skill", "NEW changing skill",
                "workflow/new-skill", "brand new skill"),
            Map.of("stable-agent", "## Body\nstable agent body", "changing-agent", "## Body\nNEW changing agent"),
            Map.of(CMD, "stable command body"),
            Map.of("old-mcp", "old-mcp jar bytes", "new-mcp", "new-mcp jar bytes"));
        writeTemplates(s.v31);

        s.claude = new AgentToolDef("claude", "Claude Code",
            s.home.resolve("skills"), ".claude/skills",
            s.home.resolve("commands"), ".claude/commands",
            s.home.resolve("agents"), ".claude/agents",
            null, null, true, true, s.home);

        Path mcpInstallDir = s.home.resolve("mcp/old-mcp");
        install(s, v30, mcpInstallDir, withManifest);
        Files.createDirectories(s.config.getParent());
        Files.write(s.config, CONFIG_BYTES);
        ageAll(s.home);

        List<UpgradeEnvironment.McpJar> mcps = List.of(
            mcp("old-mcp", s.v31, s.home),
            mcp("new-mcp", s.v31, s.home));
        s.env = new UpgradeEnvironment(Map.of("claude", s.claude), Set.of("claude"),
            s.v31.skillsDir(), s.v31.agentsDir(), s.v31.commandsDir(), s.v31.templatesDir(),
            new FakeHooks(), mcps, s.manifestFile, "3.1.0");
        return s;
    }

    private static UpgradeEnvironment.McpJar mcp(String name, BundleFixture.Bundle bundle, Path home) {
        return new UpgradeEnvironment.McpJar(name, bundle.mcpJars().get(name), home.resolve("mcp/" + name),
            name + ".jar", UpgradeHeadlineScenarioTest::copyJar);
    }

    private static void writeTemplates(BundleFixture.Bundle b) throws IOException {
        // The installer copies only TemplateRegistry.FILES, so a stray fixture README would make the
        // bundled template tree differ from the installed one forever.
        Files.deleteIfExists(b.templatesDir().resolve("README.md"));
        for (String f : TemplateRegistry.FILES) {
            Files.writeString(b.templatesDir().resolve(f), "template " + f + "\n");
        }
    }

    /** Mirrors what the real install does: installers with a recorder, then the bundle catalog, then save. */
    private static void install(Setup s, BundleFixture.Bundle b, Path mcpInstallDir, boolean withManifest)
        throws IOException {
        InstallManifest manifest = InstallManifest.load(s.manifestFile);
        ManifestRecorder recorder = new ManifestRecorder(manifest, "global", "claude", "3.0.0");
        List<SkillDescriptor> skills = SkillDiscovery.discover(b.skillsDir());
        List<AgentDescriptor> agents = AgentDiscovery.discover(b.agentsDir());
        List<CommandDescriptor> commands = List.of(new CommandDescriptor(CMD, b.commandsDir().resolve(CMD + ".md")));

        assertAllOk(SkillInstaller.install(skills, s.claude.globalPath(), recorder));
        assertAllOk(AgentInstaller.install(agents, s.claude.agentsGlobalPath(), "claude", b.agentsDir(), recorder));
        assertAllOk(CommandInstaller.install(commands, s.claude.commandsGlobalPath(), recorder));
        assertAllOk(TemplateInstaller.install(s.claude.agentsGlobalPath(), b.templatesDir(), recorder));

        copyJar(b.mcpJars().get("old-mcp"), mcpInstallDir);
        new ManifestRecorder(manifest, "global", "-", "3.0.0").record(ItemKind.MCP_JAR, "old-mcp",
            ContentHash.ofFile(mcpInstallDir.resolve("old-mcp.jar")));

        manifest.recordBundle("3.0.0", BundleCatalog.items(
            skills.stream().map(SkillDescriptor::name).toList(),
            agents.stream().map(AgentDescriptor::name).toList(),
            List.of(CMD), b.mcpJars().keySet()));
        if (withManifest) {
            manifest.save();
        }
    }

    private static void assertAllOk(List<dev.dorrian.agenticskillscli.config.OperationResult> results) {
        for (var r : results) {
            assertTrue(r.success(), r.name() + ": " + r.error());
        }
    }

    private static void copyJar(Path bundled, Path dir) {
        try {
            Files.createDirectories(dir);
            Files.copy(bundled, dir.resolve(bundled.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void ageAll(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(p, OLD);
            }
        }
    }

    /** relative path -> content and mtime, for every regular file under root. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snap = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                snap.put(root.relativize(p).toString(),
                    Files.getLastModifiedTime(p).toMillis() + "|" + Files.readString(p));
            }
        }
        return snap;
    }

    private static Set<String> changed(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Set<String> diff = new HashSet<>();
        for (String k : keys) {
            if (!java.util.Objects.equals(before.get(k), after.get(k))) diff.add(k);
        }
        return diff;
    }

    private int run(UpgradeEnvironment env, String... args) {
        return UpgradeCommand.run(List.of(args), env, out, err);
    }

    private String output() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private void resetOutput() {
        outBuf.reset();
        errBuf.reset();
    }

    private static boolean hasLine(String output, String prefix, String name) {
        return output.lines().anyMatch(l -> l.startsWith(prefix) && l.contains(name));
    }

    @Test
    void headlineScenarioRefreshesOnlyWhatChangedAndListsNewItems() throws IOException {
        Setup s = setup(tmp.resolve("main"), true);
        Map<String, String> before = snapshot(s.home);

        int code = run(s.env);

        assertEquals(0, code, output() + errBuf);
        Map<String, String> after = snapshot(s.home);

        // (a) exactly the two changed items were rewritten
        assertEquals(Set.of("skills/changing-skill/SKILL.md", "agents/changing-agent.md"), changed(before, after));
        assertEquals(Files.readString(s.v31.skillsDir().resolve("workflow/changing-skill/SKILL.md")),
            Files.readString(s.home.resolve("skills/changing-skill/SKILL.md")));
        assertTrue(Files.readString(s.home.resolve("agents/changing-agent.md")).contains("NEW changing agent"));
        assertTrue(Files.getLastModifiedTime(s.home.resolve("skills/changing-skill/SKILL.md")).toMillis()
            > OLD.toMillis());
        assertTrue(Files.getLastModifiedTime(s.home.resolve("agents/changing-agent.md")).toMillis()
            > OLD.toMillis());

        // (b) report
        String report = output();
        assertTrue(hasLine(report, "  refreshed", "changing-skill"), report);
        assertTrue(hasLine(report, "  refreshed", "changing-agent"), report);
        assertTrue(hasLine(report, "  unchanged", "stable-skill"), report);
        assertTrue(hasLine(report, "  unchanged", "stable-agent"), report);
        assertTrue(hasLine(report, "  unchanged", CMD), report);
        assertFalse(hasLine(report, "  refreshed", "stable"), report);
        int block = report.indexOf("Not installed by upgrade (run `agentic-skills` and choose Install):");
        assertTrue(block >= 0, report);
        String notInstalled = report.substring(block);
        assertTrue(hasLine(notInstalled, "  skill", "new-skill"), notInstalled);
        assertTrue(hasLine(notInstalled, "  mcp", "new-mcp"), notInstalled);
        assertFalse(notInstalled.contains("stable"), notInstalled);

        // (c) nothing new installed
        assertFalse(Files.exists(s.home.resolve("skills/new-skill")));
        assertFalse(Files.exists(s.home.resolve("mcp/new-mcp")));

        // (d) MCP config untouched
        assertArrayEquals(CONFIG_BYTES, Files.readAllBytes(s.config));

        // (e) summary
        assertTrue(report.contains("2 refreshed, 5 unchanged, 0 failed, 2 new and not installed."), report);

        // (f) a second real run has nothing to refresh
        Map<String, String> afterFirst = snapshot(s.home);
        resetOutput();
        assertEquals(0, run(s.env), output());
        assertTrue(output().contains("0 refreshed"), output());
        assertEquals(Set.of(), changed(afterFirst, snapshot(s.home)));
    }

    @Test
    void dryRunOnAFreshCopyWritesNothingButNamesWhatWouldBeRefreshed() throws IOException {
        Setup s = setup(tmp.resolve("dry"), true);
        Map<String, String> before = snapshot(s.home);
        byte[] manifestBefore = Files.readAllBytes(s.manifestFile);
        long manifestTime = Files.getLastModifiedTime(s.manifestFile).toMillis();

        int code = run(s.env, "--dry-run");

        assertEquals(0, code, output());
        assertEquals(before, snapshot(s.home));
        assertArrayEquals(manifestBefore, Files.readAllBytes(s.manifestFile));
        assertEquals(manifestTime, Files.getLastModifiedTime(s.manifestFile).toMillis());
        assertFalse(Files.exists(s.home.resolve("skills/new-skill")));
        assertTrue(hasLine(output(), "  refreshed", "changing-skill"), output());
        assertTrue(hasLine(output(), "  refreshed", "changing-agent"), output());
        assertTrue(output().contains("Dry run: nothing written"), output());
    }

    @Test
    void preManifestInstallFallsBackToContentComparisonAndPrintsTheBaselineNote() throws IOException {
        Setup s = setup(tmp.resolve("pre"), false);
        assertFalse(Files.exists(s.manifestFile));
        Map<String, String> before = snapshot(s.home);

        int code = run(s.env);

        assertEquals(0, code, output() + errBuf);
        assertEquals(Set.of("skills/changing-skill/SKILL.md", "agents/changing-agent.md"),
            changed(before, snapshot(s.home)));
        assertTrue(output().contains("2 refreshed"), output());
        assertTrue(output().contains("No previous install record, so new items cannot be told apart."), output());
        assertFalse(output().contains("Not installed by upgrade"), output());
        assertFalse(Files.exists(s.home.resolve("skills/new-skill")));
        assertArrayEquals(CONFIG_BYTES, Files.readAllBytes(s.config));
    }

    @Test
    void corruptManifestDoesNotFailTheRun() throws IOException {
        Setup s = setup(tmp.resolve("corrupt"), true);
        Files.writeString(s.manifestFile, "{ this is not json ]");
        Map<String, String> before = snapshot(s.home);

        int code = run(s.env);

        assertEquals(0, code, output() + errBuf);
        assertEquals(Set.of("skills/changing-skill/SKILL.md", "agents/changing-agent.md"),
            changed(before, snapshot(s.home)));
        assertTrue(output().contains("2 refreshed"), output());
        assertFalse(errBuf.toString(StandardCharsets.UTF_8).contains("failed"), errBuf.toString());
    }

    @Test
    void locallyModifiedSkillIsSkippedAndNotOverwritten() throws IOException {
        Setup s = setup(tmp.resolve("modified"), true);
        Path installed = s.home.resolve("skills/changing-skill/SKILL.md");
        Files.writeString(installed, "my local edit\n");

        int code = run(s.env);

        assertEquals(0, code, output() + errBuf);
        assertEquals("my local edit\n", Files.readString(installed));
        assertTrue(hasLine(output(), "  skipped", "changing-skill"), output());
        assertTrue(hasLine(output(), "  refreshed", "changing-agent"), output());
        assertTrue(output().contains("1 refreshed"), output());
        assertTrue(output().contains("1 skipped (modified locally)"), output());
    }

    private static final class FakeHooks implements UpgradeEnvironment.Hooks {
        @Override public boolean supports(String toolKey) {
            return false;
        }

        @Override public boolean isInstalled(String toolKey) {
            return false;
        }

        @Override public HookInstallOptions currentOptions(String toolKey) {
            return HookInstallOptions.NONE;
        }

        @Override public boolean isCurrent(String toolKey) {
            return false;
        }

        @Override public void install(String toolKey, HookInstallOptions options) {
            throw new IllegalStateException("hooks must not be installed by this scenario");
        }
    }
}
