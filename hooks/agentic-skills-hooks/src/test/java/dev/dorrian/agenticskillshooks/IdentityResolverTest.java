package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers hooks/tests/event-log.test.ts's `resolveProject`, `gitClientName`, the
 * "pre-supplied remoteInfo" dedup-contract block, `resolveUser`, and `gitHeadSha`
 * describe blocks (IdentityResolver + GitProcess).
 */
class IdentityResolverTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    // ─── resolveProject — no git repo at all ────────────────────────────────

    @Test
    void resolveProjectFallsBackToLastPathSegmentOfDeclaredCwdWhenNotAGitRepo(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        HookInput input = HookInput.parse("{\"cwd\":\"/some/other/path\"}");
        String result = IdentityResolver.resolveProject(input, new IdentityResolver.ParsedRemote(null, null));
        assertEquals("path", result);
        assertFalse(result.contains("/"));
    }

    // ─── resolveProject — real git repo ─────────────────────────────────────

    @Test
    void resolveProjectPrefersTheRepoNameFromTheOriginRemoteWhenPresent(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        run(repo, "remote", "add", "origin", "https://github.com/example-org/example-repo.git");
        System.setProperty("user.dir", repo.toString());
        HookInput input = HookInput.parse("{}");
        assertEquals("example-repo", IdentityResolver.resolveProject(input, IdentityResolver.gitRemoteOriginInfo()));
    }

    @Test
    void resolveProjectFallsBackToTheGitToplevelFolderNameWhenThereIsNoRemote(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        System.setProperty("user.dir", repo.toString());
        HookInput input = HookInput.parse("{}");
        assertEquals(repo.getFileName().toString(),
                IdentityResolver.resolveProject(input, IdentityResolver.gitRemoteOriginInfo()));
    }

    // ─── gitClientName — real git repo, no remote / with remote ────────────

    @Test
    void gitClientNameReturnsNullWhenNoOriginRemote(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        System.setProperty("user.dir", repo.toString());
        assertNull(IdentityResolver.gitClientName(IdentityResolver.gitRemoteOriginInfo()));
    }

    @Test
    void gitClientNameReturnsNullOutsideAGitRepoEntirely(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        assertNull(IdentityResolver.gitClientName(IdentityResolver.gitRemoteOriginInfo()));
    }

    @Test
    void gitClientNameParsesOwnerFromHttpsRemoteUrl(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        run(repo, "remote", "add", "origin", "https://github.com/example-org/example-repo.git");
        System.setProperty("user.dir", repo.toString());
        assertEquals("example-org", IdentityResolver.gitClientName(IdentityResolver.gitRemoteOriginInfo()));
    }

    @Test
    void gitClientNameParsesOwnerFromSshRemoteUrl(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        run(repo, "remote", "add", "origin", "git@github.com:example-org/example-repo.git");
        System.setProperty("user.dir", repo.toString());
        assertEquals("example-org", IdentityResolver.gitClientName(IdentityResolver.gitRemoteOriginInfo()));
    }

    // ─── pre-supplied remoteInfo dedup contract ─────────────────────────────
    // resolveIdentity fetches gitRemoteOriginInfo() once and passes it into both gitRepoName
    // (via resolveProject) and gitClientName, instead of each re-shelling to git. Prove the
    // supplied remoteInfo is actually used by running outside any git repo — a live call would
    // fall through, but a used remoteInfo still resolves.

    @Test
    void gitRepoNameUsesSuppliedRemoteInfoInsteadOfResolvingLive(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        assertEquals("fake-repo",
                IdentityResolver.gitRepoName(new IdentityResolver.ParsedRemote("fake-owner", "fake-repo")));
    }

    @Test
    void gitClientNameUsesSuppliedRemoteInfoInsteadOfResolvingLive(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        assertEquals("fake-owner",
                IdentityResolver.gitClientName(new IdentityResolver.ParsedRemote("fake-owner", "fake-repo")));
    }

    @Test
    void resolveProjectUsesSuppliedRemoteInfoInsteadOfResolvingLive(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        HookInput input = HookInput.parse("{}");
        assertEquals("fake-repo",
                IdentityResolver.resolveProject(input, new IdentityResolver.ParsedRemote("fake-owner", "fake-repo")));
    }

    @Test
    void fallsBackToLiveResolutionWhenNoRemoteInfoSupplied(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        run(repo, "remote", "add", "origin", "https://github.com/example-org/example-repo.git");
        System.setProperty("user.dir", repo.toString());

        IdentityResolver.ParsedRemote live = IdentityResolver.gitRemoteOriginInfo();
        assertEquals("example-repo", IdentityResolver.gitRepoName(live));
        assertEquals("example-org", IdentityResolver.gitClientName(live));
    }

    // ─── resolveUser ─────────────────────────────────────────────────────────

    @Test
    void resolveUserReturnsCursorUserEmailWhenProviderIsCursor(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        HookInput input = HookInput.parse("{\"user_email\":\"a@b.com\"}");
        assertEquals("a@b.com", IdentityResolver.resolveUser(input, "cursor"));
    }

    @Test
    void resolveUserReadsGitConfigUserNameWhenInsideARepo(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        System.setProperty("user.dir", repo.toString());
        HookInput input = HookInput.parse("{}");
        assertEquals("Test User", IdentityResolver.resolveUser(input, "claude"));
    }

    // ─── gitHeadSha (inlined into GitSessionDiffResolver via GitProcess.runGit) ────────────

    @Test
    void gitHeadShaReturnsNullOutsideAGitRepo(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        assertNull(GitProcess.runGit("rev-parse", "HEAD"));
    }

    @Test
    void gitHeadShaReturnsTheCurrentHeadShaInsideARepo(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        java.nio.file.Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-m", "first commit");
        System.setProperty("user.dir", repo.toString());
        String sha = GitProcess.runGit("rev-parse", "HEAD");
        assertTrue(Pattern.matches("^[0-9a-f]{40}$", sha));
    }

    // ─── resolveIdentity — session baseline cache-hit behavior ──────────────

    private UsageEvent sessionStart(String sessionId, String user, String project, String client) {
        UsageEvent event = new UsageEvent();
        event.ts = "2026-06-23T10:00:00.000Z";
        event.event = "session_start";
        event.sessionId = sessionId;
        event.provider = "claude";
        event.user = user;
        event.project = project;
        event.client = client;
        return event;
    }

    @Test
    void resolveIdentityFallsBackToLiveGitResolutionWhenNoBaselineCachedYet(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        System.setProperty("user.dir", repo.toString());
        HookInput input = HookInput.parse("{\"session_id\":\"sess-cold\"}");
        IdentityResolver.ResolvedIdentity result = IdentityResolver.resolveIdentity(input, "claude");
        assertEquals("Test User", result.user);
        assertEquals(repo.getFileName().toString(), result.project);
        assertNull(result.client);
    }

    @Test
    void resolveIdentityReadsCachedValuesFromTheSessionBaselineInsteadOfReResolvingViaGit(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        // recordEvent is what production code calls at session_start — it writes the baseline
        // as one of its fan-out targets. No git repo here at all: a live resolution would return
        // the OS user / tmpdir basename, not these cached values.
        EventLog.recordEvent("session", sessionStart("sess-cached", "Cached User", "cached-project", "cached-client"));

        HookInput input = HookInput.parse("{\"session_id\":\"sess-cached\"}");
        IdentityResolver.ResolvedIdentity result = IdentityResolver.resolveIdentity(input, "claude");
        assertEquals("Cached User", result.user);
        assertEquals("cached-project", result.project);
        assertEquals("cached-client", result.client);
    }

    @Test
    void resolveIdentityTreatsACachedNullClientAsAValidCacheHitNotAMiss(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        EventLog.recordEvent("session", sessionStart("sess-null-client", "Cached User", "cached-project", null));

        HookInput input = HookInput.parse("{\"session_id\":\"sess-null-client\"}");
        IdentityResolver.ResolvedIdentity result = IdentityResolver.resolveIdentity(input, "claude");
        assertEquals("Cached User", result.user);
        assertEquals("cached-project", result.project);
        assertNull(result.client);
    }

    @Test
    void resolveIdentityIgnoresABaselineRecordMissingUserOrProject(@TempDir Path repo) throws Exception {
        initGitRepo(repo);
        System.setProperty("user.dir", repo.toString());
        EventLog.recordEvent("session", sessionStart("sess-partial", "", "", null));

        HookInput input = HookInput.parse("{\"session_id\":\"sess-partial\"}");
        IdentityResolver.ResolvedIdentity result = IdentityResolver.resolveIdentity(input, "claude");
        assertEquals("Test User", result.user);
        assertEquals(repo.getFileName().toString(), result.project);
    }

    @Test
    void resolveIdentityDoesNotGrowWithASessionsActivity(@TempDir Path notARepo) {
        System.setProperty("user.dir", notARepo.toString());
        EventLog.recordEvent("session", sessionStart("sess-long", "Cached User", "cached-project", "cached-client"));
        for (int i = 0; i < 20; i++) {
            UsageEvent toolUse = sessionStart("sess-long", "Cached User", "cached-project", "cached-client");
            toolUse.event = "tool_use";
            EventLog.recordEvent("post-tool-use", toolUse);
        }
        // One baseline record for this session, not one per event.
        SessionBaselineStore.SessionBaseline baseline = SessionBaselineStore.read("sess-long");
        assertEquals("sess-long", baseline.sessionId);

        HookInput input = HookInput.parse("{\"session_id\":\"sess-long\"}");
        IdentityResolver.ResolvedIdentity result = IdentityResolver.resolveIdentity(input, "claude");
        assertEquals("Cached User", result.user);
        assertEquals("cached-project", result.project);
        assertEquals("cached-client", result.client);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void initGitRepo(Path dir) throws IOException, InterruptedException {
        run(dir, "init", "-q");
        run(dir, "config", "user.name", "Test User");
        run(dir, "config", "user.email", "test@example.com");
    }

    private void run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) throw new IOException("git " + String.join(" ", args) + " failed with exit " + code);
    }
}
