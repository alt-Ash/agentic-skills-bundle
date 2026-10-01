package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.JsonConfigStore;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;
import dev.dorrian.agenticskillscli.shellprofile.AzureOrg;
import dev.dorrian.agenticskillscli.shellprofile.GithubAccount;
import dev.dorrian.agenticskillscli.shellprofile.ShellProfileEnvWriter;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code bin/install.js}'s {@code runTokenUpdate()} (source read
 * directly, lines 1402-1481, on 2026-09-30): rotates a single expired
 * issue-tickets PAT (Azure DevOps or GitHub) without rerunning the full
 * installer — rewrites the shell profile and re-registers the issue-tickets
 * MCP entry for every tool where it's already configured. Everything else
 * (skills, agents, hooks) is untouched, matching the original's own comment.
 */
public final class TokenUpdateFlow {

    private TokenUpdateFlow() {
    }

    /** One selectable credential: an Azure org or a GitHub account, plus its index in the source list. */
    private record TokenItem(String kind, int index, String label) {
    }

    public static void run(Prompter prompter) {
        System.out.println();
        System.out.println("  " + Ansi.boldRed("Update token"));
        System.out.println("  " + Ansi.dim("Replace an expired Azure DevOps or GitHub PAT used by the issue-tickets MCP."));
        System.out.println();

        ShellProfileEnvWriter.ObTicketsAccounts accounts = ShellProfileEnvWriter.readObTicketsAccounts();
        List<AzureOrg> azureOrgs = new ArrayList<>(accounts.azureOrgs());
        List<GithubAccount> githubAccounts = new ArrayList<>(accounts.githubAccounts());

        if (azureOrgs.isEmpty() && githubAccounts.isEmpty()) {
            System.out.println("  " + Ansi.yellow("No issue-tickets credentials found in your shell profile."));
            System.out.println("  " + Ansi.dim("Run Install / Quick install first to configure issue-tickets."));
            System.out.println();
            return;
        }

        List<TokenItem> items = new ArrayList<>();
        for (int i = 0; i < azureOrgs.size(); i++) {
            items.add(new TokenItem("azure", i, "Azure DevOps — " + azureOrgs.get(i).name()));
        }
        for (int i = 0; i < githubAccounts.size(); i++) {
            items.add(new TokenItem("github", i, "GitHub — " + githubAccounts.get(i).name()));
        }

        Map<String, TokenItem> itemsByLabel = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        for (TokenItem item : items) {
            itemsByLabel.put(item.label(), item);
            labels.add(item.label());
        }

        String chosenLabel = prompter.list("Which token has expired?", labels);
        TokenItem selected = itemsByLabel.get(chosenLabel);

        String tokenPrompt = "azure".equals(selected.kind())
            ? "New Azure Personal Access Token:"
            : "New GitHub Personal Access Token:";
        String token;
        while (true) {
            token = prompter.password(tokenPrompt).trim();
            if (!token.isEmpty()) break;
            System.out.println("  " + Ansi.red("Token cannot be blank."));
        }

        if ("azure".equals(selected.kind())) {
            AzureOrg old = azureOrgs.get(selected.index());
            azureOrgs.set(selected.index(), new AzureOrg(old.name(), old.url(), token));
        } else {
            GithubAccount old = githubAccounts.get(selected.index());
            githubAccounts.set(selected.index(), new GithubAccount(old.name(), token));
        }

        Path profileFile = ShellProfileEnvWriter.overwriteObTicketsEnvVars(azureOrgs, githubAccounts);
        System.out.println();
        System.out.println("  " + Ansi.dim("Updated credentials written to " + profileFile));

        List<String> obTicketsToolKeys = claudeLastToolKeyOrder(McpConfigRegistry.ALL.keySet());

        String azureB64 = ShellProfileEnvWriter.encodeAzureAccountsB64(azureOrgs).orElse(null);
        String githubB64 = ShellProfileEnvWriter.encodeGithubAccountsB64(githubAccounts).orElse(null);

        List<String> refreshedTools = new ArrayList<>();
        for (String toolKey : obTicketsToolKeys) {
            if (!JsonConfigStore.isMcpServerRegistered("issue-tickets", toolKey)) continue;
            Map<String, Object> serverConfig = IssueTicketsMcpInstaller.config(toolKey, azureB64, githubB64);
            JsonConfigStore.overwriteMcpServerEntry("issue-tickets", serverConfig, toolKey);
            refreshedTools.add(AgentToolRegistry.get(toolKey).name());
        }

        System.out.println();
        if (!refreshedTools.isEmpty()) {
            System.out.println("  " + Ansi.boldGreen("✔ Re-registered issue-tickets for: " + String.join(", ", refreshedTools)));
        } else {
            System.out.println("  " + Ansi.yellow("issue-tickets isn't registered with any AI tool yet — run Install to add it."));
        }
        System.out.println("  " + Ansi.dim("Open a new terminal (or restart your shell) for the updated env vars to take effect."));
        System.out.println();
    }

    /**
     * Port of {@code [...Object.keys(MCP_CONFIG).filter((k) => k !== 'claude'), 'claude']} —
     * same key set as the input, just with {@code "claude"} moved to the end (if present).
     */
    static List<String> claudeLastToolKeyOrder(java.util.Collection<String> keys) {
        List<String> ordered = new ArrayList<>();
        boolean hasClaude = false;
        for (String key : keys) {
            if ("claude".equals(key)) {
                hasClaude = true;
            } else {
                ordered.add(key);
            }
        }
        if (hasClaude) ordered.add("claude");
        return ordered;
    }
}
