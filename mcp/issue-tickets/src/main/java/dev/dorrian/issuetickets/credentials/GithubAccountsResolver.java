package dev.dorrian.issuetickets.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves configured GitHub accounts. Port of {@code getAllGithubAccounts}/{@code
 * getGithubAccount} (providers/github.ts). Same precedence/no-caching/no-default-when-multiple
 * rules as {@link AzureAccountsResolver} — see its javadoc, including the injectable
 * {@code envLookup} testability seam.
 */
@Component
public class GithubAccountsResolver {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Function<String, String> envLookup;

    @Autowired
    public GithubAccountsResolver() {
        this(System::getenv);
    }

    public GithubAccountsResolver(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    public List<GithubAccount> getAllAccounts() {
        String b64 = envLookup.apply("GITHUB_ACCOUNTS_B64");
        if (b64 != null && !b64.isEmpty()) {
            try {
                String json = new String(Base64.getDecoder().decode(b64));
                return parseAccountsJson(json);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "[issue-tickets/github] GITHUB_ACCOUNTS_B64 is not valid base64-encoded JSON.", e);
            }
        }

        String plain = envLookup.apply("GITHUB_ACCOUNTS");
        if (plain != null && !plain.isEmpty()) {
            try {
                return parseAccountsJson(plain);
            } catch (Exception e) {
                throw new IllegalStateException("[issue-tickets/github] GITHUB_ACCOUNTS is not valid JSON.", e);
            }
        }

        String legacyToken = envLookup.apply("GITHUB_TOKEN");
        if (legacyToken != null && !legacyToken.isEmpty()) {
            return List.of(new GithubAccount("default", legacyToken));
        }

        return List.of();
    }

    private List<GithubAccount> parseAccountsJson(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<GithubAccount> accounts = new ArrayList<>();
        root.fields().forEachRemaining(entry -> accounts.add(new GithubAccount(entry.getKey(), entry.getValue().asText())));
        return List.copyOf(accounts);
    }

    public boolean hasCredentials() {
        return !getAllAccounts().isEmpty();
    }

    public GithubAccount getAccount(String accountName) {
        List<GithubAccount> accounts = getAllAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalStateException(
                "[issue-tickets/github] No GitHub credentials configured. "
                    + "Set GITHUB_ACCOUNTS_B64, GITHUB_ACCOUNTS, or GITHUB_TOKEN.");
        }

        if (accountName == null || accountName.isEmpty()) {
            if (accounts.size() == 1) {
                return accounts.get(0);
            }
            String names = String.join(", ", accounts.stream().map(GithubAccount::name).toList());
            throw new IllegalStateException(
                "[issue-tickets/github] Multiple GitHub accounts configured (" + names + "). Specify an account name.");
        }

        return accounts.stream()
            .filter(a -> a.name().equals(accountName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("[issue-tickets/github] Unknown GitHub account '" + accountName
                + "'. Configured accounts: " + String.join(", ", accounts.stream().map(GithubAccount::name).toList())));
    }
}
