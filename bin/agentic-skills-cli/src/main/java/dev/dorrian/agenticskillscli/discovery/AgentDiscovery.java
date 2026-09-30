package dev.dorrian.agenticskillscli.discovery;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.frontmatter.FrontmatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code discoverAgents()} — scans
 * {@code agents/*.md}, parses frontmatter, and skips any file with no
 * {@code description} field (documentation files like {@code AGENTS.md}
 * have no frontmatter at all and must not be offered as installable agents).
 */
public final class AgentDiscovery {

    private AgentDiscovery() {
    }

    public static List<AgentDescriptor> discover() {
        return discover(PackageRoot.agentsDir());
    }

    public static List<AgentDescriptor> discover(Path agentsDir) {
        List<AgentDescriptor> agents = new ArrayList<>();
        if (!Files.isDirectory(agentsDir)) {
            return agents;
        }

        List<Path> mdFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agentsDir, "*.md")) {
            for (Path entry : stream) {
                mdFiles.add(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mdFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path srcFile : mdFiles) {
            String fileName = srcFile.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ".md".length());
            try {
                String content = Files.readString(srcFile);
                Map<String, Object> frontmatter = FrontmatterParser.parse(content);
                if (frontmatter.get("description") == null) {
                    continue;
                }
                agents.add(new AgentDescriptor(name, srcFile, frontmatter));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return agents;
    }
}
