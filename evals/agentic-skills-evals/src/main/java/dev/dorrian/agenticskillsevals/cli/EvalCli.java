package dev.dorrian.agenticskillsevals.cli;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main-Class for the shaded {@code agentic-skills-evals.jar}. Subcommands: {@code check
 * <agent-name> [scenario-name]}, {@code select}, {@code report [--save-baseline] [--window=N]}.
 *
 * <p>{@code check} runs eval scenarios by invoking the JUnit Platform Launcher against the
 * {@code @TestFactory}-based eval classes under {@code src/test/java} - those stay JUnit tests
 * (so {@code mvn test} keeps working as their other entrypoint) rather than being duplicated as a
 * separate main-scope runner. Since test classes aren't on the main jar's classpath, {@code check}
 * loads them from {@code target/test-classes} (a sibling of this jar's own {@code target/}
 * directory) via a small child classloader - this requires {@code target/test-classes} to exist,
 * i.e. {@code mvn test-compile} (or {@code test}/{@code package}, which both run it first) must
 * have been run at least once.
 */
public final class EvalCli {

    private static final Map<String, String> AGENT_TEST_CLASSES = Map.of(
            "issue-architect", "dev.dorrian.agenticskillsevals.IssueArchitectEvalTest",
            "security-auditor", "dev.dorrian.agenticskillsevals.SecurityAuditorEvalTest",
            "security-implementor", "dev.dorrian.agenticskillsevals.SecurityImplementorEvalTest",
            "tdd-engineer", "dev.dorrian.agenticskillsevals.TddEngineerEvalTest");

    private EvalCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(2);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int exitCode = switch (args[0]) {
            case "check" -> runCheck(rest);
            case "select" -> {
                EvalSelector.run();
                yield 0;
            }
            case "report" -> {
                EvalReport.run(rest);
                yield 0;
            }
            default -> {
                printUsage();
                yield 2;
            }
        };
        System.exit(exitCode);
    }

    private static void printUsage() {
        System.err.println("Usage: EvalCli <check <agent-name> [scenario-name] | select | report [--save-baseline] [--window=N]>");
        System.err.println("Known agents: " + String.join(", ", AGENT_TEST_CLASSES.keySet()));
    }

    static int runCheck(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("check requires an agent name. Known agents: " + String.join(", ", AGENT_TEST_CLASSES.keySet()));
            return 2;
        }
        String agentName = args[0];
        String scenarioName = args.length > 1 ? args[1] : null;
        String fqcn = AGENT_TEST_CLASSES.get(agentName);
        if (fqcn == null) {
            System.err.println("Unknown agent \"" + agentName + "\". Known agents: " + String.join(", ", AGENT_TEST_CLASSES.keySet()));
            return 2;
        }

        Path testClassesDir = resolveTestClassesDir();
        if (!Files.isDirectory(testClassesDir)) {
            System.err.println("Test classes not found at " + testClassesDir
                    + " - run `mvn test-compile` (or `test`/`package`) in evals/agentic-skills-evals first.");
            return 2;
        }

        if (scenarioName != null) {
            System.out.println("Note: precise per-scenario selection isn't supported by the JUnit"
                    + " Platform Launcher for @TestFactory-generated DynamicTests (they aren't"
                    + " statically discoverable before the containing factory method runs)."
                    + " Running all scenarios for \"" + agentName + "\"; scan the per-scenario"
                    + " output below for \"" + scenarioName + "\".");
        }

        try (URLClassLoader testClassLoader = new URLClassLoader(
                new URL[] {testClassesDir.toUri().toURL()}, EvalCli.class.getClassLoader())) {
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            current.setContextClassLoader(testClassLoader);
            try {
                Class<?> testClass = Class.forName(fqcn, true, testClassLoader);
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(testClass))
                        .build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);

                TestExecutionSummary summary = listener.getSummary();
                PrintWriter out = new PrintWriter(System.out);
                summary.printTo(out);
                summary.printFailuresTo(out);
                out.flush();
                return summary.getTotalFailureCount() == 0 ? 0 : 1;
            } finally {
                current.setContextClassLoader(previous);
            }
        }
    }

    /** {@code target/test-classes}, resolved as a sibling of this jar's own {@code target/} directory. */
    private static Path resolveTestClassesDir() throws Exception {
        CodeSource codeSource = EvalCli.class.getProtectionDomain().getCodeSource();
        Path jarOrClassesDir = Path.of(codeSource.getLocation().toURI());
        // jarOrClassesDir is either .../target/agentic-skills-evals.jar or .../target/classes/
        Path targetDir = jarOrClassesDir.getFileName().toString().endsWith(".jar")
                ? jarOrClassesDir.getParent()
                : jarOrClassesDir.getParent(); // .../target/classes -> parent is target/
        return targetDir.resolve("test-classes");
    }

    // Exposed for EvalReport's agent::scenario grouping key parsing convenience, and for tests.
    static Map<String, String> knownAgents() {
        return new LinkedHashMap<>(AGENT_TEST_CLASSES);
    }
}
