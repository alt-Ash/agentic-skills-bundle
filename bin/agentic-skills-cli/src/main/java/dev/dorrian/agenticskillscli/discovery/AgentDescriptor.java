package dev.dorrian.agenticskillscli.discovery;

import java.nio.file.Path;
import java.util.Map;

/** A discovered agent: its name, source markdown file, and parsed frontmatter. */
public record AgentDescriptor(String name, Path srcFile, Map<String, Object> frontmatter) {
}
