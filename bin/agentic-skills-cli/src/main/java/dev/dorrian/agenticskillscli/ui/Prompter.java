package dev.dorrian.agenticskillscli.ui;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interactive prompt facade replacing {@code inquirer} — {@code
 * bin/install.js} was grepped for every {@code inquirer.prompt} call site to
 * confirm exactly 5 prompt types are used ({@code list}, {@code checkbox},
 * {@code input}, {@code password}, {@code confirm}), all implemented here.
 *
 * <p>Built directly on {@code org.jline:jline} rather than a higher-level
 * wrapper: {@code org.beryx:text-io} (the initially-planned choice) was
 * confirmed stale via a live Maven Central check (last released April 2020,
 * no Context7 documentation coverage either) before any code was written
 * against it, so this facade is hand-rolled on JLine's actively-maintained
 * {@link LineReader}/{@link Terminal} primitives instead.
 *
 * <p><b>Interaction model for {@link #checkbox}</b> (there is no inquirer
 * equivalent to imitate 1:1 in a plain-line-based terminal reader — inquirer
 * itself uses raw-mode arrow-key navigation, which JLine could support but
 * which is out of proportion to what this CLI wizard needs): choices are
 * printed as a numbered list with a {@code [x]}/{@code [ ]} marker reflecting
 * the current selection (pre-checked entries start marked). The user types
 * space-separated numbers to toggle entries and presses Enter on a blank line
 * to confirm the current selection — this lets a user accept all
 * pre-checked defaults by just pressing Enter once, matching the most common
 * inquirer checkbox usage in the original (accepting detected defaults).
 *
 * <p>Must be constructed with an inherited (real) stdin/stdout — i.e. the
 * eventual Node launcher shim must invoke this JVM with {@code
 * stdio:'inherit'}, not piped streams, for JLine to correctly detect an
 * interactive TTY and support line editing.
 */
public final class Prompter implements AutoCloseable {

    private final Terminal terminal;
    private final LineReader reader;

    public Prompter() {
        try {
            this.terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    public String list(String question, List<String> choices) {
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("list() requires at least one choice");
        }
        PrintWriter out = terminal.writer();
        out.println(Ansi.bold(question));
        for (int i = 0; i < choices.size(); i++) {
            out.println("  " + (i + 1) + ") " + choices.get(i));
        }
        out.flush();

        while (true) {
            String line = reader.readLine(Ansi.cyan("? ")).trim();
            int index = parseOneBasedIndex(line, choices.size());
            if (index >= 0) {
                return choices.get(index);
            }
            out.println(Ansi.red("Please enter a number between 1 and " + choices.size() + "."));
        }
    }

    public List<String> checkbox(String question, List<String> choices, Set<String> preChecked) {
        if (choices.isEmpty()) {
            return List.of();
        }
        Set<Integer> checked = new LinkedHashSet<>();
        for (int i = 0; i < choices.size(); i++) {
            if (preChecked.contains(choices.get(i))) {
                checked.add(i);
            }
        }

        PrintWriter out = terminal.writer();
        out.println(Ansi.bold(question));
        out.println(Ansi.dim("  Type space-separated numbers to toggle, then press Enter to confirm."));

        while (true) {
            for (int i = 0; i < choices.size(); i++) {
                String marker = checked.contains(i) ? "[x]" : "[ ]";
                out.println("  " + marker + " " + (i + 1) + ") " + choices.get(i));
            }
            out.flush();

            String line = reader.readLine(Ansi.cyan("? ")).trim();
            if (line.isEmpty()) {
                return checked.stream().sorted().map(choices::get).collect(Collectors.toList());
            }

            List<Integer> toggles = new ArrayList<>();
            boolean valid = true;
            for (String token : line.split("\\s+")) {
                int idx = parseOneBasedIndex(token, choices.size());
                if (idx < 0) {
                    valid = false;
                    break;
                }
                toggles.add(idx);
            }
            if (!valid) {
                out.println(Ansi.red("Please enter numbers between 1 and " + choices.size() + ", space-separated."));
                continue;
            }
            for (int idx : toggles) {
                if (!checked.remove(idx)) {
                    checked.add(idx);
                }
            }
        }
    }

    public String input(String question, String defaultValue) {
        String suffix = (defaultValue != null && !defaultValue.isEmpty()) ? " (" + defaultValue + ")" : "";
        String line = reader.readLine(Ansi.cyan("? ") + Ansi.bold(question) + suffix + " ").trim();
        return line.isEmpty() ? defaultValue : line;
    }

    public String password(String question) {
        return reader.readLine(Ansi.cyan("? ") + Ansi.bold(question) + " ", '*');
    }

    public boolean confirm(String question, boolean defaultValue) {
        String hint = defaultValue ? "(Y/n)" : "(y/N)";
        while (true) {
            String line = reader.readLine(Ansi.cyan("? ") + Ansi.bold(question) + " " + hint + " ").trim().toLowerCase();
            if (line.isEmpty()) {
                return defaultValue;
            }
            if (line.equals("y") || line.equals("yes")) {
                return true;
            }
            if (line.equals("n") || line.equals("no")) {
                return false;
            }
            terminal.writer().println(Ansi.red("Please answer y or n."));
        }
    }

    /** Parses a 1-based index string, returning the 0-based index or -1 if out of range/unparsable. */
    static int parseOneBasedIndex(String token, int choiceCount) {
        try {
            int oneBased = Integer.parseInt(token);
            if (oneBased >= 1 && oneBased <= choiceCount) {
                return oneBased - 1;
            }
        } catch (NumberFormatException ignored) {
            // falls through to -1
        }
        return -1;
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
