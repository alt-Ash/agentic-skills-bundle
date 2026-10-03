package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.registry.TemplateRegistry;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallRecorder;
import dev.dorrian.agenticskillscli.state.ItemKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of {@code bin/install.js}'s {@code installTemplates()} — copies
 * {@link TemplateRegistry#FILES} from {@code templates/project/} into
 * {@code <agentsGlobalPath>/templates/}.
 *
 * <p>There is deliberately no {@code remove}/uninstall counterpart: the
 * original never removes installed templates on uninstall either (confirmed
 * by grepping {@code bin/install.js} — templates are install-only, and this
 * preserves that behavior rather than "fixing" it).
 */
public final class TemplateInstaller {

    private TemplateInstaller() {
    }

    public static List<OperationResult> install(Path agentsGlobalPath) {
        return install(agentsGlobalPath, PackageRoot.templatesDir(), InstallRecorder.NOOP);
    }

    public static List<OperationResult> install(Path agentsGlobalPath, InstallRecorder recorder) {
        return install(agentsGlobalPath, PackageRoot.templatesDir(), recorder);
    }

    public static List<OperationResult> install(Path agentsGlobalPath, Path projectTemplatesDir) {
        return install(agentsGlobalPath, projectTemplatesDir, InstallRecorder.NOOP);
    }

    public static List<OperationResult> install(Path agentsGlobalPath, Path projectTemplatesDir, InstallRecorder recorder) {
        Path templatesDir = agentsGlobalPath.resolve("templates");
        ensureDir(templatesDir);

        List<OperationResult> results = new ArrayList<>();
        for (String file : TemplateRegistry.FILES) {
            Path src = projectTemplatesDir.resolve(file);
            Path dest = templatesDir.resolve(file);
            try {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                results.add(OperationResult.ok(file, false, null));
            } catch (IOException e) {
                results.add(OperationResult.failed(file, null, e.getMessage()));
            }
        }
        boolean allOk = results.stream().noneMatch(r -> r.error() != null);
        if (allOk) {
            try {
                recorder.record(ItemKind.TEMPLATE, "templates", ContentHash.ofTreeLimitedTo(templatesDir, projectTemplatesDir),
                    ContentHash.treeFiles(projectTemplatesDir));
            } catch (IOException | RuntimeException ignored) {
                // recording must never fail an install
            }
        }
        return results;
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
