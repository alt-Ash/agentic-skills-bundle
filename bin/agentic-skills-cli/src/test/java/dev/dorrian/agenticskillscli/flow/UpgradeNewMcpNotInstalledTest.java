package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An MCP jar added to the bundle after the recorded baseline is listed by upgrade, never installed by it. */
class UpgradeNewMcpNotInstalledTest {

    @TempDir Path tmp;

    private BundleFixture.Bundle bundle;
    private Path manifestFile;
    private Path ticketsDir;
    private Path codegenDir;
    private AgentToolDef claude;
    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        Path home = tmp.resolve("home");
        bundle = BundleFixture.create(tmp.resolve("bundle"), Map.of("cat/skill-a", "skill-a body"), Map.of(), Map.of(),
            Map.of("issue-tickets", "tickets jar", "local-codegen", "codegen jar"));
        manifestFile = tmp.resolve("state/installed.json");
        ticketsDir = tmp.resolve("mcp/issue-tickets");
        codegenDir = tmp.resolve("mcp/local-codegen");
        Files.createDirectories(home.resolve("skills/skill-a"));
        Files.writeString(home.resolve("skills/skill-a/SKILL.md"), "old skill-a");
        Files.createDirectories(ticketsDir);
        Files.writeString(ticketsDir.resolve("issue-tickets.jar"), "tickets jar");
        claude = new AgentToolDef("claude", "Claude Code",
            home.resolve("skills"), ".claude/skills",
            home.resolve("commands"), ".claude/commands",
            home.resolve("agents"), ".claude/agents",
            null, null, true, true, home);
    }

    private UpgradeEnvironment.McpJar mcp(String name, Path dir) {
        return new UpgradeEnvironment.McpJar(name, bundle.mcpJars().get(name), dir, name + ".jar",
            UpgradeNewMcpNotInstalledTest::copyJar);
    }

    private UpgradeEnvironment env(List<UpgradeEnvironment.McpJar> mcps) {
        return new UpgradeEnvironment(Map.of("claude", claude), Set.of("claude"), bundle.skillsDir(),
            bundle.agentsDir(), bundle.commandsDir(), bundle.templatesDir(), new NoHooks(), mcps, manifestFile, "test");
    }

    private int run(UpgradeEnvironment env) {
        return UpgradeCommand.run(List.of(), env, out, err);
    }

    private String output() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private static void copyJar(Path bundled, Path dir) {
        try {
            Files.createDirectories(dir);
            Files.copy(bundled, dir.resolve(bundled.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records a baseline from a bundle that predates local-codegen. */
    private void recordBaselineWithoutLocalCodegen() {
        assertEquals(0, run(env(List.of(mcp("issue-tickets", ticketsDir)))), output());
        outBuf.reset();
    }

    @Test
    void localCodegenAddedAfterTheBaselineIsListedAndNotInstalled() {
        recordBaselineWithoutLocalCodegen();

        int code = run(env(List.of(mcp("issue-tickets", ticketsDir), mcp("local-codegen", codegenDir))));

        assertEquals(0, code, output());
        String o = output();
        int block = o.indexOf("Not installed by upgrade");
        assertTrue(block >= 0, o);
        assertTrue(o.indexOf("local-codegen", block) > block, o);
        assertTrue(o.contains("run `agentic-skills` and choose Install"), o);
        assertFalse(Files.exists(codegenDir.resolve("local-codegen.jar")), "upgrade must not install it");
        assertTrue(o.contains("1 new and not installed."), o);
    }

    @Test
    void installedAndIdenticalLocalCodegenIsUnchangedAndNotListed() {
        recordBaselineWithoutLocalCodegen();
        copyJar(bundle.mcpJars().get("local-codegen"), codegenDir);

        int code = run(env(List.of(mcp("local-codegen", codegenDir))));

        assertEquals(0, code, output());
        String o = output();
        assertFalse(o.contains("Not installed by upgrade"), o);
        assertTrue(o.contains("local-codegen"), o);
        assertTrue(o.contains("0 new and not installed."), o);
        assertTrue(o.contains("unchanged"), o);
    }

    private static final class NoHooks implements UpgradeEnvironment.Hooks {
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
            throw new AssertionError("hooks are not installed in this test");
        }
    }
}
