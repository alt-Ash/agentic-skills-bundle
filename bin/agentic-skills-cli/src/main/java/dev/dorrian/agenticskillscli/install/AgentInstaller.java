package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.frontmatter.AgentContentTransformer;
import dev.dorrian.agenticskillscli.frontmatter.AgentFileNaming;
import dev.dorrian.agenticskillscli.registry.CompanionFileRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code installAgentFiles()}, plus the
 * inline agent-removal logic from {@code runUninstall()} (no separate
 * {@code uninstallAgentFiles} function exists in the original).
 */
public final class AgentInstaller {

    private static final Set<PosixFilePermission> EXECUTABLE_0755 = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
    );

    private AgentInstaller() {
    }

    public static List<OperationResult> install(List<AgentDescriptor> agents, Path targetPath, String toolKey) {
        return install(agents, targetPath, toolKey, PackageRoot.agentsDir());
    }

    public static List<OperationResult> install(List<AgentDescriptor> agents, Path targetPath, String toolKey, Path agentsDir) {
        ensureDir(targetPath);
        List<OperationResult> results = new ArrayList<>();
        for (AgentDescriptor agentDef : agents) {
            String destFile = AgentFileNaming.fileName(agentDef.name(), toolKey);
            Path dest = targetPath.resolve(destFile);
            try {
                String sourceContent = Files.readString(agentDef.srcFile());
                String transformed = AgentContentTransformer.transform(sourceContent, toolKey, agentDef.name());
                Files.writeString(dest, transformed);
                // Upgrade cleanup: drop files an older version wrote under a different name (Codex .md).
                for (String legacy : AgentFileNaming.legacyFileNames(agentDef.name(), toolKey)) {
                    Files.deleteIfExists(targetPath.resolve(legacy));
                }
                results.add(OperationResult.ok(agentDef.name(), false, null));
            } catch (IOException e) {
                results.add(OperationResult.failed(agentDef.name(), null, e.getMessage()));
            }

            // Copy companion files (e.g. shell scripts) that the agent depends on.
            // Success is silent (matches the original — only failures are recorded).
            for (String filename : CompanionFileRegistry.companionsFor(agentDef.name())) {
                Path src = agentsDir.resolve(filename);
                Path dst = targetPath.resolve(filename);
                try {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    Files.setPosixFilePermissions(dst, EXECUTABLE_0755);
                } catch (IOException | UnsupportedOperationException e) {
                    // Non-fatal: log but don't fail the agent install.
                    results.add(OperationResult.failed(agentDef.name() + "/" + filename, null, e.getMessage()));
                }
            }
        }
        return results;
    }

    /**
     * Removes an installed agent file (and its companion files, silently, on
     * a best-effort basis) if present; skipped=true if the main file was
     * already absent.
     */
    public static OperationResult remove(AgentDescriptor agentDef, Path targetPath, String toolKey) {
        String destFile = AgentFileNaming.fileName(agentDef.name(), toolKey);
        Path dest = targetPath.resolve(destFile);
        OperationResult result;
        try {
            boolean removed = Files.deleteIfExists(dest);
            // Also clean files older installer versions wrote (e.g. Codex agents as .md).
            for (String legacy : AgentFileNaming.legacyFileNames(agentDef.name(), toolKey)) {
                removed |= Files.deleteIfExists(targetPath.resolve(legacy));
            }
            result = OperationResult.ok(agentDef.name(), !removed, null);
        } catch (IOException e) {
            result = OperationResult.failed(agentDef.name(), null, e.getMessage());
        }

        for (String filename : CompanionFileRegistry.companionsFor(agentDef.name())) {
            Path dst = targetPath.resolve(filename);
            try {
                Files.deleteIfExists(dst);
            } catch (IOException e) {
                // non-fatal, matches the original's silent catch
            }
        }
        return result;
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
