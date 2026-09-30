package dev.dorrian.agenticskillscli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves every on-disk location the installer reads from or writes to,
 * relative to the root of the installed npm package (the directory
 * containing {@code bin/}, {@code skills/}, {@code agents/}, {@code mcp/},
 * {@code .opencode/}, {@code templates/}).
 *
 * <p>Unlike {@code bin/install.js}, which could derive this from its own
 * {@code __dirname} (it lives inside the package it manipulates), a shaded
 * jar has no reliable way to introspect where the npm package that bundled
 * it lives on disk. The Node launcher shim is responsible for computing this
 * path and passing it explicitly via {@code --package-root <abs-path>}.
 */
public final class PackageRoot {

    private static volatile Path root;

    private PackageRoot() {
    }

    /**
     * Initializes the package root from an explicit path (normally the
     * {@code --package-root} CLI argument). Must be called before any
     * {@code *Dir()}/{@code *Src()} accessor.
     */
    public static void init(Path packageRoot) {
        root = packageRoot.toAbsolutePath().normalize();
    }

    /**
     * Convenience overload for CLI wiring: parses {@code --package-root <path>}
     * out of the raw argv if present, otherwise falls back to the JVM's
     * current working directory (useful for local dev/test runs where no
     * Node shim is involved yet).
     */
    public static void initFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--package-root".equals(args[i])) {
                init(Paths.get(args[i + 1]));
                return;
            }
        }
        init(Paths.get(System.getProperty("user.dir")));
    }

    private static Path root() {
        Path r = root;
        if (r == null) {
            throw new IllegalStateException("PackageRoot.init(...) has not been called yet");
        }
        return r;
    }

    public static Path root(String... more) {
        Path p = root();
        for (String segment : more) {
            p = p.resolve(segment);
        }
        return p;
    }

    public static Path skillsDir() {
        return root("skills");
    }

    public static Path agentsDir() {
        return root("agents");
    }

    public static Path commandsDir() {
        return root(".opencode", "commands");
    }

    public static Path templatesDir() {
        return root("templates", "project");
    }

    public static Path obTicketsMcpSrc() {
        return root("mcp", "issue-tickets");
    }

    public static Path securityScannerMcpSrc() {
        return root("mcp", "security-scanner");
    }
}
