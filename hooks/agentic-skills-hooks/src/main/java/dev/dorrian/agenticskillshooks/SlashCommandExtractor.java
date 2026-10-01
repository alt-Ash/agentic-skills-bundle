package dev.dorrian.agenticskillshooks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of hooks/lib/event-log.ts's extractSlashCommand. Only the command name is
 * extracted, never trailing args. Requires whitespace-or-end after the name so an absolute
 * path like "/Users/..." is never mistaken for a slash command.
 */
public final class SlashCommandExtractor {
    private static final Pattern RAW = Pattern.compile("^/([a-zA-Z0-9][a-zA-Z0-9_-]*)(?=\\s|$)");
    private static final Pattern TAGGED = Pattern.compile("<command-name>\\s*([^<]+?)\\s*</command-name>");

    private SlashCommandExtractor() {
    }

    public static String extract(String prompt) {
        if (prompt == null) return null;
        Matcher raw = RAW.matcher(prompt);
        if (raw.find()) return "/" + raw.group(1);
        Matcher tagged = TAGGED.matcher(prompt);
        return tagged.find() ? tagged.group(1) : null;
    }
}
