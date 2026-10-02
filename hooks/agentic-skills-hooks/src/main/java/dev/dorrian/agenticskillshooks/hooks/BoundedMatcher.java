package dev.dorrian.agenticskillshooks.hooks;

import java.util.regex.Pattern;

/**
 * Regex {@code find} with a wall-clock budget and an input cap, so a pathological pattern or a huge
 * command cannot stall the guard. A timeout counts as "no match" (the guard fails open).
 */
final class BoundedMatcher {

    static final int MAX_INPUT_CHARS = 32 * 1024;
    static final long BUDGET_NANOS = 200_000_000L;

    private BoundedMatcher() {
    }

    static boolean find(Pattern pattern, String input) {
        return find(pattern, input, BUDGET_NANOS);
    }

    static boolean find(Pattern pattern, String input, long budgetNanos) {
        if (input == null) return false;
        try {
            return pattern.matcher(new Deadline(clip(input), System.nanoTime() + budgetNanos)).find();
        } catch (Timeout | StackOverflowError e) {
            return false;
        }
    }

    /** Over-long input keeps its head and tail so a dangerous suffix cannot hide behind padding. */
    static String clip(String input) {
        if (input.length() <= MAX_INPUT_CHARS) return input;
        int half = MAX_INPUT_CHARS / 2;
        return input.substring(0, half) + "\n" + input.substring(input.length() - half);
    }

    private static final class Timeout extends RuntimeException {
        Timeout() {
            super(null, null, false, false);
        }
    }

    private static final class Deadline implements CharSequence {
        private final CharSequence delegate;
        private final long deadline;
        private int calls;

        Deadline(CharSequence delegate, long deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0x3FF) == 0 && System.nanoTime() > deadline) throw new Timeout();
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
