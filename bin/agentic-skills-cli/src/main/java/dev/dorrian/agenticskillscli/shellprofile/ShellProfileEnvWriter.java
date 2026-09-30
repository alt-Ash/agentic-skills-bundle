package dev.dorrian.agenticskillscli.shellprofile;

import dev.dorrian.agenticskillscli.HomeDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of {@code bin/install.js}'s shell-profile credential helpers:
 * {@code writeObTicketsEnvVars}, {@code resolveShellProfileFile}, {@code
 * decodeAccountsB64}, {@code readObTicketsAccounts}, {@code
 * overwriteObTicketsEnvVars}, {@code writeFigmaEnvVar}, and the base64
 * encode helpers. Ported field-for-field from the source read directly on
 * 2026-09-30.
 */
public final class ShellProfileEnvWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern AZURE_EXPORT_LINE = Pattern.compile("export AZURE_DEVOPS_ACCOUNTS_B64=['\"]([^'\"]*)['\"]");
    private static final Pattern GITHUB_EXPORT_LINE = Pattern.compile("export GITHUB_ACCOUNTS_B64=['\"]([^'\"]*)['\"]");

    private ShellProfileEnvWriter() {
    }

    public static Path resolveShellProfileFile() {
        String shell = System.getenv().getOrDefault("SHELL", "");
        Path home = HomeDir.resolve();
        return shell.contains("zsh") ? home.resolve(".zshrc") : home.resolve(".bashrc");
    }

    public static Optional<String> encodeAzureAccountsB64(List<AzureOrg> azureOrgs) {
        if (azureOrgs.isEmpty()) return Optional.empty();
        Map<String, Object> json = new LinkedHashMap<>();
        for (AzureOrg org : azureOrgs) {
            json.put(org.name(), Map.of("url", org.url(), "token", org.token()));
        }
        return Optional.of(encodeBase64Json(json));
    }

    public static Optional<String> encodeGithubAccountsB64(List<GithubAccount> githubAccounts) {
        if (githubAccounts.isEmpty()) return Optional.empty();
        Map<String, Object> json = new LinkedHashMap<>();
        for (GithubAccount account : githubAccounts) {
            json.put(account.name(), account.token());
        }
        return Optional.of(encodeBase64Json(json));
    }

    private static String encodeBase64Json(Map<String, Object> json) {
        try {
            String text = MAPPER.writeValueAsString(json);
            return Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Map<String, Object>> decodeAccountsB64(String base64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Result of {@link #writeObTicketsEnvVars}/{@link #writeFigmaEnvVar}. */
    public record WriteResult(Path profileFile, boolean skipped) {
    }

    /**
     * Writes Azure DevOps and GitHub credentials to the user's shell profile.
     * If the env var already exists in the profile it is NOT overwritten
     * (manual edit required).
     */
    public static WriteResult writeObTicketsEnvVars(List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts) {
        return writeObTicketsEnvVars(resolveShellProfileFile(), azureOrgs, githubAccounts);
    }

    /** Overload taking an explicit profile file — used by tests to avoid touching the real shell profile. */
    public static WriteResult writeObTicketsEnvVars(Path profileFile, List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts) {
        String existing = readIfExists(profileFile);

        List<String> lines = new ArrayList<>();

        Optional<String> azureB64 = encodeAzureAccountsB64(azureOrgs);
        if (azureB64.isPresent() && !existing.contains("AZURE_DEVOPS_ACCOUNTS_B64")) {
            String marker = "# Azure DevOps credentials (issue-tickets MCP)";
            if (!existing.contains(marker)) {
                lines.add("");
                lines.add(marker);
            }
            lines.add("export AZURE_DEVOPS_ACCOUNTS_B64='" + azureB64.get() + "'");
        }

        Optional<String> githubB64 = encodeGithubAccountsB64(githubAccounts);
        if (githubB64.isPresent() && !existing.contains("GITHUB_ACCOUNTS_B64")) {
            String marker = "# GitHub credentials (issue-tickets MCP)";
            if (!existing.contains(marker)) {
                lines.add("");
                lines.add(marker);
            }
            lines.add("export GITHUB_ACCOUNTS_B64='" + githubB64.get() + "'");
        }

        if (!lines.isEmpty()) {
            append(profileFile, String.join("\n", lines) + "\n");
        }
        return new WriteResult(profileFile, lines.isEmpty());
    }

    /** The issue-tickets Azure DevOps / GitHub accounts currently stored in the user's shell profile. */
    public record ObTicketsAccounts(List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts) {
    }

    public static ObTicketsAccounts readObTicketsAccounts() {
        return readObTicketsAccounts(resolveShellProfileFile());
    }

    /** Overload taking an explicit profile file — used by tests to avoid touching the real shell profile. */
    public static ObTicketsAccounts readObTicketsAccounts(Path profileFile) {
        String existing = readIfExists(profileFile);

        List<AzureOrg> azureOrgs = new ArrayList<>();
        Matcher azureMatcher = AZURE_EXPORT_LINE.matcher(existing);
        if (azureMatcher.find()) {
            decodeAccountsB64(azureMatcher.group(1)).ifPresent(decoded -> {
                for (Map.Entry<String, Object> entry : decoded.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> value = (Map<String, Object>) entry.getValue();
                    azureOrgs.add(new AzureOrg(entry.getKey(), (String) value.get("url"), (String) value.get("token")));
                }
            });
        }

        List<GithubAccount> githubAccounts = new ArrayList<>();
        Matcher githubMatcher = GITHUB_EXPORT_LINE.matcher(existing);
        if (githubMatcher.find()) {
            decodeAccountsB64(githubMatcher.group(1)).ifPresent(decoded -> {
                for (Map.Entry<String, Object> entry : decoded.entrySet()) {
                    githubAccounts.add(new GithubAccount(entry.getKey(), String.valueOf(entry.getValue())));
                }
            });
        }

        return new ObTicketsAccounts(azureOrgs, githubAccounts);
    }

    /**
     * Replaces (rather than skips) the Azure DevOps / GitHub credential blobs
     * in the user's shell profile — used to rotate an expired PAT.
     */
    public static Path overwriteObTicketsEnvVars(List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts) {
        return overwriteObTicketsEnvVars(resolveShellProfileFile(), azureOrgs, githubAccounts);
    }

    /** Overload taking an explicit profile file — used by tests to avoid touching the real shell profile. */
    public static Path overwriteObTicketsEnvVars(Path profileFile, List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts) {
        String existing = readIfExists(profileFile);

        Optional<String> azureB64 = encodeAzureAccountsB64(azureOrgs);
        if (azureB64.isPresent()) {
            String line = "export AZURE_DEVOPS_ACCOUNTS_B64='" + azureB64.get() + "'";
            if (AZURE_EXPORT_LINE.matcher(existing).find()) {
                existing = AZURE_EXPORT_LINE.matcher(existing).replaceFirst(Matcher.quoteReplacement(line));
            } else {
                String marker = "# Azure DevOps credentials (issue-tickets MCP)";
                existing += (existing.contains(marker) ? "" : "\n" + marker) + "\n" + line + "\n";
            }
        }

        Optional<String> githubB64 = encodeGithubAccountsB64(githubAccounts);
        if (githubB64.isPresent()) {
            String line = "export GITHUB_ACCOUNTS_B64='" + githubB64.get() + "'";
            if (GITHUB_EXPORT_LINE.matcher(existing).find()) {
                existing = GITHUB_EXPORT_LINE.matcher(existing).replaceFirst(Matcher.quoteReplacement(line));
            } else {
                String marker = "# GitHub credentials (issue-tickets MCP)";
                existing += (existing.contains(marker) ? "" : "\n" + marker) + "\n" + line + "\n";
            }
        }

        writeFull(profileFile, existing);
        return profileFile;
    }

    /**
     * Appends the Figma access token export to the user's shell profile. Skips if already present.
     *
     * @deprecated Figma MCP is now configured against Figma's OAuth-only
     *     hosted server (or the desktop app's local server), neither of which
     *     reads {@code FIGMA_ACCESS_TOKEN}. Remove once the install flows stop
     *     prompting for a token.
     */
    @Deprecated
    public static WriteResult writeFigmaEnvVar(String accessToken) {
        return writeFigmaEnvVar(resolveShellProfileFile(), accessToken);
    }

    /**
     * Overload taking an explicit profile file — used by tests to avoid touching the real shell profile.
     *
     * @deprecated see {@link #writeFigmaEnvVar(String)}.
     */
    @Deprecated
    public static WriteResult writeFigmaEnvVar(Path profileFile, String accessToken) {
        String existing = readIfExists(profileFile);

        List<String> lines = new ArrayList<>();
        String marker = "# Figma MCP credentials";
        if (!existing.contains(marker)) {
            lines.add("");
            lines.add(marker);
        }
        if (!existing.contains("FIGMA_ACCESS_TOKEN")) {
            lines.add("export FIGMA_ACCESS_TOKEN=\"" + accessToken + "\"");
        }

        if (!lines.isEmpty()) {
            append(profileFile, String.join("\n", lines) + "\n");
        }
        return new WriteResult(profileFile, lines.isEmpty());
    }

    private static String readIfExists(Path file) {
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void append(Path file, String content) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeFull(Path file, String content) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
