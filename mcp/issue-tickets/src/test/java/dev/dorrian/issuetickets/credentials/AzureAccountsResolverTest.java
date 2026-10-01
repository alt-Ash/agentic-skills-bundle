package dev.dorrian.issuetickets.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AzureAccountsResolverTest {

    private static AzureAccountsResolver withEnv(Map<String, String> env) {
        return new AzureAccountsResolver(env::get);
    }

    @Test
    void returnsEmptyListWhenNoEnvVarsAreSet() {
        var resolver = withEnv(Map.of());

        assertThat(resolver.getAllAccounts()).isEmpty();
        assertThat(resolver.hasCredentials()).isFalse();
    }

    @Test
    void decodesAccountsB64AsHighestPrecedence() {
        String json = "{\"work\":{\"url\":\"https://dev.azure.com/work\",\"token\":\"tok1\"}}";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes());

        var resolver = withEnv(Map.of(
            "AZURE_DEVOPS_ACCOUNTS_B64", b64,
            "AZURE_DEVOPS_ORG_URL", "https://dev.azure.com/should-be-ignored",
            "AZURE_DEVOPS_TOKEN", "should-be-ignored"
        ));

        var accounts = resolver.getAllAccounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).name()).isEqualTo("work");
        assertThat(accounts.get(0).orgUrl()).isEqualTo("https://dev.azure.com/work");
        assertThat(accounts.get(0).token()).isEqualTo("tok1");
    }

    @Test
    void throwsWithClearMessageWhenAccountsB64IsNotValidBase64Json() {
        var resolver = withEnv(Map.of("AZURE_DEVOPS_ACCOUNTS_B64", "not-valid-base64!!!"));

        assertThatThrownBy(resolver::getAllAccounts)
            .hasMessageContaining("AZURE_DEVOPS_ACCOUNTS_B64 is not valid base64-encoded JSON");
    }

    @Test
    void fallsBackToPlainAccountsJsonWhenB64IsAbsent() {
        var resolver = withEnv(Map.of(
            "AZURE_DEVOPS_ACCOUNTS", "{\"team-a\":{\"url\":\"https://dev.azure.com/a\",\"token\":\"t\"}}"
        ));

        var accounts = resolver.getAllAccounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).name()).isEqualTo("team-a");
    }

    @Test
    void fallsBackToLegacySingleOrgNamedDefault() {
        var resolver = withEnv(Map.of(
            "AZURE_DEVOPS_ORG_URL", "https://dev.azure.com/legacy",
            "AZURE_DEVOPS_TOKEN", "legacy-token"
        ));

        var accounts = resolver.getAllAccounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).name()).isEqualTo("default");
    }

    @Test
    void getAccountUsesTheSingleConfiguredAccountWhenNameOmitted() {
        var resolver = withEnv(Map.of(
            "AZURE_DEVOPS_ORG_URL", "https://dev.azure.com/only",
            "AZURE_DEVOPS_TOKEN", "t"
        ));

        assertThat(resolver.getAccount(null).name()).isEqualTo("default");
    }

    @Test
    void getAccountThrowsWhenMultipleConfiguredAndNoneSpecified() {
        String json = "{\"a\":{\"url\":\"https://dev.azure.com/a\",\"token\":\"ta\"},"
            + "\"b\":{\"url\":\"https://dev.azure.com/b\",\"token\":\"tb\"}}";
        var resolver = withEnv(Map.of("AZURE_DEVOPS_ACCOUNTS", json));

        assertThatThrownBy(() -> resolver.getAccount(null))
            .hasMessageContaining("Multiple Azure accounts configured");
    }

    @Test
    void getAccountResolvesByNameWhenMultipleConfigured() {
        String json = "{\"a\":{\"url\":\"https://dev.azure.com/a\",\"token\":\"ta\"},"
            + "\"b\":{\"url\":\"https://dev.azure.com/b\",\"token\":\"tb\"}}";
        var resolver = withEnv(Map.of("AZURE_DEVOPS_ACCOUNTS", json));

        assertThat(resolver.getAccount("b").orgUrl()).isEqualTo("https://dev.azure.com/b");
    }

    @Test
    void getAccountThrowsWhenNoCredentialsConfiguredAtAll() {
        var resolver = withEnv(Map.of());

        assertThatThrownBy(() -> resolver.getAccount(null))
            .hasMessageContaining("No Azure DevOps credentials configured");
    }

    @Test
    void getAccountThrowsListingNamesWhenRequestedNameIsUnknown() {
        var resolver = withEnv(Map.of("AZURE_DEVOPS_ORG_URL", "https://dev.azure.com/only", "AZURE_DEVOPS_TOKEN", "t"));

        assertThatThrownBy(() -> resolver.getAccount("nonexistent"))
            .hasMessageContaining("Unknown Azure account 'nonexistent'")
            .hasMessageContaining("default");
    }
}
