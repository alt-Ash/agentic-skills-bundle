package dev.dorrian.agenticskillsevals.golden;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Port of {@code evals/behavioral/lib/golden.ts}'s {@code runGoldenChecks}. */
public final class GoldenChecker {

    public record Result(Map<String, Boolean> details, int passed, int total) {
    }

    private GoldenChecker() {
    }

    public static Result run(String response, GoldenSpec spec) {
        return run(response, spec, Map.of());
    }

    @SuppressWarnings("unchecked")
    public static Result run(String response, GoldenSpec spec, Map<String, Integer> toolCalls) {
        Map<String, Boolean> details = new LinkedHashMap<>();

        for (GoldenSpec.Check check : spec.required()) {
            details.put(check.label(), response.contains(check.pattern()));
        }

        for (String forbidden : spec.forbidden()) {
            String key = "notContains_" + forbidden.substring(0, Math.min(20, forbidden.length()))
                    .replaceAll("\\W+", "_");
            details.put(key, !response.contains(forbidden));
        }

        for (String tool : spec.tools().expected()) {
            details.put("toolUsed_" + tool, toolCalls.getOrDefault(tool, 0) > 0);
        }

        for (String tool : spec.tools().forbidden()) {
            details.put("toolNotUsed_" + tool, toolCalls.getOrDefault(tool, 0) == 0);
        }

        for (GoldenSpec.YamlBlockCheck check : spec.yamlBlocks()) {
            int startIdx = response.indexOf(check.startPattern());
            if (startIdx == -1) {
                details.put(check.label(), false);
                continue;
            }
            String afterStart = response.substring(startIdx);
            int codeFenceEnd = afterStart.indexOf("\n```");
            String block = codeFenceEnd > 0
                    ? afterStart.substring(0, codeFenceEnd)
                    : afterStart.split("\n\n", 2)[0];
            try {
                Yaml yaml = new Yaml(new Constructor(Object.class, new LoaderOptions()));
                Object parsed = yaml.load(block);
                boolean ok = false;
                if (parsed instanceof Map<?, ?> parsedMap && !parsedMap.isEmpty()) {
                    Object topValue = parsedMap.values().iterator().next();
                    Map<String, Object> content = topValue instanceof Map
                            ? (Map<String, Object>) topValue : Map.of();
                    ok = check.requiredFields().stream().allMatch(content::containsKey);
                }
                details.put(check.label(), ok);
            } catch (Exception e) {
                details.put(check.label(), false);
            }
        }

        int passed = (int) details.values().stream().filter(Boolean::booleanValue).count();
        return new Result(details, passed, details.size());
    }
}
