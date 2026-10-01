package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolChoiceBuilderTest {

    @Test
    void labelsByKeyCoversAllEightTools() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of());
        assertEquals(8, labels.size());
        assertTrue(labels.containsKey("opencode"));
        assertTrue(labels.containsKey("zed"));
    }

    @Test
    void detectedToolsGetACheckmarkLabel() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of("claude", true));
        assertTrue(labels.get("claude").contains("detected"));
    }

    @Test
    void undetectedToolsHaveNoCheckmark() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of("claude", false));
        assertTrue(!labels.get("claude").contains("detected"));
    }

    @Test
    void preCheckedLabelsOnlyIncludesDetectedTools() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of("claude", true, "opencode", false));
        Set<String> preChecked = ToolChoiceBuilder.preCheckedLabels(labels, Map.of("claude", true, "opencode", false));
        assertEquals(Set.of(labels.get("claude")), preChecked);
    }

    @Test
    void keyForLabelRoundTrips() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of());
        String label = labels.get("cursor");
        assertEquals("cursor", ToolChoiceBuilder.keyForLabel(labels, label));
    }

    @Test
    void keyForUnknownLabelThrows() {
        Map<String, String> labels = ToolChoiceBuilder.labelsByKey(Map.of());
        assertThrows(IllegalStateException.class, () -> ToolChoiceBuilder.keyForLabel(labels, "nonsense"));
    }
}
