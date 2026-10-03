package dev.dorrian.agenticskillscli.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure text formatter for the upgrade report. */
public final class UpgradeReportFormatter {

    public enum Outcome { REFRESHED, UNCHANGED, FAILED, SKIPPED_MODIFIED }

    public record Line(String tool, String kind, String name, Outcome outcome, String detail) {}

    public record NewItem(String kind, String name, String sinceVersion) {}

    private UpgradeReportFormatter() {}

    public static String format(String fromVersion, String toVersion, List<String> tools,
                                List<Line> lines, List<NewItem> notInstalled) {
        Objects.requireNonNull(fromVersion);
        Objects.requireNonNull(toVersion);
        Objects.requireNonNull(tools);
        Objects.requireNonNull(lines);
        Objects.requireNonNull(notInstalled);

        List<String> out = new ArrayList<>();
        out.add("Upgrading from " + fromVersion + " to " + toVersion + " (" + String.join(", ", tools) + ")");
        out.add("");

        for (String tool : tools) {
            List<Line> toolLines = lines.stream().filter(l -> tool.equals(l.tool())).toList();
            if (toolLines.isEmpty()) {
                continue;
            }
            out.add(tool);
            for (Line l : toolLines) {
                if (l.outcome() == Outcome.REFRESHED) {
                    out.add("  " + pad("refreshed") + l.kind() + "  " + l.name());
                }
            }
            for (Line l : toolLines) {
                if (l.outcome() == Outcome.FAILED) {
                    String detail = l.detail() == null ? "unknown error" : l.detail();
                    out.add("  " + pad("FAILED") + l.kind() + "  " + l.name() + ": " + detail);
                }
            }
            for (Line l : toolLines) {
                if (l.outcome() == Outcome.SKIPPED_MODIFIED) {
                    out.add("  " + pad("skipped") + l.kind() + "  " + l.name()
                            + "  (modified locally; choose Install to overwrite)");
                }
            }
            Map<String, List<Line>> unchanged = new LinkedHashMap<>();
            for (Line l : toolLines) {
                if (l.outcome() == Outcome.UNCHANGED) {
                    unchanged.computeIfAbsent(l.kind(), k -> new ArrayList<>()).add(l);
                }
            }
            for (Map.Entry<String, List<Line>> e : unchanged.entrySet()) {
                List<Line> items = e.getValue();
                String text = "  " + pad("unchanged") + e.getKey() + "  " + items.get(0).name();
                if (items.size() > 1) {
                    text += "  (+" + (items.size() - 1) + " more)";
                }
                out.add(text);
            }
            out.add("");
        }

        if (!notInstalled.isEmpty()) {
            out.add("Not installed by upgrade (run `agentic-skills` and choose Install):");
            for (NewItem n : notInstalled) {
                String text = "  " + String.format("%-6s", n.kind()) + "  " + n.name();
                if (n.sinceVersion() != null) {
                    text += "  (new in " + n.sinceVersion() + ")";
                }
                out.add(text);
            }
            out.add("");
        }

        long r = count(lines, Outcome.REFRESHED);
        long u = count(lines, Outcome.UNCHANGED);
        long f = count(lines, Outcome.FAILED);
        long s = count(lines, Outcome.SKIPPED_MODIFIED);
        StringBuilder summary = new StringBuilder();
        summary.append(r).append(" refreshed, ").append(u).append(" unchanged, ").append(f).append(" failed, ");
        if (s > 0) {
            summary.append(s).append(" skipped (modified locally), ");
        }
        summary.append(notInstalled.size()).append(" new and not installed.");
        out.add(summary.toString());

        return String.join("\n", out);
    }

    private static String pad(String label) {
        return String.format("%-12s", label);
    }

    private static long count(List<Line> lines, Outcome o) {
        return lines.stream().filter(l -> l.outcome() == o).count();
    }
}
