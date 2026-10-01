package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialPromptsTest {

    @Test
    void defaultAzureOrgNameStripsHttpsPrefix() {
        assertEquals("myorg", CredentialPrompts.defaultAzureOrgName("https://dev.azure.com/myorg"));
    }

    @Test
    void defaultAzureOrgNameStripsHttpPrefix() {
        assertEquals("myorg", CredentialPrompts.defaultAzureOrgName("http://dev.azure.com/myorg"));
    }

    @Test
    void defaultAzureOrgNameTakesFirstPathSegment() {
        assertEquals("myorg", CredentialPrompts.defaultAzureOrgName("https://dev.azure.com/myorg/some/nested/path"));
    }

    @Test
    void defaultAzureOrgNameFallsBackToOrgWhenEmpty() {
        assertEquals("org", CredentialPrompts.defaultAzureOrgName("https://dev.azure.com/"));
    }

    @Test
    void defaultAzureOrgNameOnNonMatchingUrlTakesFirstSlashSegment() {
        // Matches the original regex's behavior exactly: a URL that doesn't start with
        // https?://dev.azure.com/ is left unstripped, then split on '/' as-is.
        assertEquals("https:", CredentialPrompts.defaultAzureOrgName("https://example.com/myorg"));
    }

    @Test
    void defaultGithubAccountNameIsDefaultForFirst() {
        assertEquals("default", CredentialPrompts.defaultGithubAccountName(0));
    }

    @Test
    void defaultGithubAccountNameIsAccountNForSubsequent() {
        assertEquals("account2", CredentialPrompts.defaultGithubAccountName(1));
        assertEquals("account3", CredentialPrompts.defaultGithubAccountName(2));
    }
}
