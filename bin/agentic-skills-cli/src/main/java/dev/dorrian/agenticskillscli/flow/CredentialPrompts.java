package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.shellprofile.AzureOrg;
import dev.dorrian.agenticskillscli.shellprofile.GithubAccount;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared Azure DevOps / GitHub credential-collection loops, used identically
 * by the Full-install flow's issue-tickets credential step (bin/install.js
 * lines ~2907-2984); the prompt text and looping shape are ported from the
 * original.
 */
public final class CredentialPrompts {

    private static final Pattern AZURE_URL_PREFIX = Pattern.compile("^https?://dev\\.azure\\.com/");

    private CredentialPrompts() {
    }

    /** Port of the org-URL default-name expression attached to the "Short name for this org" prompt. */
    public static String defaultAzureOrgName(String orgUrl) {
        String stripped = AZURE_URL_PREFIX.matcher(orgUrl).replaceFirst("");
        String firstSegment = stripped.split("/", -1)[0];
        return firstSegment.isEmpty() ? "org" : firstSegment;
    }

    /** Port of the GitHub account default-name expression: "default" for the first account, else "account&lt;n&gt;". */
    public static String defaultGithubAccountName(int existingCount) {
        return existingCount == 0 ? "default" : "account" + (existingCount + 1);
    }

    public static List<AzureOrg> collectAzureOrgs(Prompter prompter) {
        System.out.println();
        System.out.println("  " + Ansi.bold("Azure DevOps") + Ansi.dim(" — add one or more organisations (leave org URL blank to skip)"));
        System.out.println("  " + Ansi.dim("Token: https://dev.azure.com/<org> → User settings → Personal access tokens → New token"));
        System.out.println("  " + Ansi.dim("Required scopes: Work Items (Read), Code (Read)"));
        System.out.println();

        List<AzureOrg> orgs = new ArrayList<>();
        while (true) {
            String orgUrl = prompter.input("Azure org URL e.g. https://dev.azure.com/myorg  (blank to skip):", "").trim();
            if (orgUrl.isEmpty()) {
                break;
            }
            String name = prompter.input("Short name for this org e.g. myorg (used to select it in the MCP):", defaultAzureOrgName(orgUrl)).trim();
            String token = prompter.password("Azure Personal Access Token:");
            orgs.add(new AzureOrg(name, orgUrl, token));
            if (!prompter.confirm("Add another Azure org?", false)) {
                break;
            }
        }
        return orgs;
    }

    public static List<GithubAccount> collectGithubAccounts(Prompter prompter) {
        System.out.println();
        System.out.println("  " + Ansi.bold("GitHub") + Ansi.dim(" — add one or more accounts (leave blank to skip)"));
        System.out.println("  " + Ansi.dim("Token: https://github.com/settings/tokens → Generate new token (classic)"));
        System.out.println("  " + Ansi.dim("Required scopes: repo (full)"));
        System.out.println();

        List<GithubAccount> accounts = new ArrayList<>();
        while (true) {
            String token = prompter.password("GitHub Personal Access Token (blank to skip):").trim();
            if (token.isEmpty()) {
                break;
            }
            String name = prompter.input("Short name for this account e.g. work, personal:", defaultGithubAccountName(accounts.size())).trim();
            accounts.add(new GithubAccount(name, token));
            if (!prompter.confirm("Add another GitHub account?", false)) {
                break;
            }
        }
        return accounts;
    }
}
