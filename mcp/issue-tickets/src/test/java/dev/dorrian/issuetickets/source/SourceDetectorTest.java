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

    private static String pom(String projectChildren) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>widgets</artifactId>
              <version>1.0.0</version>
            %s
            </project>
            """.formatted(projectChildren);
    }

    @Test
    void detectsGithubFromPomScmUrl(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("<scm><url>https://github.com/org/widgets</url></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsAzureFromPomScmGitConnection(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            pom("<scm><connection>scm:git:https://dev.azure.com/org/proj/_git/widgets</connection></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void detectsAzureFromPomScmSshDeveloperConnectionOnVisualStudioHost(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            pom("<scm><developerConnection>scm:git:ssh://org@vs-ssh.visualstudio.com/v3/org/proj/widgets</developerConnection></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void detectsGithubFromPomScmSshConnection(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            pom("<scm><developerConnection>scm:git:git@github.com:org/widgets.git</developerConnection></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsGithubFromPomIssueManagementUrlWhenNoScm(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            pom("<issueManagement><system>GitHub</system><url>https://github.com/org/widgets/issues</url></issueManagement>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void detectsGithubFromPomProjectUrlWhenNoScmOrIssueManagement(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("<url>https://github.com/org/widgets</url>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void pomScmTakesPrecedenceOverPomProjectUrl(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("""
            <url>https://github.com/org/widgets</url>
            <scm><url>https://dev.azure.com/org/proj/_git/widgets</url></scm>
            """));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void ignoresHostUrlsOutsideTheProjectLevelScmUrlAndIssueManagementElements(@TempDir Path root) throws IOException {
        // A <repository>/<pluginRepository> or <distributionManagement> URL says where artifacts
        // live, not where tickets live — it must not be read as a provider signal.
        Files.writeString(root.resolve("pom.xml"), pom("""
            <repositories><repository><id>gh</id><url>https://maven.pkg.github.com/org/widgets</url></repository></repositories>
            <distributionManagement><repository><id>gh</id><url>https://maven.pkg.github.com/org/widgets</url></repository></distributionManagement>
            """));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).isEmpty();
    }

    @Test
    void pomWithNoScmFallsThroughToCredentials(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom(""));
        var detector = detectorWithCredentials(true, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void malformedPomFallsThroughWithoutThrowing(@TempDir Path root) throws IOException {
        // Truncated pom whose partial content would say github — parse failure must fall through
        // to the credentials tier (azure-only here), not throw or half-read it.
        Files.writeString(root.resolve("pom.xml"), "<project><scm><url>https://github.com/org/widgets</url></scm>");
        var detector = detectorWithCredentials(true, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void pomWithExternalEntityIsNotResolved(@TempDir Path root) throws IOException {
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "https://github.com/leaked/by-xxe");
        Files.writeString(root.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE project [ <!ENTITY xxe SYSTEM "%s"> ]>
            <project><modelVersion>4.0.0</modelVersion><scm><url>&xxe;</url></scm></project>
            """.formatted(secret.toUri()));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).isEmpty();
    }

    @Test
    void multiModuleUsesOnlyTheRootPomScm(@TempDir Path root) throws IOException {
        // Detection only ever inspects the project root (no walking into modules or up to
        // parents) — same as every other signal in the chain.
        Files.writeString(root.resolve("pom.xml"), pom("""
            <packaging>pom</packaging>
            <modules><module>api</module></modules>
            <scm><url>https://github.com/org/widgets</url></scm>
            """));
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api/pom.xml"), pom("<scm><url>https://dev.azure.com/org/proj/_git/widgets</url></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void submodulePomScmIsNotConsultedWhenRootPomHasNone(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("<modules><module>api</module></modules>"));
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api/pom.xml"), pom("<scm><url>https://github.com/org/widgets</url></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).isEmpty();
    }

    @Test
    void packageJsonRepositoryTakesPrecedenceOverPomScm(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{ \"repository\": \"github.com/org/repo\" }");
        Files.writeString(root.resolve("pom.xml"), pom("<scm><url>https://dev.azure.com/org/proj/_git/widgets</url></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.github);
    }

    @Test
    void pomScmIsUsedWhenPackageJsonHasNoRepositoryField(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{ \"name\": \"frontend\" }");
        Files.writeString(root.resolve("pom.xml"), pom("<scm><url>https://dev.azure.com/org/proj/_git/widgets</url></scm>"));
        var detector = detectorWithCredentials(false, false);

        assertThat(detector.detect(root)).contains(Source.azure);
    }

    @Test
    void gitConfigTakesPrecedenceOverPomScm(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@github.com:org/repo.git\n");
        Files.writeString(root.resolve("pom.xml"), pom("<scm><url>https://dev.azure.com/org/proj/_git/widgets</url></scm>"));
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
