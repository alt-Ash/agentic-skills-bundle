package dev.dorrian.issuetickets.model;

/** Normalized comment/discussion entry, shared by both providers' detail-fetch path. */
public record TicketComment(String author, String text, String createdAt) {
}
