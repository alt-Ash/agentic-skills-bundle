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
 * Resolves configured Azure DevOps accounts. Port of {@code getAllAzureAccounts}/
 * {@code getAzureAccount} (providers/azure.ts). Re-reads env vars fresh on every call — no
 * caching, matching the source exactly (so a rotated credential takes effect without a
 * restart).
 *
 * <p>Precedence, first hit wins (no merging): {@code AZURE_DEVOPS_ACCOUNTS_B64} (base64 JSON)
 * → {@code AZURE_DEVOPS_ACCOUNTS} (plain JSON) → legacy {@code AZURE_DEVOPS_ORG_URL} +
 * {@code AZURE_DEVOPS_TOKEN} (single account named {@code "default"}) → none.
 *
 * <p>{@code envLookup} defaults to {@link System#getenv(String)} but is injectable so tests can
 * exercise every precedence tier without mutating the real process environment (which the JVM
 * has no supported, JDK-version-stable way to do).
 */
@Component
public class AzureAccountsResolver {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Function<String, String> envLookup;

    @Autowired
    public AzureAccountsResolver() {
        this(System::getenv);
    }

    public AzureAccountsResolver(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    public List<AzureAccount> getAllAccounts() {
        String b64 = envLookup.apply("AZURE_DEVOPS_ACCOUNTS_B64");
        if (b64 != null && !b64.isEmpty()) {
            try {
                String json = new String(Base64.getDecoder().decode(b64));
                return parseAccountsJson(json);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "[issue-tickets/azure] AZURE_DEVOPS_ACCOUNTS_B64 is not valid base64-encoded JSON.", e);
            }
        }

        String plain = envLookup.apply("AZURE_DEVOPS_ACCOUNTS");
        if (plain != null && !plain.isEmpty()) {
            try {
                return parseAccountsJson(plain);
            } catch (Exception e) {
                throw new IllegalStateException("[issue-tickets/azure] AZURE_DEVOPS_ACCOUNTS is not valid JSON.", e);
            }
        }

        String legacyUrl = envLookup.apply("AZURE_DEVOPS_ORG_URL");
        String legacyToken = envLookup.apply("AZURE_DEVOPS_TOKEN");
        if (legacyUrl != null && !legacyUrl.isEmpty() && legacyToken != null && !legacyToken.isEmpty()) {
            return List.of(new AzureAccount("default", legacyUrl, legacyToken));
        }

        return List.of();
    }

    private List<AzureAccount> parseAccountsJson(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<AzureAccount> accounts = new ArrayList<>();
        root.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            accounts.add(new AzureAccount(name, value.path("url").asText(), value.path("token").asText()));
        });
        return List.copyOf(accounts);
    }

    public boolean hasCredentials() {
        return !getAllAccounts().isEmpty();
    }

    /**
     * @param accountName optional; if omitted and exactly one account is configured, that one
     *                    is used silently — with &gt;1 configured, an explicit name is required
     *                    (no "default account" concept for multi-account setups).
     */
    public AzureAccount getAccount(String accountName) {
        List<AzureAccount> accounts = getAllAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("[issue-tickets/azure] No Azure DevOps credentials configured. "
                + "Set AZURE_DEVOPS_ACCOUNTS_B64, AZURE_DEVOPS_ACCOUNTS, or AZURE_DEVOPS_ORG_URL/AZURE_DEVOPS_TOKEN.");
        }

        if (accountName == null || accountName.isEmpty()) {
            if (accounts.size() == 1) {
                return accounts.get(0);
            }
            String names = String.join(", ", accounts.stream().map(AzureAccount::name).toList());
            throw new IllegalStateException(
                "[issue-tickets/azure] Multiple Azure accounts configured (" + names + "). Specify an account name.");
        }

        return accounts.stream()
            .filter(a -> a.name().equals(accountName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("[issue-tickets/azure] Unknown Azure account '" + accountName
                + "'. Configured accounts: " + String.join(", ", accounts.stream().map(AzureAccount::name).toList())));
    }
}
