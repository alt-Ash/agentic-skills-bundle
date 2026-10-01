package dev.dorrian.issuetickets.credentials;

/** One configured Azure DevOps account/organization. */
public record AzureAccount(String name, String orgUrl, String token) {
}
