package dev.dorrian.agenticskillscli.registry;

import java.util.List;

/**
 * Java port of {@code bin/install.js}'s {@code TEMPLATE_FILES} constant —
 * template files installed alongside agents for the project-initializer agent.
 */
public final class TemplateRegistry {

    public static final List<String> FILES = List.of(
        "AGENT.md", "ARCHITECTURE.md", "CLAUDE.md", "DESIGN.md", "GLOSSARY.md", "MEMORY.md"
    );

    private TemplateRegistry() {
    }
}
