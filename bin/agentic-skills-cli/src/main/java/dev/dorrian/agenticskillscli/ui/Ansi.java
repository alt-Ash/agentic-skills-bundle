package dev.dorrian.agenticskillscli.ui;

/**
 * Minimal ANSI color/style helper — a Java replacement for the handful of
 * {@code chalk} styles actually used in {@code bin/install.js} (confirmed by
 * grepping every {@code chalk.*} call site: dim, bold, cyan, red, yellow,
 * green, white, magenta, and the composed {@code bold.green}/{@code
 * bold.red}/{@code bold.white}/{@code bold.yellow}). No dependency (e.g.
 * Jansi) is needed — every terminal this CLI targets (macOS/Linux terminals,
 * Windows 10+/Windows Terminal, and the JLine terminal provider used
 * elsewhere in this module) renders these codes natively.
 */
public final class Ansi {

    private static final String RESET = "\u001B[0m";

    private Ansi() {
    }

    public static String dim(String s) {
        return wrap(s, "\u001B[2m");
    }

    public static String bold(String s) {
        return wrap(s, "\u001B[1m");
    }

    public static String cyan(String s) {
        return wrap(s, "\u001B[36m");
    }

    public static String red(String s) {
        return wrap(s, "\u001B[31m");
    }

    public static String yellow(String s) {
        return wrap(s, "\u001B[33m");
    }

    public static String green(String s) {
        return wrap(s, "\u001B[32m");
    }

    public static String white(String s) {
        return wrap(s, "\u001B[37m");
    }

    public static String magenta(String s) {
        return wrap(s, "\u001B[35m");
    }

    public static String boldGreen(String s) {
        return bold(green(s));
    }

    public static String boldRed(String s) {
        return bold(red(s));
    }

    public static String boldWhite(String s) {
        return bold(white(s));
    }

    public static String boldYellow(String s) {
        return bold(yellow(s));
    }

    private static String wrap(String s, String code) {
        return code + s + RESET;
    }
}
