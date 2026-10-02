package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageDb;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of hooks/lib/event-log.ts's resolveIdentity/resolveUser/resolveProject/
 * gitClientName/gitRepoName. Resolves user/project/client once per session, preferring the
 * stored session row over re-shelling to git on every hook invocation.
 */
public final class IdentityResolver {
    private static final Pattern REMOTE_URL = Pattern.compile("[/:]([^/:]+)/([^/]+)$");

    private IdentityResolver() {
    }

    public static final class ResolvedIdentity {
        public final String user;
        public final String project;
        public final String client;

        public ResolvedIdentity(String user, String project, String client) {
            this.user = user;
            this.project = project;
            this.client = client;
        }
    }

    // Package-private (not private) so IdentityResolverTest can exercise the pre-supplied-
    // remoteInfo seam directly, mirroring the TS test suite's "remoteInfo dedup contract" cases.
    static final class ParsedRemote {
        final String owner;
        final String repo;

        ParsedRemote(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }
    }

    static ParsedRemote parseRemoteUrl(String remote) {
        String cleaned = remote.replaceAll("\\.git$", "");
        Matcher m = REMOTE_URL.matcher(cleaned);
        if (!m.find()) return new ParsedRemote(null, null);
        return new ParsedRemote(m.group(1), m.group(2));
    }

    static ParsedRemote gitRemoteOriginInfo() {
        String remote = GitProcess.runGit("remote", "get-url", "origin");
        return remote != null ? parseRemoteUrl(remote) : new ParsedRemote(null, null);
    }

    static String gitRepoName(ParsedRemote remoteInfo) {
        if (remoteInfo.repo != null) return remoteInfo.repo;
        String toplevel = GitProcess.runGit("rev-parse", "--show-toplevel");
        if (toplevel == null) return null;
        Path fileName = Path.of(toplevel).getFileName();
        return fileName != null ? fileName.toString() : null;
    }

    static String gitClientName(ParsedRemote remoteInfo) {
        return remoteInfo.owner;
    }

    static String gitUserName() {
        return GitProcess.runGit("config", "user.name");
    }

    static String osUser() {
        String u = System.getProperty("user.name");
        return (u != null && !u.isEmpty()) ? u : "unknown";
    }

    static String resolveProject(HookInput input, ParsedRemote remoteInfo) {
        String repoName = gitRepoName(remoteInfo);
        if (repoName != null) return repoName;
        String cwd = (input.cwd() != null && !input.cwd().isEmpty()) ? input.cwd() : System.getProperty("user.dir");
        Path fileName = Path.of(cwd).getFileName();
        return fileName != null ? fileName.toString() : cwd;
    }

    static String resolveUser(HookInput input, String provider) {
        if ("cursor".equals(provider) && input.userEmail() != null && !input.userEmail().isEmpty()) {
            return input.userEmail();
        }
        String gitUser = gitUserName();
        return gitUser != null ? gitUser : osUser();
    }

    public static ResolvedIdentity resolveIdentity(HookInput input, String provider) {
        UsageDb.SessionRow stored = EventLog.session(input.sessionId());
        // client is intentionally not checked for truthiness — null is a legitimate value
        // (no origin remote). user/project are checked as a guard against a corrupted record.
        if (stored != null
                && stored.user() != null && !stored.user().isEmpty()
                && stored.project() != null && !stored.project().isEmpty()) {
            return new ResolvedIdentity(stored.user(), stored.project(), stored.client());
        }

        ParsedRemote remoteInfo = gitRemoteOriginInfo();
        String user = resolveUser(input, provider);
        String project = resolveProject(input, remoteInfo);
        String client = gitClientName(remoteInfo);
        return new ResolvedIdentity(user, project, client);
    }
}
