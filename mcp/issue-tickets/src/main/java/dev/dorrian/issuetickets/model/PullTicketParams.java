package dev.dorrian.issuetickets.model;

import java.util.List;

/** Port of the TS {@code PullTicketParams} interface. */
public record PullTicketParams(
    List<String> ticketIds,
    String projectId,
    Boolean allProjects,
    Integer pageSize,
    String account
) {

    public int pageSizeOrDefault() {
        return pageSize != null ? pageSize : 25;
    }
}
