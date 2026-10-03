package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.registry.TemplateRegistry;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallRecorder;
import dev.dorrian.agenticskillscli.state.ItemKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerRecordingTest {

    @TempDir Path tmp;

    private record Rec(ItemKind kind, String name, String hash) { }

    private static class Capture implements InstallRecorder {
        final List<Rec> recs = new ArrayList<>();
        @Override public void record(ItemKind kind, String name, String hash, List<String> files) {
            recs.add(new Rec(kind, name, hash));
        }
    }

    @Test
    void skillRecordsTreeHash() throws IOException {
        Path src = tmp.resolve("src/s1");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("SKILL.md"), "x");
        Files.writeString(src.resolve("sub/f.txt"), "y");
        Path target = tmp.resolve("t");
        Capture c = new Capture();
        List<OperationResult> r = SkillInstaller.install(List.of(new SkillDescriptor("cat", "s1", src)), target, c);
        assertTrue(r.get(0).success());
        assertEquals(List.of(new Rec(ItemKind.SKILL, "s1", ContentHash.ofTree(src))), c.recs);

        // an extra user file in the installed dir does not change what a re-check would compute
        Files.writeString(target.resolve("s1/extra.txt"), "z");
        assertEquals(c.recs.get(0).hash(), ContentHash.ofTreeLimitedTo(target.resolve("s1"), src));
    }

    @Test
    void skillFailureRecordsNothing() {
        Capture c = new Capture();
        List<OperationResult> r = SkillInstaller.install(
            List.of(new SkillDescriptor("cat", "gone", tmp.resolve("missing"))), tmp.resolve("t"), c);
        assertFalse(r.get(0).success());
        assertTrue(c.recs.isEmpty());
    }

    @Test
    void noopOverloadsStillInstall() throws IOException {
        Path src = tmp.resolve("src/s1");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "x");
        assertTrue(SkillInstaller.install(List.of(new SkillDescriptor("c", "s1", src)), tmp.resolve("t")).get(0).success());
        assertTrue(Files.exists(tmp.resolve("t/s1/SKILL.md")));
    }

    @Test
    void recorderFailureNeverFailsInstall() throws IOException {
        Path src = tmp.resolve("src/s1");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SKILL.md"), "x");
        InstallRecorder boom = (k, n, h, f) -> { throw new IllegalStateException("boom"); };
        assertTrue(SkillInstaller.install(List.of(new SkillDescriptor("c", "s1", src)), tmp.resolve("t"), boom).get(0).success());
    }

    @Test
    void agentRecordsTransformedFileHash() throws IOException {
        Path srcFile = tmp.resolve("agents/rev.md");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, """
            ---
            description: An agent used in recording tests ok
            mode: subagent
            temperature: 0.2
            color: "#112233"
            permission:
              edit: allow
            ---
            ## Role
            body
            """);
        Path target = tmp.resolve("t");
        Capture c = new Capture();
        List<OperationResult> r = AgentInstaller.install(
            List.of(new AgentDescriptor("rev", srcFile, Map.of())), target, "claude", tmp.resolve("agents"), c);
        assertTrue(r.get(0).success());
        assertEquals(List.of(new Rec(ItemKind.AGENT, "rev", ContentHash.ofFile(target.resolve("rev.md")))), c.recs);
        // transformed, not raw
        assertFalse(ContentHash.ofFile(srcFile).equals(c.recs.get(0).hash()));
    }

    @Test
    void agentFailureRecordsNothing() {
        Capture c = new Capture();
        List<OperationResult> r = AgentInstaller.install(
            List.of(new AgentDescriptor("x", tmp.resolve("nope.md"), Map.of())), tmp.resolve("t"), "claude", tmp.resolve("a"), c);
        assertFalse(r.get(0).success());
        assertTrue(c.recs.isEmpty());
    }

    @Test
    void commandRecordsAndFailureRecordsNothing() throws IOException {
        Path src = tmp.resolve("c/go.md");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "cmd");
        Path target = tmp.resolve("t");
        Capture c = new Capture();
        List<OperationResult> r = CommandInstaller.install(
            List.of(new CommandDescriptor("go", src), new CommandDescriptor("bad", tmp.resolve("missing.md"))), target, c);
        assertTrue(r.get(0).success());
        assertFalse(r.get(1).success());
        assertEquals(List.of(new Rec(ItemKind.COMMAND, "go", ContentHash.ofFile(target.resolve("go.md")))), c.recs);
    }

    @Test
    void templatesRecordTreeHashOnlyWhenAllWritten() throws IOException {
        Path src = tmp.resolve("tpl");
        Files.createDirectories(src);
        for (String f : TemplateRegistry.FILES) {
            Path p = src.resolve(f);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "t-" + f);
        }
        Path target = tmp.resolve("t");
        Capture c = new Capture();
        TemplateInstaller.install(target, src, c);
        assertEquals(List.of(new Rec(ItemKind.TEMPLATE, "templates", ContentHash.ofTree(src))), c.recs);

        Capture bad = new Capture();
        List<OperationResult> r = TemplateInstaller.install(tmp.resolve("t2"), tmp.resolve("empty"), bad);
        assertFalse(r.get(0).success());
        assertTrue(bad.recs.isEmpty());
    }

    @Test
    void skillAndTemplateRecordTheSourceFileSet() throws IOException {
        Path src = tmp.resolve("src/s1");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("SKILL.md"), "x");
        Files.writeString(src.resolve("sub/f.txt"), "y");
        List<List<String>> seen = new ArrayList<>();
        InstallRecorder rec = (kind, name, hash, files) -> seen.add(files);
        SkillInstaller.install(List.of(new SkillDescriptor("cat", "s1", src)), tmp.resolve("t"), rec);
        assertEquals(List.of(List.of("SKILL.md", "sub/f.txt")), seen);

        Path templates = tmp.resolve("tpl");
        for (String f : TemplateRegistry.FILES) {
            Files.createDirectories(templates);
            Files.writeString(templates.resolve(f), "t");
        }
        seen.clear();
        TemplateInstaller.install(tmp.resolve("agents"), templates, rec);
        assertEquals(1, seen.size());
        assertEquals(TemplateRegistry.FILES.stream().sorted().toList(), seen.get(0));
    }

    @Test
    void threeArgRecordDelegatesWithEmptyFiles() {
        List<List<String>> seen = new ArrayList<>();
        InstallRecorder rec = (kind, name, hash, files) -> seen.add(files);
        rec.record(ItemKind.AGENT, "a", "h");
        assertEquals(List.of(List.of()), seen);
    }
}
