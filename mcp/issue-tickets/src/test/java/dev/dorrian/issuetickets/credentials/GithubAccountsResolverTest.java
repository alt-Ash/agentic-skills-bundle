package dev.dorrian.issuetickets.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GithubAccountsResolverTest {

    private static GithubAccountsResolver withEnv(Map<String, String> env) {
        return new GithubAccountsResolver(env::get);
    }

    @Test
    void returnsEmptyListWhenNoEnvVarsAreSet() {
        var resolver = withEnv(Map.of());

        assertThat(resolver.getAllAccounts()).isEmpty();
        assertThat(resolver.hasCredentials()).isFalse();
    }

    @Test
    void decodesAccountsB64AsHighestPrecedence() {
        String json = "{\"personal\":\"ghp_abc123\"}";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes());

        var resolver = withEnv(Map.of("GITHUB_ACCOUNTS_B64", b64, "GITHUB_TOKEN", "should-be-ignored"));

        var accounts = resolver.getAllAccounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).name()).isEqualTo("personal");
        assertThat(accounts.get(0).token()).isEqualTo("ghp_abc123");
    }

    @Test
    void throwsWithClearMessageWhenAccountsB64IsNotValidBase64Json() {
        var resolver = withEnv(Map.of("GITHUB_ACCOUNTS_B64", "not-valid-base64!!!"));

        assertThatThrownBy(resolver::getAllAccounts)
            .hasMessageContaining("GITHUB_ACCOUNTS_B64 is not valid base64-encoded JSON");
    }

    @Test
    void fallsBackToLegacyTokenNamedDefault() {
        var resolver = withEnv(Map.of("GITHUB_TOKEN", "ghp_legacy"));

        var accounts = resolver.getAllAccounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).name()).isEqualTo("default");
        assertThat(accounts.get(0).token()).isEqualTo("ghp_legacy");
    }

    @Test
    void getAccountThrowsWhenMultipleConfiguredAndNoneSpecified() {
        var resolver = withEnv(Map.of("GITHUB_ACCOUNTS", "{\"a\":\"ta\",\"b\":\"tb\"}"));

        assertThatThrownBy(() -> resolver.getAccount(null))
            .hasMessageContaining("Multiple GitHub accounts configured");
    }

    @Test
    void getAccountThrowsWhenNoCredentialsConfiguredAtAll() {
        var resolver = withEnv(Map.of());

        assertThatThrownBy(() -> resolver.getAccount(null))
            .hasMessageContaining("No GitHub credentials configured");
    }
}
