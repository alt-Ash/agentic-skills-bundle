package dev.dorrian.issuetickets.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of ticket-text.ts — deterministic extraction of author-flagged asides (Azure-only,
 * color-styled inline HTML spans) and "to be elaborated"/TBD/TODO open items from ticket
 * bodies.
 */
public final class TicketTextExtractor {

    private static final Set<String> DEFAULT_COLORS = Set.of("#000000", "rgb(0,0,0)", "black", "inherit", "initial");

    private static final Pattern FLAGGED_ASIDE_PATTERN = Pattern.compile(
        "<[^>]+style=\"[^\"]*color\\s*:\\s*([^;\"]+)[^\"]*\"[^>]*>(.*?)</[^>]+>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final Pattern OPEN_ITEM_PATTERN = Pattern.compile(
        "\\b(?:to be elaborated|tbd|todo)\\s*:\\s*(\\[[^]]*]|[^\\n<]*)",
        Pattern.CASE_INSENSITIVE);

    private TicketTextExtractor() {
    }

    /** Azure-only: HTML rich-text descriptions may contain color-styled "aside" spans. */
    public static List<String> extractFlaggedAsides(String html) {
        if (html == null) {
            return List.of();
        }
        List<String> asides = new ArrayList<>();
        Matcher matcher = FLAGGED_ASIDE_PATTERN.matcher(html);
        while (matcher.find()) {
            String color = normalizeColor(matcher.group(1));
            if (DEFAULT_COLORS.contains(color)) {
                continue;
            }
            String content = TAG_PATTERN.matcher(matcher.group(2)).replaceAll("").trim();
            if (!content.isEmpty()) {
                asides.add(content);
            }
        }
        return List.copyOf(asides);
    }

    private static String normalizeColor(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Both providers: "to be elaborated:"/"tbd:"/"todo:" markers over tag-stripped text. */
    public static List<String> extractOpenItems(String text) {
        if (text == null) {
            return List.of();
        }
        String stripped = TAG_PATTERN.matcher(text).replaceAll("");
        List<String> items = new ArrayList<>();
        Matcher matcher = OPEN_ITEM_PATTERN.matcher(stripped);
        while (matcher.find()) {
            String captured = matcher.group(1).trim();
            if (!captured.isEmpty()) {
                items.add(captured);
            }
        }
        return List.copyOf(items);
    }
}
