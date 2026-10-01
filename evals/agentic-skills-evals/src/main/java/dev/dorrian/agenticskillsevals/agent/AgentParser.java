package dev.dorrian.agenticskillsevals.agent;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code evals/behavioral/lib/parse-agent.ts}'s {@code parseAgent} - splits an agent
 * markdown file into YAML frontmatter + body (equivalent of {@code gray-matter}'s split, not the
 * full field-validation {@code bin/agentic-skills-cli}'s installer-side parser does - this one just
 * needs the description + system prompt for eval purposes).
 */
public final class AgentParser {

    private static final Pattern FRONTMATTER_BLOCK = Pattern.compile(
            "^---\r?\n(.*?)\r?\n---\r?\n?(.*)$", Pattern.DOTALL);

    public record ParsedAgent(AgentFrontmatter frontmatter, String systemPrompt, Path filePath) {
    }

    private AgentParser() {
    }

    @SuppressWarnings("unchecked")
    public static ParsedAgent parse(Path filePath) throws IOException {
        String raw = Files.readString(filePath);
        Matcher matcher = FRONTMATTER_BLOCK.matcher(raw);

        Map<String, Object> data = new LinkedHashMap<>();
        String content = raw;
        if (matcher.find()) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object parsed = yaml.load(matcher.group(1));
            if (parsed instanceof Map<?, ?> map) {
                data = (Map<String, Object>) map;
            }
            content = matcher.group(2);
        }

        AgentFrontmatter frontmatter = new AgentFrontmatter(
                asString(data.get("description")),
                asString(data.get("mode")),
                data.get("temperature") == null ? null : ((Number) data.get("temperature")).doubleValue(),
                asString(data.get("color")),
                data.get("permission") instanceof Map ? (Map<String, Object>) data.get("permission") : Map.of(),
                asString(data.get("name")));

        return new ParsedAgent(frontmatter, content.trim(), filePath);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
