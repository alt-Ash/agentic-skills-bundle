package dev.dorrian.agenticskillscli.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Line;
import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.NewItem;
import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpgradeReportFormatterTest {

    private static Line line(String tool, String kind, String name, Outcome o, String detail) {
        return new Line(tool, kind, name, o, detail);
    }

    @Test
    void formatsTheExampleExactly() {
        String actual = UpgradeReportFormatter.format("3.0.0", "3.1.0", List.of("claude"), List.of(
                line("claude", "skill", "parallel-feature-build", Outcome.REFRESHED, null),
                line("claude", "agent", "pr-reviewer", Outcome.REFRESHED, null),
                line("claude", "skill", "validation-loop", Outcome.UNCHANGED, null),
                line("claude", "skill", "grilling", Outcome.UNCHANGED, null),
                line("claude", "skill", "find-skills", Outcome.UNCHANGED, null)),
                List.of(new NewItem("mcp", "local-codegen", "3.1.0")));
        String expected = String.join("\n",
                "Upgrading from 3.0.0 to 3.1.0 (claude)",
                "",
                "claude",
                "  refreshed   skill  parallel-feature-build",
                "  refreshed   agent  pr-reviewer",
                "  unchanged   skill  validation-loop  (+2 more)",
                "",
                "Not installed by upgrade (run `agentic-skills` and choose Install):",
                "  mcp     local-codegen  (new in 3.1.0)",
                "",
                "2 refreshed, 3 unchanged, 0 failed, 1 new and not installed.");
        assertEquals(expected, actual);
    }

    @Test
    void emptyInputsGiveHeaderAndZeroSummary() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of("claude"), List.of(), List.of());
        assertEquals("Upgrading from 1 to 2 (claude)\n\n0 refreshed, 0 unchanged, 0 failed, 0 new and not installed.",
                actual);
    }

    @Test
    void failedLineShowsDetailOrUnknownError() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of("claude"), List.of(
                line("claude", "skill", "a", Outcome.FAILED, "disk full"),
                line("claude", "agent", "b", Outcome.FAILED, null)), List.of());
        assertTrue(actual.contains("  FAILED      skill  a: disk full\n"));
        assertTrue(actual.contains("  FAILED      agent  b: unknown error\n"));
        assertTrue(actual.endsWith("0 refreshed, 0 unchanged, 2 failed, 0 new and not installed."));
    }

    @Test
    void skippedModifiedLineAndSummaryPart() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of("claude"), List.of(
                line("claude", "skill", "a", Outcome.SKIPPED_MODIFIED, null)), List.of());
        assertTrue(actual.contains(
                "  skipped     skill  a  (modified locally; choose Install to overwrite)\n"));
        assertTrue(actual.endsWith(
                "0 refreshed, 0 unchanged, 0 failed, 1 skipped (modified locally), 0 new and not installed."));
    }

    @Test
    void toolsWithoutLinesAreOmitted() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of("claude", "opencode"), List.of(
                line("opencode", "skill", "a", Outcome.REFRESHED, null)), List.of());
        String expected = String.join("\n",
                "Upgrading from 1 to 2 (claude, opencode)",
                "",
                "opencode",
                "  refreshed   skill  a",
                "",
                "1 refreshed, 0 unchanged, 0 failed, 0 new and not installed.");
        assertEquals(expected, actual);
    }

    @Test
    void unchangedCollapsesPerKindInFirstSeenOrder() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of("claude"), List.of(
                line("claude", "agent", "a1", Outcome.UNCHANGED, null),
                line("claude", "skill", "s1", Outcome.UNCHANGED, null),
                line("claude", "agent", "a2", Outcome.UNCHANGED, null),
                line("claude", "command", "c1", Outcome.UNCHANGED, null)), List.of());
        String expected = String.join("\n",
                "Upgrading from 1 to 2 (claude)",
                "",
                "claude",
                "  unchanged   agent  a1  (+1 more)",
                "  unchanged   skill  s1",
                "  unchanged   command  c1",
                "",
                "0 refreshed, 4 unchanged, 0 failed, 0 new and not installed.");
        assertEquals(expected, actual);
    }

    @Test
    void newItemWithoutVersionHasNoSuffix() {
        String actual = UpgradeReportFormatter.format("1", "2", List.of(), List.of(),
                List.of(new NewItem("skill", "x", null)));
        assertTrue(actual.contains("\n  skill   x\n"));
        assertTrue(!actual.contains("new in"));
    }

    @Test
    void nullArgumentIsRejected() {
        assertThrows(NullPointerException.class,
                () -> UpgradeReportFormatter.format(null, "2", List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> UpgradeReportFormatter.format("1", "2", List.of(), null, List.of()));
    }
}
