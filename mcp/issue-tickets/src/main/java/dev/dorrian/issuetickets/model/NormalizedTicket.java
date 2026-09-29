package dev.dorrian.issuetickets.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Port of the TS {@code NormalizedTicket} interface (types.ts). {@code comments} is only
 * populated on a {@code ticketIds} detail fetch, never on a {@code projectId} list fetch — an
 * explicit anti-N+1 design decision preserved from the source, not an oversight.
 * {@code flaggedAsides} is Azure-only (GitHub issue bodies are Markdown, not the Azure
 * rich-text-with-inline-color convention this field is extracted from).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedTicket(
    String source,
    String id,
    String title,
    String description,
    String status,
    String url,
    String assignee,
    List<String> labels,
    String createdAt,
    String updatedAt,
    List<TicketComment> comments,
    List<String> flaggedAsides,
    List<String> openItems,
    Object raw
) {
}
