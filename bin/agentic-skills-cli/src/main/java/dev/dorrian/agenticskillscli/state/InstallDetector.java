package dev.dorrian.agenticskillscli.state;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.frontmatter.AgentContentTransformer;
import dev.dorrian.agenticskillscli.frontmatter.AgentFileNaming;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Answers whether an installed item is absent, current, behind the bundle, or edited locally. Never throws. */
public final class InstallDetector {

    private static final ItemState STALE_BY_CONTENT = new ItemState(InstallStatus.UPDATE_AVAILABLE, true);

    private final InstallManifest manifest;
    private final String scope;

    public InstallDetector(InstallManifest manifest, String scope) {
        this.manifest = manifest;
        this.scope = scope;
    }

    public ItemState skill(SkillDescriptor skill, Path skillsPath, String tool) {
        Path live = skillsPath.resolve(skill.name());
        return compareTree(ItemKind.SKILL, skill.name(), tool, live, skill.sourcePath());
    }

    public ItemState agent(AgentDescriptor agent, Path agentsPath, String tool) {
        Path live = agentsPath.resolve(AgentFileNaming.fileName(agent.name(), tool));
        return compare(ItemKind.AGENT, agent.name(), tool, Files.isRegularFile(live),
            () -> ContentHash.ofFile(live),
            () -> ContentHash.ofString(AgentContentTransformer.transform(
                Files.readString(agent.srcFile()), tool, agent.name())));
    }

    public ItemState command(CommandDescriptor command, Path commandsPath, String tool) {
        Path live = commandsPath.resolve(command.name() + ".md");
        return compare(ItemKind.COMMAND, command.name(), tool, Files.isRegularFile(live),
            () -> ContentHash.ofFile(live), () -> ContentHash.ofFile(command.srcFile()));
    }

    public ItemState template(Path installedTemplatesDir, Path bundledTemplatesDir, String tool) {
        return compareTree(ItemKind.TEMPLATE, "templates", tool, installedTemplatesDir, bundledTemplatesDir);
    }

    public ItemState mcpJar(String name, Path installedJar, Path bundledJar) {
        return compare(ItemKind.MCP_JAR, name, "-", Files.isRegularFile(installedJar),
            () -> ContentHash.ofFile(installedJar), () -> ContentHash.ofFile(bundledJar));
    }

    /**
     * Tree items: when the bundle's file set changed since the install, the live copy is re-hashed over
     * the file set recorded at install time, so an untouched install is not mistaken for a local edit.
     */
    private ItemState compareTree(ItemKind kind, String name, String tool, Path live, Path bundle) {
        if (!Files.isDirectory(live)) {
            return new ItemState(InstallStatus.NOT_INSTALLED, false);
        }
        try {
            String key = InstallManifest.key(scope, tool, kind, name);
            var recorded = manifest.hash(key);
            if (ContentHash.ofTreeLimitedTo(live, bundle).equals(ContentHash.ofTree(bundle))) {
                return new ItemState(InstallStatus.UP_TO_DATE, recorded.isEmpty());
            }
            if (recorded.isEmpty()) {
                return STALE_BY_CONTENT;
            }
            var files = manifest.files(key);
            String liveHash = files.isPresent()
                ? ContentHash.ofFiles(live, files.get())
                : ContentHash.ofTreeLimitedTo(live, bundle);
            return liveHash.equals(recorded.get())
                ? new ItemState(InstallStatus.UPDATE_AVAILABLE, false)
                : new ItemState(InstallStatus.MODIFIED, false);
        } catch (IOException | RuntimeException e) {
            return STALE_BY_CONTENT;
        }
    }

    private interface HashSource {
        String get() throws IOException;
    }

    private ItemState compare(ItemKind kind, String name, String tool, boolean present,
                              HashSource liveHash, HashSource expectedHash) {
        if (!present) {
            return new ItemState(InstallStatus.NOT_INSTALLED, false);
        }
        try {
            String live = liveHash.get();
            String expected = expectedHash.get();
            var recorded = manifest.hash(InstallManifest.key(scope, tool, kind, name));
            if (live.equals(expected)) {
                return new ItemState(InstallStatus.UP_TO_DATE, recorded.isEmpty());
            }
            if (recorded.isEmpty()) {
                return STALE_BY_CONTENT;
            }
            return live.equals(recorded.get())
                ? new ItemState(InstallStatus.UPDATE_AVAILABLE, false)
                : new ItemState(InstallStatus.MODIFIED, false);
        } catch (IOException | RuntimeException e) {
            return STALE_BY_CONTENT;
        }
    }
}
