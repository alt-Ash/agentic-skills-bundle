package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSessionDiffResolverTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void summarizesCommitsFilesAndUncommittedChanges(@TempDir Path repo) throws IOException, InterruptedException {
        run(repo, "init");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test User");

        Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-m", "initial commit");
        String startSha = runCapture(repo, "rev-parse", "HEAD");

        Files.writeString(repo.resolve("a.txt"), "hello world\n");
        Files.writeString(repo.resolve("b.txt"), "new file\n");
        run(repo, "add", "a.txt", "b.txt");
        run(repo, "commit", "-m", "second commit");

        Files.writeString(repo.resolve("c.txt"), "uncommitted\n");

        System.setProperty("user.dir", repo.toString());
        GitSessionDiffResolver.GitChangeSummary summary = GitSessionDiffResolver.summarize(startSha);

        assertEquals(1, summary.commits.size());
        assertEquals("second commit", summary.commits.get(0).message);
        assertTrue(summary.filesModified.contains("a.txt"));
        assertTrue(summary.filesAdded.contains("b.txt"));
        assertTrue(summary.filesAdded.contains("c.txt"));
        // a.txt's single line was replaced (1 deletion + 1 insertion); b.txt added 1 line.
        assertEquals(2, summary.linesAdded);
        assertEquals(1, summary.linesDeleted);
    }

    @Test
    void returnsAnEmptySummaryWhenStartShaIsNull(@TempDir Path repo) throws IOException, InterruptedException {
        run(repo, "init");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test User");
        System.setProperty("user.dir", repo.toString());

        GitSessionDiffResolver.GitChangeSummary summary = GitSessionDiffResolver.summarize(null);
        assertTrue(summary.commits.isEmpty());
        assertTrue(summary.filesAdded.isEmpty());
    }

    @Test
    void includesCurrentlyUncommittedChangesEvenWithNoNewCommits(@TempDir Path repo)
            throws IOException, InterruptedException {
        run(repo, "init");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test User");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-m", "first commit");
        String startSha = runCapture(repo, "rev-parse", "HEAD");

        Files.writeString(repo.resolve("untracked.txt"), "new\n");
        System.setProperty("user.dir", repo.toString());

        GitSessionDiffResolver.GitChangeSummary summary = GitSessionDiffResolver.summarize(startSha);
        assertTrue(summary.commits.isEmpty());
        assertTrue(summary.filesAdded.contains("untracked.txt"));
    }

    @Test
    void preservesALeadingDotOnADotfileThatIsTheOnlyFirstPorcelainLine(@TempDir Path repo)
            throws IOException, InterruptedException {
        // Regression: a whole-string trim on git's porcelain output would strip the leading
        // space off " M .env" when it's the very first line, shifting every column left by one
        // and truncating the filename's leading dot.
        run(repo, "init");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test User");
        Files.writeString(repo.resolve(".env"), "A=1\n");
        run(repo, "add", ".env");
        run(repo, "commit", "-m", "add .env");
        String startSha = runCapture(repo, "rev-parse", "HEAD");

        Files.writeString(repo.resolve(".env"), "A=1\nB=2\n");
        System.setProperty("user.dir", repo.toString());

        GitSessionDiffResolver.GitChangeSummary summary = GitSessionDiffResolver.summarize(startSha);
        assertTrue(summary.filesModified.contains(".env"));
    }

    @Test
    void returnsEmptySummaryOutsideGitRepo(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        GitSessionDiffResolver.GitChangeSummary summary = GitSessionDiffResolver.summarize(null);
        assertTrue(summary.commits.isEmpty());
        assertTrue(summary.filesAdded.isEmpty());
        assertTrue(summary.filesModified.isEmpty());
        assertTrue(summary.filesDeleted.isEmpty());
    }

    private void run(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) throw new IOException("git " + String.join(" ", args) + " failed with exit " + code);
    }

    private String runCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.trim();
    }
}
