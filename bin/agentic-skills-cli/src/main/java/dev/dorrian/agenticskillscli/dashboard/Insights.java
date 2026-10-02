package dev.dorrian.agenticskillscli.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A few fixed, explainable rules over the aggregates — no model, no guesswork. Each insight says
 * what was measured, so a reader can check it against the charts.
 */
final class Insights {

    static final int MIN_TOOL_ATTEMPTS = 20;
    static final double HIGH_FAILURE_RATE = 0.20;
    static final long LARGE_CONTEXT_TOKENS = 150_000;

    private Insights() {
    }

    /** Minimum sessions in the window before "never used" means anything. */
    static final long MIN_SESSIONS_FOR_UNUSED = 20;

    static List<Map<String, Object>> compute(DashboardQueries q, Filters f) {
        return compute(q, f, null);
    }

    static List<Map<String, Object>> compute(DashboardQueries q, Filters f, InstalledContent installed) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> t : q.tools(f)) {
            long attempts = num(t.get("calls")) + num(t.get("failures"));
            double rate = ((Number) t.get("failure_rate")).doubleValue();
            if (attempts >= MIN_TOOL_ATTEMPTS && rate > HIGH_FAILURE_RATE && !"(not recorded)".equals(t.get("tool"))) {
                out.add(insight("warn", t.get("tool") + " fails " + pct(rate) + " of the time",
                    num(t.get("failures")) + " of " + attempts + " calls failed. Check the Tools tab for the top errors:"
                        + " repeated failures usually mean unclear instructions or a missing permission."));
            }
        }

        long largeSessions = q.sessions(f).stream()
            .filter(s -> s.get("peak_context") != null && num(s.get("peak_context")) > LARGE_CONTEXT_TOKENS).count();
        if (largeSessions > 0) {
            out.add(insight("warn", largeSessions + " session(s) reached a context over " + LARGE_CONTEXT_TOKENS / 1000 + "k tokens",
                "Large contexts cost more per call and degrade answers. Compact earlier, split the task, or trim"
                    + " always-loaded instructions."));
        }

        for (Map<String, Object> g : q.guardByRule(f)) {
            out.add(insight("info", "Guard blocked \"" + g.get("rule") + "\" " + g.get("blocks") + "x via " + g.get("tool"),
                "Check Guard > Recent blocks. If these were legitimate, loosen the rule; if not, the guard is earning its keep."));
        }

        long notRecorded = q.tools(f).stream().filter(t -> "(not recorded)".equals(t.get("tool")))
            .mapToLong(t -> num(t.get("calls")) + num(t.get("failures"))).sum();
        if (notRecorded > 0) {
            out.add(insight("info", notRecorded + " tool call(s) have no tool name",
                "These predate per-tool recording, so they are grouped together on the Tools tab."));
        }

        if (installed != null && num(q.summary(f).get("sessions")) >= MIN_SESSIONS_FOR_UNUSED) {
            @SuppressWarnings("unchecked")
            Map<String, Object> never = (Map<String, Object>) q.usage(f, installed).get("neverUsed");
            long total = 0;
            List<String> sample = new ArrayList<>();
            for (Map.Entry<String, Object> e : never.entrySet()) {
                for (Object name : (List<?>) e.getValue()) {
                    total++;
                    if (sample.size() < 8) sample.add(e.getKey().replaceAll("s$", "") + " " + name);
                }
            }
            if (total > 0) {
                out.add(insight("info", total + " installed skill/agent/command name(s) never used in this window",
                    "Candidates to cut (or to make easier to trigger), e.g. " + String.join(", ", sample)
                        + (total > sample.size() ? ", ..." : "") + ". See Skills & agents."
                        + " Usage is only recorded for hooks installed with skill/agent capture, so older data undercounts."));
            }
        }

        Map<String, Object> health = q.health();
        if (num(health.get("droppedEvents")) > 0) {
            out.add(insight("warn", num(health.get("droppedEvents")) + " event(s) were dropped",
                "A hook could not write to the database (locked, full or corrupt), so counts here are an undercount."));
        }
        if (num(health.get("sessionEndsWithoutStart")) > 0) {
            out.add(insight("info", num(health.get("sessionEndsWithoutStart")) + " session(s) ended without a recorded start",
                "Hooks were installed mid-session, or a start event was lost."));
        }
        return out;
    }

    private static Map<String, Object> insight(String severity, String title, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("title", title);
        m.put("detail", detail);
        return m;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String pct(double rate) {
        return Math.round(rate * 100) + "%";
    }
}
