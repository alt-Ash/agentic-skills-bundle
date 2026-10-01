package dev.dorrian.agenticskillscli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves every on-disk location the installer reads content from, relative
 * to a "package root" laid out like the jar's classpath {@code bundle/}:
 * {@code skills/}, {@code agents/}, {@code .opencode/commands/},
 * {@code templates/}, plus the prebuilt {@code agentic-skills-hooks.jar},
 * {@code issue-tickets.jar} and {@code security-scanner.jar}.
 *
 * <p>Resolution order ({@link #initFromArgs}):
 * <ol>
 *   <li>{@code --package-root <path>} if given (tests, dev runs against a
 *       hand-assembled tree);</li>
 *   <li>the bundle shipped in the running code — extracted once from
 *       {@code agentic-skills.jar} to {@code ~/.agentic-skills/dist/<version>/},
 *       or {@code target/classes/bundle} used in place (see
 *       {@link BundleExtractor});</li>
 *   <li>the JVM working directory, as a last-resort dev fallback (a repo
 *       checkout has {@code skills/}/{@code agents/} at its root, though not
 *       the prebuilt jars).</li>
 * </ol>
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
     * CLI wiring: {@code --package-root <path>} from argv if present, else the
     * bundled content (see class doc), else the JVM working directory.
     */
    public static void initFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--package-root".equals(args[i])) {
                init(Paths.get(args[i + 1]));
                return;
            }
        }
        init(BundleExtractor.locateOrExtract(HomeDir.resolve())
            .orElseGet(() -> Paths.get(System.getProperty("user.dir"))));
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

    /** Prebuilt analytics-hooks jar; copied to {@code HooksJarLocation} at install time. */
    public static Path hooksJar() {
        return root("agentic-skills-hooks.jar");
    }

    /** Prebuilt (Spring Boot fat jar) issue-tickets MCP server. */
    public static Path issueTicketsMcpJar() {
        return root("issue-tickets.jar");
    }

    /** Prebuilt (Spring Boot fat jar) security-scanner MCP server. */
    public static Path securityScannerMcpJar() {
        return root("security-scanner.jar");
    }
}
