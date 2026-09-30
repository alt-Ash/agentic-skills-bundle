package dev.dorrian.agenticskillscli.ui;

import dev.dorrian.agenticskillscli.config.AgentRegistrationResult;
import dev.dorrian.agenticskillscli.config.OperationResult;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryPrinterTest {

    @Test
    void installSummaryReportsSuccessSkippedAndFailedCounts() {
        ToolResults r = new ToolResults(
            "Claude Code",
            List.of(OperationResult.ok("react-migration", false, null), OperationResult.failed("broken-skill", null, "boom")),
            List.of(OperationResult.ok("migrate-node", false, null)),
            List.of(OperationResult.ok("plan", false, null)),
            List.of(new AgentRegistrationResult("plan", true, true, false, "/x/opencode.json")),
            List.of(OperationResult.ok("CLAUDE.md", false, null)),
            List.of(OperationResult.ok("issue-tickets", false, "/x/claude.json"), OperationResult.ok("security-scanner", true, "/x/claude.json")),
            "/skills", "/commands", "/agents"
        );
        Map<String, ToolResults> byTool = new LinkedHashMap<>();
        byTool.put("claude", r);

        StringWriter sw = new StringWriter();
        SummaryPrinter.printInstallSummary(byTool, new PrintWriter(sw));
        String output = sw.toString();

        assertTrue(output.contains("Installation Summary"));
        assertTrue(output.contains("react-migration"));
        assertTrue(output.contains("boom"));
        assertTrue(output.contains("already in config — skipped"));
        assertTrue(output.contains("already installed, skipped"));
        assertTrue(output.contains("5 items installed successfully"));
        assertTrue(output.contains("1 item failed"));
    }

    @Test
    void installSummaryPrintsNothingInstalledWhenAllListsEmpty() {
        Map<String, ToolResults> byTool = new LinkedHashMap<>();
        byTool.put("cursor", ToolResults.empty("Cursor"));

        StringWriter sw = new StringWriter();
        SummaryPrinter.printInstallSummary(byTool, new PrintWriter(sw));

        assertTrue(sw.toString().contains("nothing installed"));
    }

    @Test
    void uninstallSummaryReportsRemovedSkippedAndFailedCounts() {
        ToolResults r = new ToolResults(
            "OpenCode",
            List.of(OperationResult.ok("react-migration", false, null), OperationResult.ok("never-installed", true, null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(OperationResult.failed("issue-tickets", "/x/opencode.json", "permission denied")),
            "/skills", null, null
        );
        Map<String, ToolResults> byTool = new LinkedHashMap<>();
        byTool.put("opencode", r);

        StringWriter sw = new StringWriter();
        SummaryPrinter.printUninstallSummary(byTool, new PrintWriter(sw));
        String output = sw.toString();

        assertTrue(output.contains("Uninstall Summary"));
        assertTrue(output.contains("removed from /skills"));
        assertTrue(output.contains("not found — skipped"));
        assertTrue(output.contains("permission denied"));
        assertTrue(output.contains("1 item removed successfully"));
        assertTrue(output.contains("1 item not found — skipped"));
        assertTrue(output.contains("1 item failed"));
    }

    @Test
    void uninstallSummaryPrintsNothingToRemoveWhenAllListsEmpty() {
        Map<String, ToolResults> byTool = new LinkedHashMap<>();
        byTool.put("zed", ToolResults.empty("Zed"));

        StringWriter sw = new StringWriter();
        SummaryPrinter.printUninstallSummary(byTool, new PrintWriter(sw));

        assertTrue(sw.toString().contains("nothing to remove"));
    }
}
