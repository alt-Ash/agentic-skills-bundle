package dev.dorrian.issuetickets.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketTextExtractorTest {

    @Test
    void extractsAColorStyledAsideThatIsNotADefaultColor() {
        String html = "<p>Normal text</p><span style=\"color: #ff0000;\">This is flagged</span>";

        var asides = TicketTextExtractor.extractFlaggedAsides(html);

        assertThat(asides).containsExactly("This is flagged");
    }

    @Test
    void skipsDefaultBlackColorVariants() {
        String html = "<span style=\"color: #000000;\">a</span>"
            + "<span style=\"color: black;\">b</span>"
            + "<span style=\"color: rgb(0,0,0);\">c</span>"
            + "<span style=\"color: inherit;\">d</span>"
            + "<span style=\"color: initial;\">e</span>";

        var asides = TicketTextExtractor.extractFlaggedAsides(html);

        assertThat(asides).isEmpty();
    }

    @Test
    void skipsDefaultColorEvenWithExtraWhitespaceAndMixedCase() {
        String html = "<span style=\"color:  RGB( 0 , 0 , 0 ) ;\">a</span>";

        var asides = TicketTextExtractor.extractFlaggedAsides(html);

        assertThat(asides).isEmpty();
    }

    @Test
    void nonGreedyMatchStopsAtTheFirstNestedClosingTagNotTheOuterOne() {
        // Preserved quirk from the source regex: (.*?) is non-greedy, so with a nested tag like
        // <b>bold</b>, the match ends at the FIRST </...> it finds (</b>), not the outer </span>
        // — "text" after </b> is never captured. This is the source's actual behavior, not a bug
        // introduced by this port.
        String html = "<span style=\"color: red;\"><b>bold</b> text</span>";

        var asides = TicketTextExtractor.extractFlaggedAsides(html);

        assertThat(asides).containsExactly("bold");
    }

    @Test
    void stripsASelfContainedInnerTagThatHasNoClosingCounterpart() {
        // <br> has no </...> counterpart, so the lazy outer match isn't cut short by it — this
        // exercises tag-stripping on the captured content without triggering the early-stop
        // quirk covered by the test above.
        String html = "<span style=\"color: red;\">bold <br>text</span>";

        var asides = TicketTextExtractor.extractFlaggedAsides(html);

        assertThat(asides).containsExactly("bold text");
    }

    @Test
    void extractOpenItemsCapturesBracketedContent() {
        String text = "Some description. TBD: [needs design review]. More text.";

        var items = TicketTextExtractor.extractOpenItems(text);

        assertThat(items).containsExactly("[needs design review]");
    }

    @Test
    void extractOpenItemsCapturesRestOfLineWhenNotBracketed() {
        String text = "todo: fix the thing before release\nNext line unrelated.";

        var items = TicketTextExtractor.extractOpenItems(text);

        assertThat(items).containsExactly("fix the thing before release");
    }

    @Test
    void extractOpenItemsIsCaseInsensitiveAndMatchesAllThreeMarkers() {
        String text = "To Be Elaborated: item one\nTBD: item two\nTODO: item three";

        var items = TicketTextExtractor.extractOpenItems(text);

        assertThat(items).containsExactly("item one", "item two", "item three");
    }

    @Test
    void extractOpenItemsStripsHtmlTagsFirst() {
        String text = "<p>todo: <b>fix</b> this</p>";

        var items = TicketTextExtractor.extractOpenItems(text);

        assertThat(items).containsExactly("fix this");
    }
}
