package dev.dorrian.agenticskillscli.shellprofile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellProfileEnvWriter}'s write/read/overwrite functions all target
 * the real {@code ~/.zshrc}/{@code ~/.bashrc} (matching the original, which
 * has no dependency-injection seam for the profile path either) — so these
 * tests only cover the pure encode/decode round-trip, not the file I/O
 * paths, to avoid mutating the test runner's actual shell profile.
 */
class ShellProfileEnvWriterTest {

    @Test
    void encodesAndDecodesAzureAccountsRoundTrip() {
        List<AzureOrg> orgs = List.of(new AzureOrg("acme", "https://dev.azure.com/acme", "pat-123"));
        String b64 = ShellProfileEnvWriter.encodeAzureAccountsB64(orgs).orElseThrow();

        // Decoded payload should be valid base64-encoded JSON keyed by org name.
        String json = new String(Base64.getDecoder().decode(b64));
        assertTrue(json.contains("acme"));
        assertTrue(json.contains("https://dev.azure.com/acme"));
        assertTrue(json.contains("pat-123"));
    }

    @Test
    void encodeAzureAccountsReturnsEmptyForNoOrgs() {
        assertEquals(Optional.empty(), ShellProfileEnvWriter.encodeAzureAccountsB64(List.of()));
    }

    @Test
    void encodesGithubAccountsAsNameToTokenMapping() {
        List<GithubAccount> accounts = List.of(new GithubAccount("personal", "ghp_abc123"));
        String b64 = ShellProfileEnvWriter.encodeGithubAccountsB64(accounts).orElseThrow();
        String json = new String(Base64.getDecoder().decode(b64));
        assertTrue(json.contains("personal"));
        assertTrue(json.contains("ghp_abc123"));
    }

    @Test
    void encodeGithubAccountsReturnsEmptyForNoAccounts() {
        assertEquals(Optional.empty(), ShellProfileEnvWriter.encodeGithubAccountsB64(List.of()));
    }

    @Test
    void resolveShellProfileFilePicksZshrcOrBashrcBasedOnShellEnvVar() {
        // Can't override $SHELL from within the JVM portably, but the function
        // must at least resolve to one of the two expected profile files.
        String name = ShellProfileEnvWriter.resolveShellProfileFile().getFileName().toString();
        assertTrue(name.equals(".zshrc") || name.equals(".bashrc"));
    }

    @Test
    void writeThenReadObTicketsAccountsRoundTripsAgainstAnExplicitProfileFile(@TempDir Path tempDir) throws IOException {
        Path profileFile = tempDir.resolve("fake-rc");
        List<AzureOrg> orgs = List.of(new AzureOrg("acme", "https://dev.azure.com/acme", "pat-123"));
        List<GithubAccount> accounts = List.of(new GithubAccount("personal", "ghp_abc"));

        ShellProfileEnvWriter.WriteResult writeResult = ShellProfileEnvWriter.writeObTicketsEnvVars(profileFile, orgs, accounts);
        assertFalse(writeResult.skipped());
        assertTrue(Files.exists(profileFile));

        ShellProfileEnvWriter.ObTicketsAccounts read = ShellProfileEnvWriter.readObTicketsAccounts(profileFile);
        assertEquals(1, read.azureOrgs().size());
        assertEquals("acme", read.azureOrgs().get(0).name());
        assertEquals("https://dev.azure.com/acme", read.azureOrgs().get(0).url());
        assertEquals("pat-123", read.azureOrgs().get(0).token());
        assertEquals(1, read.githubAccounts().size());
        assertEquals("personal", read.githubAccounts().get(0).name());
        assertEquals("ghp_abc", read.githubAccounts().get(0).token());
    }

    @Test
    void writeObTicketsEnvVarsSkipsWhenAlreadyPresent(@TempDir Path tempDir) throws IOException {
        Path profileFile = tempDir.resolve("fake-rc");
        List<AzureOrg> orgs = List.of(new AzureOrg("acme", "https://dev.azure.com/acme", "pat-123"));

        ShellProfileEnvWriter.writeObTicketsEnvVars(profileFile, orgs, List.of());
        ShellProfileEnvWriter.WriteResult second = ShellProfileEnvWriter.writeObTicketsEnvVars(profileFile, orgs, List.of());

        assertTrue(second.skipped()); // already present — not overwritten
    }

    @Test
    void overwriteObTicketsEnvVarsReplacesExistingExportLineInPlace(@TempDir Path tempDir) throws IOException {
        Path profileFile = tempDir.resolve("fake-rc");
        ShellProfileEnvWriter.writeObTicketsEnvVars(profileFile, List.of(new AzureOrg("acme", "url1", "old-token")), List.of());

        ShellProfileEnvWriter.overwriteObTicketsEnvVars(profileFile, List.of(new AzureOrg("acme", "url1", "new-token")), List.of());

        ShellProfileEnvWriter.ObTicketsAccounts read = ShellProfileEnvWriter.readObTicketsAccounts(profileFile);
        assertEquals(1, read.azureOrgs().size());
        assertEquals("new-token", read.azureOrgs().get(0).token());
        // Only one export line should remain, not two.
        long exportLineCount = Files.readString(profileFile).lines()
            .filter(l -> l.contains("AZURE_DEVOPS_ACCOUNTS_B64")).count();
        assertEquals(1, exportLineCount);
    }

    @Test
    void readObTicketsAccountsReturnsEmptyListsWhenProfileFileDoesNotExist(@TempDir Path tempDir) {
        ShellProfileEnvWriter.ObTicketsAccounts read = ShellProfileEnvWriter.readObTicketsAccounts(tempDir.resolve("nope"));
        assertTrue(read.azureOrgs().isEmpty());
        assertTrue(read.githubAccounts().isEmpty());
    }
}
