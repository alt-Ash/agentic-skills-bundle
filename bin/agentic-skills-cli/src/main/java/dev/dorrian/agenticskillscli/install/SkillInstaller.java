package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallRecorder;
import dev.dorrian.agenticskillscli.state.ItemKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of {@code bin/install.js}'s {@code installSkills()}, plus the
 * inline skill-removal logic from {@code runUninstall()} (there is no
 * separate {@code uninstallSkills} function in the original — removal is a
 * simple recursive delete at the call site).
 */
public final class SkillInstaller {

    private SkillInstaller() {
    }

    public static List<OperationResult> install(List<SkillDescriptor> skills, Path targetPath) {
        return install(skills, targetPath, InstallRecorder.NOOP);
    }

    public static List<OperationResult> install(List<SkillDescriptor> skills, Path targetPath, InstallRecorder recorder) {
        ensureDir(targetPath);
        List<OperationResult> results = new ArrayList<>();
        for (SkillDescriptor skill : skills) {
            Path dest = targetPath.resolve(skill.name());
            try {
                copyRecursive(skill.sourcePath(), dest);
                try {
                    recorder.record(ItemKind.SKILL, skill.name(), ContentHash.ofTreeLimitedTo(dest, skill.sourcePath()),
                        ContentHash.treeFiles(skill.sourcePath()));
                } catch (IOException | RuntimeException ignored) {
                    // recording must never fail an install
                }
                results.add(OperationResult.ok(skill.name(), false, null));
            } catch (IOException e) {
                results.add(OperationResult.failed(skill.name(), null, e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Removes an installed skill directory if present, plus any copy an older
     * version left at a {@link LegacySkillLocations legacy location} (only if
     * that copy has a {@code SKILL.md}, so a user's own folder of the same name
     * is never touched); skipped=true if neither existed.
     */
    public static OperationResult remove(String skillName, Path targetPath) {
        Path dest = targetPath.resolve(skillName);
        try {
            boolean removed = false;
            if (Files.exists(dest)) {
                deleteRecursive(dest);
                removed = true;
            }
            for (Path legacyDir : LegacySkillLocations.forTarget(targetPath)) {
                Path legacy = legacyDir.resolve(skillName);
                if (Files.isRegularFile(legacy.resolve("SKILL.md"))) {
                    deleteRecursive(legacy);
                    removed = true;
                }
            }
            return OperationResult.ok(skillName, !removed, null);
        } catch (IOException e) {
            return OperationResult.failed(skillName, null, e.getMessage());
        }
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void copyRecursive(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
