package dev.dorrian.agenticskillsevals.cli;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interactive checkbox-style picker, port of {@code evals/eval-selector.ts}. Discovers agent ->
 * scenario choices from the fixtures directories (mirroring the TS original's
 * {@code fixtures/<agent>/scenario-*.md} scan), lets the user toggle a subset by typing
 * space-separated numbers, then runs {@link EvalCli#runCheck} for each selected agent.
 *
 * <p>Self-contained JLine usage (not a dependency on {@code bin/agentic-skills-cli}'s
 * {@code Prompter}) - this repo has no shared-module convention between its independent sibling
 * Maven projects, so a small, narrow duplication here is more consistent than introducing one.
 */
final class EvalSelector {

    private static final Pattern SCENARIO_MD = Pattern.compile("^(scenario-.*)\\.md$");

    private EvalSelector() {
    }

    static void run() throws Exception {
        Map<String, List<String>> choices = discoverChoices();
        if (choices.isEmpty()) {
            System.out.println("No fixtures found under target/test-classes/fixtures - run `mvn test-compile` first.");
            return;
        }

        List<String[]> flat = new ArrayList<>(); // [agent, scenarioOrNull]
        for (var entry : choices.entrySet()) {
            if (entry.getValue().isEmpty()) {
                flat.add(new String[] {entry.getKey(), null});
            } else {
                for (String scenario : entry.getValue()) {
                    flat.add(new String[] {entry.getKey(), scenario});
                }
            }
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            terminal.writer().println("Select scenarios to run (space-separated numbers, blank = none, \"a\" = all):");
            for (int i = 0; i < flat.size(); i++) {
                String[] pair = flat.get(i);
                terminal.writer().printf("  [%d] %s%s%n", i + 1, pair[0], pair[1] != null ? " :: " + pair[1] : " (all)");
            }
            terminal.flush();
            String line = reader.readLine("> ").trim();

            Set<Integer> selected = new LinkedHashSet<>();
            if (line.equalsIgnoreCase("a") || line.equalsIgnoreCase("all")) {
                for (int i = 0; i < flat.size(); i++) selected.add(i);
            } else {
                for (String token : line.split("\\s+")) {
                    if (token.isBlank()) continue;
                    try {
                        int idx = Integer.parseInt(token) - 1;
                        if (idx >= 0 && idx < flat.size()) selected.add(idx);
                    } catch (NumberFormatException ignored) {
                        // skip malformed tokens rather than aborting the whole selection
                    }
                }
            }

            if (selected.isEmpty()) {
                terminal.writer().println("Nothing selected.");
                return;
            }

            Map<String, List<String>> byAgent = new LinkedHashMap<>();
            for (int idx : selected) {
                String[] pair = flat.get(idx);
                byAgent.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            }

            int overallExit = 0;
            for (var entry : byAgent.entrySet()) {
                String agent = entry.getKey();
                List<String> scenarios = entry.getValue();
                String[] checkArgs = (scenarios.size() == 1 && scenarios.get(0) != null)
                        ? new String[] {agent, scenarios.get(0)}
                        : new String[] {agent};
                terminal.writer().println();
                terminal.writer().println("=== " + agent + " ===");
                terminal.flush();
                int exit = EvalCli.runCheck(checkArgs);
                overallExit = overallExit == 0 ? exit : overallExit;
            }
            if (overallExit != 0) {
                System.exit(overallExit);
            }
        }
    }

    /** agentName -> list of scenario names (empty list = fixtures dir exists but has no per-scenario .md files, i.e. "(all)"). */
    private static Map<String, List<String>> discoverChoices() throws IOException, URISyntaxException {
        Path fixturesRoot = resolveFixturesRoot();
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(fixturesRoot)) {
            return result;
        }
        try (Stream<Path> agents = Files.list(fixturesRoot)) {
            for (Path agentDir : agents.filter(Files::isDirectory).sorted().toList()) {
                String agentName = agentDir.getFileName().toString();
                if (!EvalCli.knownAgents().containsKey(agentName)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(agentDir)) {
                    List<String> scenarios = files
                            .map(p -> p.getFileName().toString())
                            .map(SCENARIO_MD::matcher)
                            .filter(java.util.regex.Matcher::matches)
                            .map(m -> m.group(1))
                            .sorted()
                            .collect(Collectors.toList());
                    result.put(agentName, scenarios);
                }
            }
        }
        return result;
    }

    private static Path resolveFixturesRoot() throws URISyntaxException {
        CodeSource codeSource = EvalSelector.class.getProtectionDomain().getCodeSource();
        URL location = codeSource.getLocation();
        Path jarOrClassesDir = Path.of(location.toURI());
        Path targetDir = jarOrClassesDir.getFileName().toString().endsWith(".jar")
                ? jarOrClassesDir.getParent()
                : jarOrClassesDir.getParent();
        return targetDir.resolve("test-classes").resolve("fixtures");
    }
}
