package dev.dorrian.agenticskillsevals.golden;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code evals/behavioral/lib/golden.ts}'s {@code GoldenSpec} type + {@code loadGolden}.
 * Deterministic string/tool/YAML validation - pure logic, no protocol dependency, no real API calls.
 */
public final class GoldenSpec {

    public record Check(String pattern, String label) {
    }

    public record YamlBlockCheck(String startPattern, String label, List<String> requiredFields) {
    }

    public record Tools(List<String> expected, List<String> forbidden) {
        public Tools {
            expected = expected == null ? List.of() : expected;
            forbidden = forbidden == null ? List.of() : forbidden;
        }
    }

    private final List<Check> required;
    private final List<String> forbidden;
    private final Tools tools;
    private final List<YamlBlockCheck> yamlBlocks;
    private final String referenceAnswer;

    public GoldenSpec(List<Check> required, List<String> forbidden, Tools tools,
                       List<YamlBlockCheck> yamlBlocks, String referenceAnswer) {
        this.required = required == null ? List.of() : required;
        this.forbidden = forbidden == null ? List.of() : forbidden;
        this.tools = tools == null ? new Tools(List.of(), List.of()) : tools;
        this.yamlBlocks = yamlBlocks == null ? List.of() : yamlBlocks;
        this.referenceAnswer = referenceAnswer;
    }

    public List<Check> required() {
        return required;
    }

    public List<String> forbidden() {
        return forbidden;
    }

    public Tools tools() {
        return tools;
    }

    public List<YamlBlockCheck> yamlBlocks() {
        return yamlBlocks;
    }

    public String referenceAnswer() {
        return referenceAnswer;
    }

    @SuppressWarnings("unchecked")
    public static GoldenSpec load(Path filePath) throws IOException {
        String raw = Files.readString(filePath);
        Yaml yaml = new Yaml(new Constructor(Object.class, new LoaderOptions()));
        Map<String, Object> parsed = (Map<String, Object>) yaml.load(raw);
        return fromMap(parsed);
    }

    @SuppressWarnings("unchecked")
    static GoldenSpec fromMap(Map<String, Object> parsed) {
        if (parsed == null) {
            return new GoldenSpec(List.of(), List.of(), null, List.of(), null);
        }
        List<Check> requiredChecks = new ArrayList<>();
        for (Object entry : asList(parsed.get("required"))) {
            Map<String, Object> m = (Map<String, Object>) entry;
            requiredChecks.add(new Check(String.valueOf(m.get("pattern")), String.valueOf(m.get("label"))));
        }

        List<String> forbiddenList = new ArrayList<>();
        for (Object entry : asList(parsed.get("forbidden"))) {
            forbiddenList.add(String.valueOf(entry));
        }

        Tools tools = null;
        Object toolsRaw = parsed.get("tools");
        if (toolsRaw instanceof Map) {
            Map<String, Object> toolsMap = (Map<String, Object>) toolsRaw;
            List<String> expected = new ArrayList<>();
            for (Object entry : asList(toolsMap.get("expected"))) {
                expected.add(String.valueOf(entry));
            }
            List<String> forbiddenTools = new ArrayList<>();
            for (Object entry : asList(toolsMap.get("forbidden"))) {
                forbiddenTools.add(String.valueOf(entry));
            }
            tools = new Tools(expected, forbiddenTools);
        }

        List<YamlBlockCheck> yamlBlockChecks = new ArrayList<>();
        for (Object entry : asList(parsed.get("yamlBlocks"))) {
            Map<String, Object> m = (Map<String, Object>) entry;
            List<String> requiredFields = new ArrayList<>();
            for (Object field : asList(m.get("requiredFields"))) {
                requiredFields.add(String.valueOf(field));
            }
            yamlBlockChecks.add(new YamlBlockCheck(
                    String.valueOf(m.get("startPattern")), String.valueOf(m.get("label")), requiredFields));
        }

        Object referenceAnswer = parsed.get("referenceAnswer");
        return new GoldenSpec(requiredChecks, forbiddenList, tools, yamlBlockChecks,
                referenceAnswer == null ? null : String.valueOf(referenceAnswer));
    }

    private static List<Object> asList(Object maybeList) {
        if (maybeList instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }
}
