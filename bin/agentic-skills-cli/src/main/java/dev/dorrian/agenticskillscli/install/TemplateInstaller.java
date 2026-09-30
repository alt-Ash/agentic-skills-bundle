package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.registry.TemplateRegistry;

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
        return install(agentsGlobalPath, PackageRoot.templatesDir());
    }

    public static List<OperationResult> install(Path agentsGlobalPath, Path projectTemplatesDir) {
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
