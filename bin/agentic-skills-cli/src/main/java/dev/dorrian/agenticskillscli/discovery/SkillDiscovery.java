package dev.dorrian.agenticskillscli.discovery;

import dev.dorrian.agenticskillscli.PackageRoot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Java port of {@code bin/install.js}'s {@code discoverSkills()} — scans
 * {@code skills/<category>/<name>/} two levels deep. Unlike the original,
 * this returns plain data (no inquirer choice-object / chalk-colored label
 * concerns — that's a UI-layer responsibility, built in a later pass).
 */
public final class SkillDiscovery {

    private SkillDiscovery() {
    }

    public static List<SkillDescriptor> discover() {
        return discover(PackageRoot.skillsDir());
    }

    public static List<SkillDescriptor> discover(Path skillsDir) {
        List<SkillDescriptor> skills = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            return skills;
        }
        for (Path category : listDirectoriesSorted(skillsDir)) {
            for (Path skillDir : listDirectoriesSorted(category)) {
                skills.add(new SkillDescriptor(
                    category.getFileName().toString(),
                    skillDir.getFileName().toString(),
                    skillDir
                ));
            }
        }
        return skills;
    }

    private static List<Path> listDirectoriesSorted(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
}
