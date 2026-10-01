package dev.dorrian.agenticskillscli.ui;

import dev.dorrian.agenticskillscli.config.AgentRegistrationResult;
import dev.dorrian.agenticskillscli.config.OperationResult;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code printSummary()} and {@code
 * printUninstallSummary()} — formatted console reports over a {@link
 * ToolResults} map, ported line-for-line from the original console.log
 * output (icon/color/detail-text combinations preserved exactly).
 */
public final class SummaryPrinter {

    private SummaryPrinter() {
    }

    public static void printInstallSummary(Map<String, ToolResults> resultsByTool) {
        printInstallSummary(resultsByTool, new PrintWriter(System.out, true));
    }

    public static void printInstallSummary(Map<String, ToolResults> resultsByTool, PrintWriter out) {
        out.println();
        out.println("  " + Ansi.bold("Installation Summary"));
        out.println("  " + Ansi.dim("─".repeat(40)));

        int succeeded = 0;
        int failed = 0;

        for (Map.Entry<String, ToolResults> entry : resultsByTool.entrySet()) {
            ToolResults r = entry.getValue();
            out.println();
            out.println("  " + Ansi.boldYellow(r.toolName()));

            for (OperationResult s : r.skills()) {
                if (s.success()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white(s.name()) + " " + Ansi.dim("→ " + r.skillsPath()));
                    succeeded++;
                }
            }
            for (OperationResult s : r.skills()) {
                if (!s.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white(s.name()) + " " + Ansi.dim("— " + s.error()));
                    failed++;
                }
            }

            for (OperationResult c : r.commands()) {
                if (c.success()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white("/" + c.name()) + " " + Ansi.dim("→ " + r.commandsPath()));
                    succeeded++;
                }
            }
            for (OperationResult c : r.commands()) {
                if (!c.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white("/" + c.name()) + " " + Ansi.dim("— " + c.error()));
                    failed++;
                }
            }

            for (OperationResult a : r.agents()) {
                if (a.success()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white("@" + a.name()) + " " + Ansi.dim("→ " + r.agentsPath()));
                    succeeded++;
                }
            }
            for (OperationResult a : r.agents()) {
                if (!a.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white("@" + a.name()) + " " + Ansi.dim("— " + a.error()));
                    failed++;
                }
            }

            for (AgentRegistrationResult c : r.configRegs()) {
                if (c.success() && !c.skipped() && !c.repaired()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white("@" + c.name()) + " " + Ansi.dim("registered in " + c.configFile()));
                    succeeded++;
                }
            }
            for (AgentRegistrationResult c : r.configRegs()) {
                if (c.success() && !c.skipped() && c.repaired()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white("@" + c.name()) + " " + Ansi.dim("repaired malformed entry in " + c.configFile()));
                    succeeded++;
                }
            }
            for (AgentRegistrationResult c : r.configRegs()) {
                if (c.success() && c.skipped()) {
                    out.println("    " + Ansi.yellow("~") + "  " + Ansi.white("@" + c.name()) + " " + Ansi.dim("already in config — skipped"));
                }
            }
            for (AgentRegistrationResult c : r.configRegs()) {
                if (!c.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white("@" + c.name()) + " " + Ansi.dim("config registration failed — " + c.error()));
                    failed++;
                }
            }

            for (OperationResult t : r.templates()) {
                if (t.success()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white(t.name()) + " " + Ansi.dim("(template)"));
                    succeeded++;
                }
            }
            for (OperationResult t : r.templates()) {
                if (!t.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white(t.name()) + " " + Ansi.dim("— " + t.error()));
                    failed++;
                }
            }

            for (OperationResult m : r.mcps()) {
                if (m.success() && !m.skipped()) {
                    out.println("    " + Ansi.green("✔") + "  " + Ansi.white(m.name()) + " " + Ansi.dim("(MCP → " + m.configFile() + ")"));
                    succeeded++;
                }
            }
            for (OperationResult m : r.mcps()) {
                if (m.success() && m.skipped()) {
                    out.println("    " + Ansi.yellow("~") + "  " + Ansi.white(m.name()) + " " + Ansi.dim("(MCP — already installed, skipped)"));
                }
            }
            for (OperationResult m : r.mcps()) {
                if (!m.success()) {
                    out.println("    " + Ansi.red("✖") + "  " + Ansi.white(m.name()) + " " + Ansi.dim("(MCP — " + m.error() + ")"));
                    failed++;
                }
            }

            if (r.skills().isEmpty() && r.commands().isEmpty() && r.agents().isEmpty()
                && r.templates().isEmpty() && r.configRegs().isEmpty() && r.mcps().isEmpty()) {
                out.println("    " + Ansi.dim("nothing installed"));
            }
        }

        out.println();
        if (succeeded > 0) {
            out.println("  " + Ansi.boldGreen(succeeded + " item" + (succeeded > 1 ? "s" : "") + " installed successfully"));
        }
        if (failed > 0) {
            out.println("  " + Ansi.boldRed(failed + " item" + (failed > 1 ? "s" : "") + " failed"));
        }
        out.println();
        out.flush();
    }

    public static void printUninstallSummary(Map<String, ToolResults> resultsByTool) {
        printUninstallSummary(resultsByTool, new PrintWriter(System.out, true));
    }

    public static void printUninstallSummary(Map<String, ToolResults> resultsByTool, PrintWriter out) {
        out.println();
        out.println("  " + Ansi.bold("Uninstall Summary"));
        out.println("  " + Ansi.dim("─".repeat(40)));

        int removed = 0;
        int skipped = 0;
        int failed = 0;

        for (Map.Entry<String, ToolResults> entry : resultsByTool.entrySet()) {
            ToolResults r = entry.getValue();
            out.println();
            out.println("  " + Ansi.boldYellow(r.toolName()));

            for (OperationResult s : r.skills()) {
                if (s.success() && !s.skipped()) {
                    printItem(out, Ansi.green("✔"), s.name(), "removed from " + r.skillsPath());
                    removed++;
                } else if (s.skipped()) {
                    printItem(out, Ansi.yellow("~"), s.name(), "not found — skipped");
                    skipped++;
                } else {
                    printItem(out, Ansi.red("✖"), s.name(), s.error() != null ? s.error() : "error");
                    failed++;
                }
            }

            for (OperationResult c : r.commands()) {
                String label = "/" + c.name();
                if (c.success() && !c.skipped()) {
                    printItem(out, Ansi.green("✔"), label, "removed from " + r.commandsPath());
                    removed++;
                } else if (c.skipped()) {
                    printItem(out, Ansi.yellow("~"), label, "not found — skipped");
                    skipped++;
                } else {
                    printItem(out, Ansi.red("✖"), label, c.error() != null ? c.error() : "error");
                    failed++;
                }
            }

            for (OperationResult a : r.agents()) {
                String label = "@" + a.name();
                if (a.success() && !a.skipped()) {
                    printItem(out, Ansi.green("✔"), label, "removed from " + r.agentsPath());
                    removed++;
                } else if (a.skipped()) {
                    printItem(out, Ansi.yellow("~"), label, "not found — skipped");
                    skipped++;
                } else {
                    printItem(out, Ansi.red("✖"), label, a.error() != null ? a.error() : "error");
                    failed++;
                }
            }

            for (AgentRegistrationResult c : r.configRegs()) {
                String label = "@" + c.name();
                if (c.success() && !c.skipped()) {
                    printItem(out, Ansi.green("✔"), label, "unregistered from " + c.configFile());
                    removed++;
                } else if (c.skipped()) {
                    printItem(out, Ansi.yellow("~"), label, "not in config — skipped");
                    skipped++;
                } else {
                    printItem(out, Ansi.red("✖"), label, c.error() != null ? c.error() : "error");
                    failed++;
                }
            }

            for (OperationResult m : r.mcps()) {
                if (m.success() && !m.skipped()) {
                    printItem(out, Ansi.green("✔"), m.name(), "(MCP removed from " + m.configFile() + ")");
                    removed++;
                } else if (m.skipped()) {
                    printItem(out, Ansi.yellow("~"), m.name(), "(MCP — not found, skipped)");
                    skipped++;
                } else {
                    printItem(out, Ansi.red("✖"), m.name(), m.error() != null ? m.error() : "error");
                    failed++;
                }
            }

            int total = r.skills().size() + r.commands().size() + r.agents().size() + r.configRegs().size() + r.mcps().size();
            if (total == 0) {
                out.println("    " + Ansi.dim("nothing to remove"));
            }
        }

        out.println();
        if (removed > 0) {
            out.println("  " + Ansi.boldGreen(removed + " item" + (removed > 1 ? "s" : "") + " removed successfully"));
        }
        if (skipped > 0) {
            out.println("  " + Ansi.boldYellow(skipped + " item" + (skipped > 1 ? "s" : "") + " not found — skipped"));
        }
        if (failed > 0) {
            out.println("  " + Ansi.boldRed(failed + " item" + (failed > 1 ? "s" : "") + " failed"));
        }
        out.println();
        out.flush();
    }

    private static void printItem(PrintWriter out, String icon, String label, String detail) {
        out.println("    " + icon + "  " + Ansi.white(label) + " " + Ansi.dim(detail));
    }

    /** Convenience for building a {@code LinkedHashMap}-backed resultsByTool in insertion order, matching JS object iteration order. */
    public static Map<String, ToolResults> newResultsMap() {
        return new LinkedHashMap<>();
    }
}
