package dev.dorrian.issuetickets.source;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dorrian.issuetickets.credentials.AzureAccountsResolver;
import dev.dorrian.issuetickets.credentials.GithubAccountsResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDetectorTest {

    private static SourceDetector detectorWithCredentials(boolean azure, boolean github) {
        var azureResolver = new AzureAccountsResolver(
            azure ? Map.of("AZURE_DEVOPS_ORG_URL", "u", "AZURE_DEVOPS_TOKEN", "t")::get : Map.<String, String>of()::get);
        var githubResolver = new GithubAccountsResolver(
            github ? Map.of("GITHUB_TOKEN", "t")::get : Map.<String, String>of()::get);
        return new SourceDetector(azureResolver, githubResolver);
    }

    @Test
    void detectsGithubFromDotGithubDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".github"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsAzureFromAzurePipelinesYml(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("azure-pipelines.yml"), "trigger: main");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void detectsAzureFromDotAzureDirectoryWhenNoPipelinesFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".azure"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void dotGithubDirectoryTakesPrecedenceOverAzurePipelinesYml(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve("azure-pipelines.yml"), "trigger: main");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsAzureFromGitConfigHostPattern(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[remote \"origin\"]\n\turl = https://dev.azure.com/org/proj/_git/repo\n");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void detectsGithubFromGitConfigHostPattern(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@github.com:org/repo.git\n");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsAzureFromPackageJsonRepositoryUrlField(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"),
            "{ \"repository\": { \"url\": \"https://org.visualstudio.com/proj/_git/repo\" } }");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void detectsGithubFromPackageJsonRepositoryStringField(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{ \"repository\": \"github.com/org/repo\" }");
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void fallsBackToTheSingleConfiguredProviderWhenNoFileSignalsExist(@TempDir Path root) {
        var detector = detectorWithCredentials(true, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void returnsEmptyWhenNoFileSignalsAndNeitherProviderHasCredentials(@TempDir Path root) {
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).isEmpty();
    }

    @Test
    void isAmbiguousWhenNoFileSignalsAndBothProvidersHaveCredentials(@TempDir Path root) {
        var detector = detectorWithCredentials(true, true);

        assertThat(detector.detect(root)).isEmpty();
        assertThat(detector.isAmbiguous(root)).isTrue();
    }

    @Test
    void isNotAmbiguousWhenAFileSignalResolvesItEvenIfBothHaveCredentials(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".github"));
        var detector = detectorWithCredentials(true, true);

        assertThat(detector.isAmbiguous(root)).isFalse();
    }
}
