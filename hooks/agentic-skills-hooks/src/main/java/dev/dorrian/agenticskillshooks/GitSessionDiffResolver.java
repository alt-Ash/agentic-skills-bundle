package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.GitCommitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of hooks/lib/event-log.ts's gitSessionChangeSummary. Summarizes everything that
 * changed during a session: commits made since startSha (recorded at session_start), the files
 * those commits touched, plus whatever is still uncommitted in the working tree right now.
 * Best-effort — returns an all-empty summary outside a git repo.
 */
public final class GitSessionDiffResolver {
    private static final Pattern INSERTIONS = Pattern.compile("(\\d+) insertion");
    private static final Pattern DELETIONS = Pattern.compile("(\\d+) deletion");

    private GitSessionDiffResolver() {
    }

    public static final class GitChangeSummary {
        public List<GitCommitInfo> commits = new ArrayList<>();
        public List<String> filesAdded = new ArrayList<>();
        public List<String> filesModified = new ArrayList<>();
        public List<String> filesDeleted = new ArrayList<>();
        public Integer linesAdded;
        public Integer linesDeleted;
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && !value.isEmpty() && !list.contains(value)) {
            list.add(value);
        }
    }

    // `git diff --name-status` lines: "A\tpath", "D\tpath", "M\tpath", "R100\told\tnew"
    // (renames/copies use the last column, the new path).
    private static void parseNameStatus(String output, GitChangeSummary into) {
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            String[] columns = line.split("\t");
            String status = columns[0];
            String filePath = columns[columns.length - 1];
            if (status.startsWith("A")) addUnique(into.filesAdded, filePath);
            else if (status.startsWith("D")) addUnique(into.filesDeleted, filePath);
            else addUnique(into.filesModified, filePath);
        }
    }

    // `git status --porcelain` lines: 2-char XY status code + path (renames use "old -> new";
    // "??" marks an untracked/new file).
    private static void parsePorcelainStatus(String output, GitChangeSummary into) {
        for (String line : output.split("\n")) {
            if (line.isEmpty()) continue;
            String code = line.length() >= 2 ? line.substring(0, 2) : line;
            String rest = line.length() > 3 ? line.substring(3) : "";
            String filePath = rest.contains(" -> ") ? rest.split(" -> ")[1] : rest;
            if (code.contains("D")) addUnique(into.filesDeleted, filePath);
            else if (code.equals("??") || code.contains("A")) addUnique(into.filesAdded, filePath);
            else addUnique(into.filesModified, filePath);
        }
    }

    public static GitChangeSummary summarize(String startSha) {
        GitChangeSummary summary = new GitChangeSummary();

        String headSha = GitProcess.runGit("rev-parse", "HEAD");
        if (startSha != null && headSha != null && !startSha.equals(headSha)) {
            String log = GitProcess.runGit("log", startSha + ".." + headSha, "--format=%h%x1f%s");
            if (log != null) {
                for (String line : log.split("\n")) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split("\u001f", 2);
                    String hash = parts[0];
                    String message = parts.length > 1 ? parts[1] : "";
                    summary.commits.add(new GitCommitInfo(hash, message));
                }
            }

            String diff = GitProcess.runGit("diff", "--name-status", startSha, headSha);
            if (diff != null) parseNameStatus(diff, summary);

            String shortstat = GitProcess.runGit("diff", "--shortstat", startSha, headSha);
            if (shortstat != null) {
                Matcher insertions = INSERTIONS.matcher(shortstat);
                Matcher deletions = DELETIONS.matcher(shortstat);
                summary.linesAdded = insertions.find() ? Integer.parseInt(insertions.group(1)) : 0;
                summary.linesDeleted = deletions.find() ? Integer.parseInt(deletions.group(1)) : 0;
            }
        }

        String status = GitProcess.runGit("status", "--porcelain");
        if (status != null) parsePorcelainStatus(status, summary);

        return summary;
    }
}
