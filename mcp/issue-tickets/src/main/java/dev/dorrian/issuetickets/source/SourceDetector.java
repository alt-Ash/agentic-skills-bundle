package dev.dorrian.issuetickets.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.issuetickets.credentials.AzureAccountsResolver;
import dev.dorrian.issuetickets.credentials.GithubAccountsResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Port of detect-source.ts. Checked in this exact order, first signal wins:
 * <ol>
 *   <li>{@code .github/} directory exists → github</li>
 *   <li>{@code azure-pipelines.yml} file or {@code .azure/} directory exists → azure</li>
 *   <li>{@code .git/config} contents match an Azure DevOps or GitHub host pattern</li>
 *   <li>{@code package.json}'s {@code repository} field (string, or {@code .url}) matches the
 *       same patterns</li>
 *   <li>Otherwise: if exactly one of Azure/GitHub has credentials configured, use that one;
 *       both-or-neither is ambiguous (returns empty)</li>
 * </ol>
 */
@Component
public class SourceDetector {

    private static final Pattern AZURE_HOST_PATTERN = Pattern.compile("dev\\.azure\\.com|visualstudio\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_HOST_PATTERN = Pattern.compile("github\\.com", Pattern.CASE_INSENSITIVE);

    private final AzureAccountsResolver azureAccountsResolver;
    private final GithubAccountsResolver githubAccountsResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public SourceDetector(AzureAccountsResolver azureAccountsResolver, GithubAccountsResolver githubAccountsResolver) {
        this.azureAccountsResolver = azureAccountsResolver;
        this.githubAccountsResolver = githubAccountsResolver;
    }

    public Optional<Source> detect(Path projectRoot) {
        if (Files.isDirectory(projectRoot.resolve(".github"))) {
            return Optional.of(Source.github);
        }
        if (Files.exists(projectRoot.resolve("azure-pipelines.yml")) || Files.isDirectory(projectRoot.resolve(".azure"))) {
            return Optional.of(Source.azure);
        }

        Optional<Source> fromGitConfig = detectFromGitConfig(projectRoot);
        if (fromGitConfig.isPresent()) {
            return fromGitConfig;
        }

        Optional<Source> fromPackageJson = detectFromPackageJson(projectRoot);
        if (fromPackageJson.isPresent()) {
            return fromPackageJson;
        }

        boolean hasAzure = azureAccountsResolver.hasCredentials();
        boolean hasGithub = githubAccountsResolver.hasCredentials();
        if (hasAzure && !hasGithub) {
            return Optional.of(Source.azure);
        }
        if (hasGithub && !hasAzure) {
            return Optional.of(Source.github);
        }
        return Optional.empty();
    }

    public boolean isAmbiguous(Path projectRoot) {
        return detect(projectRoot).isEmpty()
            && azureAccountsResolver.hasCredentials()
            && githubAccountsResolver.hasCredentials();
    }

    private Optional<Source> detectFromGitConfig(Path projectRoot) {
        Path gitConfig = projectRoot.resolve(".git").resolve("config");
        if (!Files.exists(gitConfig)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(gitConfig);
            return fromHostPatterns(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Source> detectFromPackageJson(Path projectRoot) {
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(packageJson.toFile());
            JsonNode repository = root.get("repository");
            if (repository == null) {
                return Optional.empty();
            }
            String repoString = repository.isTextual() ? repository.asText() : repository.path("url").asText(null);
            if (repoString == null) {
                return Optional.empty();
            }
            return fromHostPatterns(repoString);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Source> fromHostPatterns(String content) {
        if (AZURE_HOST_PATTERN.matcher(content).find()) {
            return Optional.of(Source.azure);
        }
        if (GITHUB_HOST_PATTERN.matcher(content).find()) {
            return Optional.of(Source.github);
        }
        return Optional.empty();
    }
}
