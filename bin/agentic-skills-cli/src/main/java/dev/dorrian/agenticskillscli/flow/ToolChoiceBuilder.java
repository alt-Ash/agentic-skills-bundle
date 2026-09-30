package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.ui.Ansi;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds the checkbox choice labels for AI-tool selection prompts, and maps
 * a chosen label back to its tool key. Port of the {@code toolChoices}/
 * {@code toolChoicesQuick} label-building logic duplicated at both call
 * sites in the original ({@code bin/install.js} lines ~2393-2403 and
 * ~2662-2672 — identical logic, just a different local variable name).
 */
public final class ToolChoiceBuilder {

    private ToolChoiceBuilder() {
    }

    /** Ordered key -&gt; display label; "detected" tools get a green checkmark suffix, others are dimmed. */
    public static Map<String, String> labelsByKey(Map<String, Boolean> detectedTools) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, AgentToolDef> entry : AgentToolRegistry.ALL.entrySet()) {
            AgentToolDef tool = entry.getValue();
            boolean detected = Boolean.TRUE.equals(detectedTools.get(entry.getKey()));
            String label = detected
                ? Ansi.cyan(tool.name()) + "  " + Ansi.green("✔ detected")
                : Ansi.dim(tool.name());
            labels.put(entry.getKey(), label);
        }
        return labels;
    }

    /** The subset of labels whose tool was detected (used to pre-check the checkbox). */
    public static Set<String> preCheckedLabels(Map<String, String> labelsByKey, Map<String, Boolean> detectedTools) {
        Set<String> labels = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : labelsByKey.entrySet()) {
            if (Boolean.TRUE.equals(detectedTools.get(entry.getKey()))) {
                labels.add(entry.getValue());
            }
        }
        return labels;
    }

    public static String keyForLabel(Map<String, String> labelsByKey, String label) {
        for (Map.Entry<String, String> entry : labelsByKey.entrySet()) {
            if (entry.getValue().equals(label)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Unknown tool label: " + label);
    }
}
