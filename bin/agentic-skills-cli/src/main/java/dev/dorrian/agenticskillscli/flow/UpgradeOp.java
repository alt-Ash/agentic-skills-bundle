package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Outcome;

/**
 * One planned upgrade step. {@code outcome} is what the step will report if it succeeds (REFRESHED
 * with an action, or UNCHANGED / SKIPPED_MODIFIED with no action).
 */
public record UpgradeOp(String tool, String kind, String name, Outcome outcome, Action action) {

    @FunctionalInterface
    public interface Action {
        /** Performs the refresh; throws on any failure so the runner can record it and continue. */
        void run() throws Exception;
    }
}
