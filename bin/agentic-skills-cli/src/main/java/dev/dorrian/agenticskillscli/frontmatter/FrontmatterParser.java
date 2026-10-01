package dev.dorrian.agenticskillscli.frontmatter;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of {@code bin/install.js}'s {@code parseFrontmatter()} — extracts
 * the {@code ---\n...\n---} YAML block from a markdown file's contents and
 * parses it. Returns an empty map if no frontmatter block is present, or if
 * parsing fails (matching the original's {@code catch { return {}; }}).
 */
public final class FrontmatterParser {

    // Mirrors /^---\r?\n([\s\S]*?)\r?\n---/ — DOTALL makes '.' match newlines,
    // and the non-greedy [\s\S]*? equivalent is '.*?' under DOTALL.
    private static final Pattern FRONTMATTER_BLOCK = Pattern.compile(
        "^---\r?\n(.*?)\r?\n---", Pattern.DOTALL
    );

    private FrontmatterParser() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String content) {
        Matcher matcher = FRONTMATTER_BLOCK.matcher(content);
        if (!matcher.find()) {
            return new LinkedHashMap<>();
        }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object parsed = yaml.load(matcher.group(1));
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Splits a markdown file's content into its raw frontmatter block text
     * and body, matching the original's {@code fmMatch} regex
     * ({@code /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/}). Returns null
     * if there is no frontmatter block (caller should pass the content
     * through unchanged in that case, exactly as {@code transformAgentContent}
     * does).
     */
    public static FrontmatterAndBody splitFrontmatterAndBody(String content) {
        Pattern fullPattern = Pattern.compile("^---\r?\n(.*?)\r?\n---\r?\n?(.*)$", Pattern.DOTALL);
        Matcher matcher = fullPattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        return new FrontmatterAndBody(matcher.group(1), matcher.group(2));
    }

    public record FrontmatterAndBody(String rawFrontmatter, String body) {
    }
}
