package dev.dorrian.agenticskillsevals.golden;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Port of {@code evals/behavioral/lib/golden.test.ts}. Pure logic - zero real API calls. */
class GoldenCheckerTest {

    private static final GoldenSpec BASE_SPEC = new GoldenSpec(
            List.of(new GoldenSpec.Check("agent_contract:", "hasAgentContract")),
            List.of("I cannot"),
            null, List.of(), null);

    @Test
    void requiredPatternPresentPasses() {
        GoldenChecker.Result result = GoldenChecker.run("agent_contract: foo", BASE_SPEC);
        assertTrue(result.details().get("hasAgentContract"));
        assertEquals(2, result.passed());
        assertEquals(2, result.total());
    }

    @Test
    void requiredPatternAbsentFails() {
        GoldenChecker.Result result = GoldenChecker.run("some other text", BASE_SPEC);
        assertFalse(result.details().get("hasAgentContract"));
        assertTrue(result.passed() < result.total());
    }

    @Test
    void forbiddenPatternAbsentPasses() {
        GoldenChecker.Result result = GoldenChecker.run("all good", BASE_SPEC);
        String matchKey = result.details().keySet().stream().filter(k -> k.startsWith("notContains")).findFirst().orElseThrow();
        assertTrue(result.details().get(matchKey));
    }

    @Test
    void forbiddenPatternPresentFails() {
        GoldenChecker.Result result = GoldenChecker.run("I cannot do that", BASE_SPEC);
        String matchKey = result.details().keySet().stream().filter(k -> k.startsWith("notContains")).findFirst().orElseThrow();
        assertFalse(result.details().get(matchKey));
        assertTrue(result.passed() < result.total());
    }

    @Test
    void toolsExpectedPassesWhenCalled() {
        GoldenSpec spec = new GoldenSpec(List.of(), null, new GoldenSpec.Tools(List.of("Read"), null), List.of(), null);
        GoldenChecker.Result result = GoldenChecker.run("", spec, Map.of("Read", 2));
        assertTrue(result.details().get("toolUsed_Read"));
        assertEquals(1, result.passed());
    }

    @Test
    void toolsExpectedFailsWhenNotCalled() {
        GoldenSpec spec = new GoldenSpec(List.of(), null, new GoldenSpec.Tools(List.of("Read"), null), List.of(), null);
        GoldenChecker.Result result = GoldenChecker.run("", spec, Map.of());
        assertFalse(result.details().get("toolUsed_Read"));
        assertEquals(0, result.passed());
        assertEquals(1, result.total());
    }

    @Test
    void toolsForbiddenPassesWhenNotCalled() {
        GoldenSpec spec = new GoldenSpec(List.of(), null, new GoldenSpec.Tools(null, List.of("Write", "Edit")), List.of(), null);
        GoldenChecker.Result result = GoldenChecker.run("", spec, Map.of());
        assertTrue(result.details().get("toolNotUsed_Write"));
        assertTrue(result.details().get("toolNotUsed_Edit"));
        assertEquals(2, result.passed());
    }

    @Test
    void toolsForbiddenFailsWhenCalled() {
        GoldenSpec spec = new GoldenSpec(List.of(), null, new GoldenSpec.Tools(null, List.of("Write", "Edit")), List.of(), null);
        GoldenChecker.Result result = GoldenChecker.run("", spec, Map.of("Write", 1));
        assertFalse(result.details().get("toolNotUsed_Write"));
        assertEquals(1, result.passed());
        assertEquals(2, result.total());
    }

    @Test
    void defaultsToEmptyToolCalls() {
        GoldenSpec spec = new GoldenSpec(List.of(), null, new GoldenSpec.Tools(null, List.of("Bash")), List.of(), null);
        GoldenChecker.Result result = GoldenChecker.run("", spec);
        assertTrue(result.details().get("toolNotUsed_Bash"));
    }

    private static final GoldenSpec YAML_SPEC = new GoldenSpec(List.of(), null, null,
            List.of(new GoldenSpec.YamlBlockCheck("agent_contract:", "agentContractValid",
                    List.of("type", "inputs", "outputs"))),
            null);

    @Test
    void yamlBlockPassesWhenAllRequiredFieldsPresent() {
        String response = "agent_contract:\n  type: feature\n  inputs:\n    - name: foo\n  outputs:\n    - bar\n";
        GoldenChecker.Result result = GoldenChecker.run(response, YAML_SPEC);
        assertTrue(result.details().get("agentContractValid"));
        assertEquals(1, result.passed());
    }

    @Test
    void yamlBlockFailsWhenStartPatternAbsent() {
        GoldenChecker.Result result = GoldenChecker.run("no yaml here", YAML_SPEC);
        assertFalse(result.details().get("agentContractValid"));
        assertEquals(0, result.passed());
    }

    @Test
    void yamlBlockFailsWhenRequiredFieldsMissing() {
        String response = "agent_contract:\n  type: feature\n";
        GoldenChecker.Result result = GoldenChecker.run(response, YAML_SPEC);
        assertFalse(result.details().get("agentContractValid"));
    }

    @Test
    void yamlBlockFailsWhenMalformed() {
        String response = "agent_contract:\n  type: :\n  bad: [unclosed\n";
        GoldenChecker.Result result = GoldenChecker.run(response, YAML_SPEC);
        assertFalse(result.details().get("agentContractValid"));
    }
}
