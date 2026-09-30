package dev.dorrian.agenticskillscli.discovery;

import java.nio.file.Path;

/** A discovered skill: its category, name, and on-disk source directory. */
public record SkillDescriptor(String category, String name, Path sourcePath) {
}
