package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.install.LegacySkillLocations;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code detectInstalledSkills()} —
 * for each skill, true if it exists in ANY of the given tools' global paths.
 */
public final class InstalledSkillDetector {

    private InstalledSkillDetector() {
    }

    public static Set<String> detect(List<SkillDescriptor> skills, List<String> toolKeys) {
        Set<String> installed = new LinkedHashSet<>();
        for (SkillDescriptor skill : skills) {
            for (String toolKey : toolKeys) {
                AgentToolDef tool = AgentToolRegistry.get(toolKey);
                Path skillsPath = tool.globalPath();
                if (skillsPath == null) continue;
                boolean found = Files.exists(skillsPath.resolve(skill.name()))
                    || LegacySkillLocations.forTarget(skillsPath).stream()
                        .anyMatch(d -> Files.isRegularFile(d.resolve(skill.name()).resolve("SKILL.md")));
                if (found) {
                    installed.add(skill.name());
                    break;
                }
            }
        }
        return installed;
    }

    /** Skills present under one explicit skills directory (a project folder, say), incl. legacy locations. */
    public static Set<String> detect(List<SkillDescriptor> skills, Path skillsPath) {
        Set<String> installed = new LinkedHashSet<>();
        if (skillsPath == null) return installed;
        for (SkillDescriptor skill : skills) {
            boolean found = Files.exists(skillsPath.resolve(skill.name()))
                || LegacySkillLocations.forTarget(skillsPath).stream()
                    .anyMatch(d -> Files.isRegularFile(d.resolve(skill.name()).resolve("SKILL.md")));
            if (found) installed.add(skill.name());
        }
        return installed;
    }
}
