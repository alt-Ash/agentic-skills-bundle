package dev.dorrian.agenticskillsevals.eval;

/** Port of {@code EvalConfig.prescannedPrefix} - prepends {@code prefix} to the system prompt when {@code trigger} appears in the scenario text. */
public record PrescannedPrefix(String trigger, String prefix) {
}
