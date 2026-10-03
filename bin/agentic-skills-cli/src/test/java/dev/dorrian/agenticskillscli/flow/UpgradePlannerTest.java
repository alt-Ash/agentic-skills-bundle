package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Outcome;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradePlannerTest {

    @TempDir Path tmp;

    private UpgradeEnvironment env(Path home) {
        AgentToolDef claude = new AgentToolDef("claude", "Claude Code",
            home.resolve("skills"), ".claude/skills",
            home.resolve("commands"), ".claude/commands",
            home.resolve("agents"), ".claude/agents",
            null, null, true, true, home);
        UpgradeEnvironment.Hooks hooks = new UpgradeEnvironment.Hooks() {
            @Override public boolean supports(String toolKey) { return false; }
            @Override public boolean isInstalled(String toolKey) { return false; }
            @Override public HookInstallOptions currentOptions(String toolKey) { return HookInstallOptions.NONE; }
            @Override public boolean isCurrent(String toolKey) { return false; }
            @Override public void install(String toolKey, HookInstallOptions options) { }
        };
        Path bundle = tmp.resolve("bundle");
        return new UpgradeEnvironment(Map.of("claude", claude), Set.of("claude"),
            bundle.resolve("skills"), bundle.resolve("agents"), bundle.resolve("commands"),
            bundle.resolve("templates"), hooks, List.of(), null, "v2");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void classifiesByDetectorAndAttachesActionsOnlyToRefreshes() throws IOException {
        write(tmp.resolve("bundle/skills/cat/same/SKILL.md"), "same");
        write(tmp.resolve("bundle/skills/cat/stale/SKILL.md"), "fresh");
        Path home = tmp.resolve("home");
        write(home.resolve("skills/same/SKILL.md"), "same");
        write(home.resolve("skills/stale/SKILL.md"), "old");

        UpgradePlanner.Plan plan = UpgradePlanner.plan(env(home), List.of());

        assertEquals(2, plan.ops().size());
        UpgradeOp same = plan.ops().stream().filter(o -> o.name().equals("same")).findFirst().orElseThrow();
        UpgradeOp stale = plan.ops().stream().filter(o -> o.name().equals("stale")).findFirst().orElseThrow();
        assertEquals(Outcome.UNCHANGED, same.outcome());
        assertNull(same.action());
        assertEquals(Outcome.REFRESHED, stale.outcome());
        assertTrue(stale.action() != null);
        assertTrue(plan.baselineMissing());
        assertTrue(plan.newItems().isEmpty());
        assertEquals("old", Files.readString(home.resolve("skills/stale/SKILL.md")), "planning writes nothing");
    }

    @Test
    void registeredHooksAreUnchangedWhenCurrentAndRefreshedOtherwise() throws IOException {
        for (boolean current : new boolean[] {true, false}) {
            UpgradeEnvironment base = env(tmp.resolve("home"));
            UpgradeEnvironment.Hooks h = new UpgradeEnvironment.Hooks() {
                @Override public boolean supports(String k) { return true; }
                @Override public boolean isInstalled(String k) { return true; }
                @Override public HookInstallOptions currentOptions(String k) { return HookInstallOptions.NONE; }
                @Override public boolean isCurrent(String k) { return current; }
                @Override public void install(String k, HookInstallOptions o) { }
            };
            UpgradeEnvironment e = new UpgradeEnvironment(base.tools(), base.detectedTools(), base.skillsDir(),
                base.agentsDir(), base.commandsDir(), base.templatesDir(), h, List.of(), null, "v2");
            var op = UpgradePlanner.plan(e, List.of()).ops().stream()
                .filter(o -> o.kind().equals("hooks")).findFirst().orElseThrow();
            assertEquals(current ? UpgradeReportFormatter.Outcome.UNCHANGED : UpgradeReportFormatter.Outcome.REFRESHED, op.outcome());
            assertEquals(current, op.action() == null);
        }
    }

    @Test
    void uninstalledItemsProduceNoOps() throws IOException {
        write(tmp.resolve("bundle/skills/cat/only-bundled/SKILL.md"), "x");

        UpgradePlanner.Plan plan = UpgradePlanner.plan(env(tmp.resolve("home")), List.of());

        assertTrue(plan.ops().isEmpty());
    }

    @Test
    void notInstalledTemplatesAreNeverNewCandidates() throws IOException {
        write(tmp.resolve("bundle/templates/AGENT.md"), "t");
        Path mf = tmp.resolve("state/installed.json");
        var m = dev.dorrian.agenticskillscli.state.InstallManifest.load(mf);
        m.advanceBundle("v1", List.of("SKILL:other"), List.of());
        m.save();
        UpgradeEnvironment base = env(tmp.resolve("home"));
        UpgradeEnvironment e = new UpgradeEnvironment(base.tools(), base.detectedTools(), base.skillsDir(),
            base.agentsDir(), base.commandsDir(), base.templatesDir(), base.hooks(), List.of(), mf, "v2");

        UpgradePlanner.Plan plan = UpgradePlanner.plan(e, List.of());

        assertTrue(plan.newItems().isEmpty());
        assertTrue(plan.ops().isEmpty());
        assertTrue(plan.installedEntries().isEmpty());
        assertTrue(!plan.baselineMissing());
    }
}
