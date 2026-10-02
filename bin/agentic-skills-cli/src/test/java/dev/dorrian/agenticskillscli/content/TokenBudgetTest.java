package dev.dorrian.agenticskillscli.content;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Token-budget ratchet for the markdown that ends up in a model's context (agents, skills,
 * commands) or loads on every dev session (CLAUDE.md, AGENTS.md). Each file has a ceiling in
 * {@code token-budgets.properties}; ceilings only ever go down. Tokens are estimated as chars/4 -
 * a relative measure, so no tokenizer dependency. No model calls, no cost.
 */
class TokenBudgetTest {

    // Resolved independently of PackageRoot's shared mutable singleton - see AgentFileStructureTest.
    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();

    static int estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }

    private static Properties budgets() {
        Properties props = new Properties();
        try (InputStream in = TokenBudgetTest.class.getResourceAsStream("/token-budgets.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    /** Files that must declare a budget: anything that can load into model context. */
    private static List<String> governedFiles() {
        List<String> rels = new ArrayList<>();
        rels.add("CLAUDE.md");
        rels.add("AGENTS.md");
        rels.add("README.md");
        rels.add("evals/README.md");
        collect(REPO_ROOT.resolve("agents"), 1, rels);
        collect(REPO_ROOT.resolve(".opencode/commands"), 1, rels);
        collect(REPO_ROOT.resolve("skills"), 3, rels); // skills/<category>/<skill>/SKILL.md only
        rels.removeIf(r -> r.startsWith("skills/") && !r.endsWith("/SKILL.md"));
        return rels;
    }

    private static void collect(Path dir, int depth, List<String> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir, depth)) {
            walk.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p))
                    .forEach(p -> out.add(REPO_ROOT.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyFileStaysWithinItsTokenCeiling() {
        Properties budgets = budgets();
        return governedFiles().stream().sorted().map(rel -> dynamicTest(rel, () -> {
            String ceiling = budgets.getProperty(rel);
            assertTrue(ceiling != null, rel + " has no entry in token-budgets.properties");
            int tokens = estimateTokens(Files.readString(REPO_ROOT.resolve(rel)));
            assertTrue(tokens <= Integer.parseInt(ceiling.trim()),
                    rel + " is ~" + tokens + " tokens, over its ceiling of " + ceiling.trim());
        }));
    }

    @Test
    void noBudgetEntryPointsAtAMissingFile() {
        List<String> stale = budgets().stringPropertyNames().stream()
                .filter(rel -> !Files.isRegularFile(REPO_ROOT.resolve(rel))).sorted().toList();
        assertTrue(stale.isEmpty(), "budget entries for files that no longer exist: " + stale);
    }
}
