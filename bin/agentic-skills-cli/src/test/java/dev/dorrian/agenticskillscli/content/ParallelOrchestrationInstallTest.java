package dev.dorrian.agenticskillscli.content;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.frontmatter.FrontmatterParser;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the parallel-orchestration wiring (dev-orchestrator -> parallel-feature-build skill)
 * survives installation into a user's tool config: the orchestrator can spawn sub-agents, the
 * skill it names is actually installable, and nothing the installed skill points at is missing.
 */
class ParallelOrchestrationInstallTest {

    // Resolved independently of PackageRoot's shared mutable singleton - see AgentFileStructureTest.
    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final Path AGENTS_DIR = REPO_ROOT.resolve("agents");
    private static final Path SKILLS_DIR = REPO_ROOT.resolve("skills");
    private static final String SKILL = "parallel-feature-build";
    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)#\\s]+)(?:#[^)]*)?\\)");

    @Test
    void orchestratorNamesAnInstallableParallelSkill() {
        String orchestrator = read(AGENTS_DIR.resolve("dev-orchestrator.md"));
        assertTrue(orchestrator.contains(SKILL), "dev-orchestrator must reference the " + SKILL + " skill");

        List<String> discovered = SkillDiscovery.discover(SKILLS_DIR).stream().map(SkillDescriptor::name).toList();
        assertTrue(discovered.contains(SKILL), SKILL + " must be discoverable by the installer");
    }

    @Test
    void orchestratorIsInstalledForClaudeWithSubagentSpawningAllowed(@TempDir Path target) throws IOException {
        Path src = AGENTS_DIR.resolve("dev-orchestrator.md");
        List<OperationResult> results = AgentInstaller.install(
            List.of(new AgentDescriptor("dev-orchestrator", src, Map.of())), target, "claude", AGENTS_DIR);
        assertTrue(results.get(0).success());

        Map<String, Object> fm = FrontmatterParser.parse(Files.readString(target.resolve("dev-orchestrator.md")));
        String disallowed = String.valueOf(fm.getOrDefault("disallowedTools", ""));
        assertTrue(!disallowed.contains("Task") && !disallowed.contains("Agent"),
            "orchestrator must stay able to spawn sub-agents, got disallowedTools: " + disallowed);
    }

    @Test
    void everyRelativeLinkInInstalledSkillResolves(@TempDir Path toolHome) throws IOException {
        // Mirror a real install: <tool>/skills/<skill>/ plus the companion dirs the CLI creates.
        Path skillsTarget = toolHome.resolve("skills");
        SkillDescriptor skill = SkillDiscovery.discover(SKILLS_DIR).stream()
            .filter(s -> s.name().equals(SKILL)).findFirst().orElseThrow();
        SkillInstaller.install(List.of(skill), skillsTarget);

        Path installedMd = skillsTarget.resolve(SKILL).resolve("SKILL.md");
        List<String> dangling = new ArrayList<>();
        Matcher m = MD_LINK.matcher(Files.readString(installedMd));
        while (m.find()) {
            String link = m.group(1);
            if (link.matches("^[a-z]+:.*")) continue; // http:, mailto:, ...
            if (!Files.exists(installedMd.getParent().resolve(link).normalize())) dangling.add(link);
        }
        assertEquals(List.of(), dangling, "installed " + SKILL + " links to files the installer never copies");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
