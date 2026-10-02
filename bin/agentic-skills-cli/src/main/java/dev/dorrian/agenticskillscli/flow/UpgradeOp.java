package dev.dorrian.agenticskillscli.flow;

/** One planned upgrade step: a human-readable line plus the action that performs it. */
public record UpgradeOp(String line, Action action) {

    @FunctionalInterface
    public interface Action {
        /** Performs the refresh; throws on any failure so the runner can record it and continue. */
        void run() throws Exception;
    }
}
