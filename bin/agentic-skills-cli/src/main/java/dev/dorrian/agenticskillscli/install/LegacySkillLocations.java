package dev.dorrian.agenticskillscli.install;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill folders that earlier installer versions wrote to and the current
 * version no longer does, keyed by the current target folder — so removing a
 * skill from its current location also cleans up a copy an older install
 * left behind.
 *
 * <ul>
 *   <li>Devin Desktop (formerly Windsurf) project skills: {@code .windsurf/rules}
 *       (a rules folder, never read as skills) → {@code .windsurf/skills}.</li>
 * </ul>
 */
public final class LegacySkillLocations {

    private LegacySkillLocations() {
    }

    /** The legacy folders to also clean when removing skills from {@code targetPath}; empty if none. */
    public static List<Path> forTarget(Path targetPath) {
        if (endsWith(targetPath, ".windsurf", "skills")) {
            return List.of(targetPath.resolveSibling("rules"));
        }
        return List.of();
    }

    private static boolean endsWith(Path path, String parent, String name) {
        Path fileName = path.getFileName();
        Path parentPath = path.getParent();
        return fileName != null && name.equals(fileName.toString())
            && parentPath != null && parentPath.getFileName() != null
            && parent.equals(parentPath.getFileName().toString());
    }
}
