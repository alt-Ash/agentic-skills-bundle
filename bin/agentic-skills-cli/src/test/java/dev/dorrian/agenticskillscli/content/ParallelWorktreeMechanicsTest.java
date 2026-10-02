package dev.dorrian.agenticskillscli.content;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the git mechanics prescribed by skills/workflow/parallel-feature-build/SKILL.md
 * (worktree-per-slice, concurrent workers, ordered --no-ff merge) against a real throwaway repo:
 * disjoint slices merge cleanly while genuinely running at the same time, and an overlapping
 * slice surfaces as a merge conflict (the skill's "Phase 0 false negative" signal).
 */
class ParallelWorktreeMechanicsTest {

    private static final String FEATURE = "orders-api";

    @Test
    void disjointSlicesBuildConcurrentlyAndMergeCleanly(@TempDir Path repo) throws Exception {
        initRepo(repo);
        List<String> files = List.of("src/OrderController.java", "src/OrderService.java", "src/OrderRepository.java");
        addWorktrees(repo, files.size());

        runWorkersConcurrently(repo, files);

        git(repo, "checkout", "-b", FEATURE + "-integration", "main");
        for (int n = 1; n <= files.size(); n++) {
            git(repo, "merge", "--no-ff", "-m", "slice " + n, branch(n));
        }
        for (String f : files) {
            assertTrue(Files.exists(repo.resolve(f)), f + " missing after merge");
        }
        assertEquals("", git(repo, "status", "--porcelain").strip(), "integration tree must be clean");
    }

    @Test
    void overlappingSliceSurfacesAsMergeConflict(@TempDir Path repo) throws Exception {
        initRepo(repo);
        List<String> files = List.of("pom.xml", "pom.xml"); // both slices touch the shared pom
        addWorktrees(repo, files.size());

        runWorkersConcurrently(repo, files);

        git(repo, "checkout", "-b", FEATURE + "-integration", "main");
        git(repo, "merge", "--no-ff", "-m", "slice 1", branch(1));
        assertNotEquals(0, gitExit(repo, "merge", "--no-ff", "-m", "slice 2", branch(2)),
            "overlapping slices must conflict, never silently merge");
    }

    // ---- helpers -------------------------------------------------------------------------

    private static String branch(int n) {
        return "feature/" + FEATURE + "/slice-" + n;
    }

    private static Path worktree(Path repo, int n) {
        return repo.resolve(".worktrees").resolve(FEATURE).resolve("slice-" + n);
    }

    private static void initRepo(Path repo) throws Exception {
        Assumptions.assumeTrue(gitExit(repo.getParent(), "--version") == 0, "git not on PATH");
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        Files.writeString(repo.resolve(".gitignore"), ".worktrees/\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "base");
    }

    private static void addWorktrees(Path repo, int slices) throws Exception {
        for (int n = 1; n <= slices; n++) {
            git(repo, "worktree", "add", worktree(repo, n).toString(), "-b", branch(n));
        }
    }

    /** One thread per slice; a barrier proves every worker was in flight before any finished. */
    private static void runWorkersConcurrently(Path repo, List<String> files) throws Exception {
        int n = files.size();
        CountDownLatch allStarted = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int slice = i + 1;
                String file = files.get(i);
                futures.add(pool.submit(() -> {
                    Path wt = worktree(repo, slice);
                    allStarted.countDown();
                    assertTrue(allStarted.await(30, TimeUnit.SECONDS), "workers did not overlap in time");
                    Path target = wt.resolve(file);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "slice " + slice + "\n");
                    git(wt, "add", ".");
                    git(wt, "commit", "-m", "slice " + slice);
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        Result r = run(dir, args);
        if (r.exit != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + r.out);
        return r.out;
    }

    private static int gitExit(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, args).exit;
    }

    private record Result(int exit, String out) {
    }

    private static Result run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Result(p.waitFor(), out);
    }
}
