package dev.dorrian.agenticskillscli.state;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.frontmatter.AgentContentTransformer;
import dev.dorrian.agenticskillscli.frontmatter.AgentFileNaming;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InstallDetectorTest {

    @TempDir Path tmp;

    private static final String AGENT_SRC = """
        ---
        description: An agent used in detector tests for sure
        mode: subagent
        temperature: 0.2
        color: "#112233"
        permission:
          edit: allow
        ---
        ## Role
        body
        """;

    private InstallManifest manifest() {
        return InstallManifest.load(tmp.resolve("m.json"));
    }

    private ItemState st(InstallStatus s, boolean byContent) {
        return new ItemState(s, byContent);
    }

    @Test
    void skillRules() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        SkillDescriptor sd = new SkillDescriptor("c", "tdd", src);
        Path skills = tmp.resolve("skills");
        InstallManifest m = manifest();
        InstallDetector d = new InstallDetector(m, "global");

        assertEquals(st(InstallStatus.NOT_INSTALLED, false), d.skill(sd, skills, "claude"));

        Path live = skills.resolve("tdd");
        Files.createDirectories(live);
        Files.writeString(live.resolve("SKILL.md"), "v1");
        assertEquals(st(InstallStatus.UP_TO_DATE, true), d.skill(sd, skills, "claude"));

        m.record(InstallManifest.key("global", "claude", ItemKind.SKILL, "tdd"), ContentHash.ofTree(live), "1");
        assertEquals(st(InstallStatus.UP_TO_DATE, false), d.skill(sd, skills, "claude"));

        // bundle changes, live equals recorded -> update available (not by content)
        Files.writeString(src.resolve("SKILL.md"), "v2");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false), d.skill(sd, skills, "claude"));

        // user edits live -> modified
        Files.writeString(live.resolve("SKILL.md"), "mine");
        assertEquals(st(InstallStatus.MODIFIED, false), d.skill(sd, skills, "claude"));

        // no manifest entry and differing content
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true),
            new InstallDetector(manifest(), "global").skill(sd, skills, "claude"));
    }

    @Test
    void skillExtraFileIsIgnoredButOwnedFileDeletionIsNot() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        SkillDescriptor sd = new SkillDescriptor("c", "tdd", src);
        Path skills = tmp.resolve("skills");
        Path live = skills.resolve("tdd");
        Files.createDirectories(live);
        Files.writeString(live.resolve("SKILL.md"), "v1");
        Files.writeString(live.resolve("extra.txt"), "mine");
        InstallDetector d = new InstallDetector(manifest(), "global");

        assertEquals(st(InstallStatus.UP_TO_DATE, true), d.skill(sd, skills, "claude"));

        Files.delete(live.resolve("SKILL.md"));
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.skill(sd, skills, "claude"));
    }

    @Test
    void legacyOnlySkillIsNotInstalled() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        Path legacy = tmp.resolve("legacy/tdd");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("SKILL.md"), "v1");
        assertEquals(st(InstallStatus.NOT_INSTALLED, false),
            new InstallDetector(manifest(), "global")
                .skill(new SkillDescriptor("c", "tdd", src), tmp.resolve("skills"), "claude"));
    }

    @Test
    void unreadableSourceMeansUpdateByContent() throws IOException {
        Path live = tmp.resolve("skills/gone");
        Files.createDirectories(live);
        Files.writeString(live.resolve("SKILL.md"), "x");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true),
            new InstallDetector(manifest(), "global")
                .skill(new SkillDescriptor("c", "gone", tmp.resolve("missing")), tmp.resolve("skills"), "claude"));
    }

    @Test
    void agentComparesTransformedOutputPerTool() throws IOException {
        Path srcFile = tmp.resolve("agents/reviewer.md");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, AGENT_SRC);
        AgentDescriptor ad = new AgentDescriptor("reviewer", srcFile, Map.of());
        InstallDetector d = new InstallDetector(manifest(), "global");

        String claudeOut = AgentContentTransformer.transform(AGENT_SRC, "claude", "reviewer");
        String vscodeOut = AgentContentTransformer.transform(AGENT_SRC, "vscode", "reviewer");
        assertNotEquals(claudeOut, vscodeOut);
        assertNotEquals(claudeOut, AGENT_SRC);

        for (String[] t : new String[][] {{"claude", claudeOut}, {"vscode", vscodeOut}}) {
            Path dir = tmp.resolve("out-" + t[0]);
            assertEquals(st(InstallStatus.NOT_INSTALLED, false), d.agent(ad, dir, t[0]));
            Path live = dir.resolve(AgentFileNaming.fileName("reviewer", t[0]));
            Files.createDirectories(live.getParent());
            Files.writeString(live, t[1]);
            assertEquals(st(InstallStatus.UP_TO_DATE, true), d.agent(ad, dir, t[0]));
        }
        // claude output installed under the vscode tool's expectations is not current
        Path mixed = tmp.resolve("mixed");
        Files.createDirectories(mixed);
        Files.writeString(mixed.resolve("reviewer.agent.md"), claudeOut);
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.agent(ad, mixed, "vscode"));
        // raw source is never "current"
        Path raw = tmp.resolve("raw");
        Files.createDirectories(raw);
        Files.writeString(raw.resolve("reviewer.md"), AGENT_SRC);
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.agent(ad, raw, "claude"));
    }

    @Test
    void commandRules() throws IOException {
        Path src = tmp.resolve("cmds/go.md");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "one");
        CommandDescriptor cd = new CommandDescriptor("go", src);
        Path dir = tmp.resolve("installed");
        InstallManifest m = manifest();
        InstallDetector d = new InstallDetector(m, "/proj");
        assertEquals(st(InstallStatus.NOT_INSTALLED, false), d.command(cd, dir, "claude"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("go.md"), "old");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.command(cd, dir, "claude"));
        m.record(InstallManifest.key("/proj", "claude", ItemKind.COMMAND, "go"), ContentHash.ofString("old"), "1");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false), d.command(cd, dir, "claude"));
        Files.writeString(dir.resolve("go.md"), "edited");
        assertEquals(st(InstallStatus.MODIFIED, false), d.command(cd, dir, "claude"));
        Files.writeString(dir.resolve("go.md"), "one");
        assertEquals(st(InstallStatus.UP_TO_DATE, false), d.command(cd, dir, "claude"));
    }

    @Test
    void templateRules() throws IOException {
        Path bundled = tmp.resolve("b/templates");
        Path installed = tmp.resolve("i/templates");
        Files.createDirectories(bundled);
        Files.writeString(bundled.resolve("A.md"), "a1");
        InstallManifest m = manifest();
        InstallDetector d = new InstallDetector(m, "global");
        assertEquals(st(InstallStatus.NOT_INSTALLED, false), d.template(installed, bundled, "claude"));
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("A.md"), "a0");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.template(installed, bundled, "claude"));
        m.record(InstallManifest.key("global", "claude", ItemKind.TEMPLATE, "templates"), ContentHash.ofTree(installed), "1");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false), d.template(installed, bundled, "claude"));
        Files.writeString(installed.resolve("A.md"), "zzz");
        assertEquals(st(InstallStatus.MODIFIED, false), d.template(installed, bundled, "claude"));
        Files.writeString(installed.resolve("A.md"), "a1");
        assertEquals(st(InstallStatus.UP_TO_DATE, false), d.template(installed, bundled, "claude"));
    }

    @Test
    void mcpJarRules() throws IOException {
        Path bundled = tmp.resolve("b.jar");
        Path installed = tmp.resolve("i/x.jar");
        Files.writeString(bundled, "new");
        InstallManifest m = manifest();
        InstallDetector d = new InstallDetector(m, "global");
        assertEquals(st(InstallStatus.NOT_INSTALLED, false), d.mcpJar("x", installed, bundled));
        Files.createDirectories(installed.getParent());
        Files.writeString(installed, "old");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, true), d.mcpJar("x", installed, bundled));
        m.record(InstallManifest.key("global", "-", ItemKind.MCP_JAR, "x"), ContentHash.ofString("old"), "1");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false), d.mcpJar("x", installed, bundled));
        Files.writeString(installed, "hacked");
        assertEquals(st(InstallStatus.MODIFIED, false), d.mcpJar("x", installed, bundled));
        Files.writeString(installed, "new");
        assertEquals(st(InstallStatus.UP_TO_DATE, false), d.mcpJar("x", installed, bundled));
    }

    private Path liveSkill(String... filesAndContents) throws IOException {
        Path live = tmp.resolve("skills/tdd");
        for (int i = 0; i < filesAndContents.length; i += 2) {
            Path f = live.resolve(filesAndContents[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, filesAndContents[i + 1]);
        }
        return live;
    }

    private void recordInstall(InstallManifest m, Path live, Path src) throws IOException {
        m.record(InstallManifest.key("global", "claude", ItemKind.SKILL, "tdd"),
            ContentHash.ofTreeLimitedTo(live, src), "1", ContentHash.treeFiles(src));
    }

    @Test
    void bundleAddingAFileIsAnUpdateNotAModification() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        Path live = liveSkill("SKILL.md", "v1");
        InstallManifest m = manifest();
        recordInstall(m, live, src);

        Files.writeString(src.resolve("EXTRA.md"), "new file");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false),
            new InstallDetector(m, "global").skill(new SkillDescriptor("c", "tdd", src), tmp.resolve("skills"), "claude"));
    }

    @Test
    void bundleRemovingAFileIsAnUpdateNotAModification() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        Files.writeString(src.resolve("OLD.md"), "old");
        Path live = liveSkill("SKILL.md", "v1", "OLD.md", "old");
        InstallManifest m = manifest();
        recordInstall(m, live, src);

        Files.delete(src.resolve("OLD.md"));
        Files.writeString(src.resolve("SKILL.md"), "v2");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false),
            new InstallDetector(m, "global").skill(new SkillDescriptor("c", "tdd", src), tmp.resolve("skills"), "claude"));
    }

    @Test
    void editingAnOwnedFileAfterAShapeChangeIsModified() throws IOException {
        Path src = tmp.resolve("src/tdd");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "v1");
        Path live = liveSkill("SKILL.md", "v1");
        InstallManifest m = manifest();
        recordInstall(m, live, src);

        Files.writeString(src.resolve("EXTRA.md"), "new file");
        Files.writeString(live.resolve("SKILL.md"), "mine");
        assertEquals(st(InstallStatus.MODIFIED, false),
            new InstallDetector(m, "global").skill(new SkillDescriptor("c", "tdd", src), tmp.resolve("skills"), "claude"));
    }

    @Test
    void templateBundleAddingAFileIsAnUpdate() throws IOException {
        Path bundled = tmp.resolve("bt");
        Path installed = tmp.resolve("it");
        Files.createDirectories(bundled);
        Files.createDirectories(installed);
        Files.writeString(bundled.resolve("A.md"), "a1");
        Files.writeString(installed.resolve("A.md"), "a1");
        InstallManifest m = manifest();
        m.record(InstallManifest.key("global", "claude", ItemKind.TEMPLATE, "templates"),
            ContentHash.ofTreeLimitedTo(installed, bundled), "1", ContentHash.treeFiles(bundled));
        Files.writeString(bundled.resolve("B.md"), "b1");
        assertEquals(st(InstallStatus.UPDATE_AVAILABLE, false),
            new InstallDetector(m, "global").template(installed, bundled, "claude"));
    }
}
