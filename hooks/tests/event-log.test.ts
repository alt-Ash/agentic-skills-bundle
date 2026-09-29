import { describe, it, expect, vi, beforeEach, afterEach, beforeAll, afterAll } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFile } from 'child_process';
import { promisify } from 'util';
import {
  detectProvider,
  sessionId,
  resolveProject,
  resolveUser,
  resolveIdentity,
  gitRepoName,
  gitClientName,
  osUser,
  gitHeadSha,
  gitSessionChangeSummary,
  readSessionGroup,
  readSessionBaseline,
  extractBashCommand,
  extractSlashCommand,
  redactSecrets,
  parseInput,
  appendEvent,
  appendHookTypeData,
  appendSessionConsolidatedEvent,
  recordEvent,
  MAX_SESSION_GROUPS,
} from '../lib/event-log.js';

const execFileAsync = promisify(execFile);

async function initGitRepo(dir: string): Promise<void> {
  await execFileAsync('git', ['init', '-q'], { cwd: dir });
  await execFileAsync('git', ['config', 'user.name', 'Test User'], { cwd: dir });
  await execFileAsync('git', ['config', 'user.email', 'test@example.com'], { cwd: dir });
}

async function commitAll(dir: string, message: string): Promise<void> {
  await execFileAsync('git', ['add', '-A'], { cwd: dir });
  await execFileAsync('git', ['commit', '-q', '-m', message], { cwd: dir });
}
import type { UsageEvent, SessionEventGroup } from '../lib/event-log.js';

const baseEvent: UsageEvent = {
  ts: '2026-06-23T10:00:00.000Z',
  event: 'session_start',
  sessionId: 'sess-1',
  provider: 'claude',
  user: 'ashleigh',
  project: '/dev/project',
};

// ─── detectProvider ───────────────────────────────────────────────────────────

describe('detectProvider', () => {
  it('returns gemini when hook_event_name is AfterTool', () => {
    expect(detectProvider({ hook_event_name: 'AfterTool' })).toBe('gemini');
  });

  it('returns cursor when model + user_email (string) are present', () => {
    expect(detectProvider({ model: 'gpt-4', user_email: 'a@b.com' })).toBe('cursor');
  });

  it('returns cursor when model + conversation_id are present', () => {
    expect(detectProvider({ model: 'gpt-4', conversation_id: 'abc' })).toBe('cursor');
  });

  it('returns cursor when user_email is null (null !== undefined)', () => {
    expect(detectProvider({ model: 'gpt-4', user_email: null })).toBe('cursor');
  });

  it('returns codex when model is present but no user_email or conversation_id', () => {
    expect(detectProvider({ model: 'gpt-4' })).toBe('codex');
  });

  it('returns claude for an empty payload', () => {
    expect(detectProvider({})).toBe('claude');
  });

  it('hook_event_name wins over model for gemini detection', () => {
    expect(detectProvider({ hook_event_name: 'AfterTool', model: 'gemini-pro' })).toBe('gemini');
  });
});

// ─── sessionId ────────────────────────────────────────────────────────────────

describe('sessionId', () => {
  it('returns session_id when present', () => {
    expect(sessionId({ session_id: 'abc' })).toBe('abc');
  });

  it('returns conversation_id when session_id absent', () => {
    expect(sessionId({ conversation_id: 'xyz' })).toBe('xyz');
  });

  it('prefers session_id over conversation_id', () => {
    expect(sessionId({ session_id: 'abc', conversation_id: 'xyz' })).toBe('abc');
  });

  it('returns null for empty payload', () => {
    expect(sessionId({})).toBeNull();
  });
});

// ─── extractBashCommand ─────────────────────────────────────────────────────────

describe('extractBashCommand', () => {
  it('extracts the command when tool_name is Bash', () => {
    expect(extractBashCommand({ tool_name: 'Bash', tool_input: { command: 'git status' } })).toBe('git status');
  });

  it('returns null for a non-Bash tool, even if tool_input happens to have a command-like field', () => {
    expect(extractBashCommand({ tool_name: 'Read', tool_input: { command: 'not-actually-a-command' } })).toBeNull();
  });

  it('returns null when tool_name is Bash but tool_input is missing', () => {
    expect(extractBashCommand({ tool_name: 'Bash' })).toBeNull();
  });

  it('returns null when tool_input.command is not a string', () => {
    expect(extractBashCommand({ tool_name: 'Bash', tool_input: { command: 123 } })).toBeNull();
  });

  it('returns null for an empty payload', () => {
    expect(extractBashCommand({})).toBeNull();
  });

  it('redacts secrets in the extracted command', () => {
    const result = extractBashCommand({
      tool_name: 'Bash',
      tool_input: { command: 'export GITHUB_TOKEN=ghp_1234567890abcdef1234567890abcdef1234' },
    });
    expect(result).not.toContain('ghp_1234567890abcdef1234567890abcdef1234');
  });
});

// ─── redactSecrets ──────────────────────────────────────────────────────────────

describe('redactSecrets', () => {
  it('leaves an ordinary command with no secrets unchanged', () => {
    expect(redactSecrets('git status && pnpm test')).toBe('git status && pnpm test');
  });

  it('does not false-positive on an ordinary git commit sha', () => {
    const cmd = 'git show 5e30633aab1122334455667788990011223344 --stat';
    expect(redactSecrets(cmd)).toBe(cmd);
  });

  it('redacts basic auth credentials embedded in a URL', () => {
    const result = redactSecrets('git clone https://myuser:ghp_supersecret123@github.com/org/repo.git');
    expect(result).not.toContain('ghp_supersecret123');
    expect(result).not.toContain('myuser');
    expect(result).toContain('[REDACTED]@github.com');
  });

  it('redacts a Bearer token', () => {
    const result = redactSecrets('curl -H "Authorization: Bearer sk-ant-abcdefghijklmnopqrstuvwxyz123456" https://api.example.com');
    expect(result).not.toContain('sk-ant-abcdefghijklmnopqrstuvwxyz123456');
    expect(result).toContain('Bearer [REDACTED]');
  });

  it('does not swallow the closing quote after a quoted Bearer token', () => {
    const result = redactSecrets('curl -H "Authorization: Bearer sk-ant-abcdefghijklmnopqrstuvwxyz123456" https://api.example.com');
    expect(result).toBe('curl -H "Authorization: Bearer [REDACTED]" https://api.example.com');
  });

  it('redacts a GitHub personal access token', () => {
    const result = redactSecrets('export GITHUB_TOKEN=ghp_1234567890abcdef1234567890abcdef1234');
    expect(result).not.toContain('ghp_1234567890abcdef1234567890abcdef1234');
  });

  it('redacts an AWS access key ID', () => {
    const result = redactSecrets('aws configure set aws_access_key_id AKIAIOSFODNN7EXAMPLE');
    expect(result).not.toContain('AKIAIOSFODNN7EXAMPLE');
  });

  it('redacts an OpenAI/Anthropic-style secret key', () => {
    const result = redactSecrets('curl -H "x-api-key: sk-proj-abcdefghijklmnopqrstuvwxyz1234567890"');
    expect(result).not.toContain('sk-proj-abcdefghijklmnopqrstuvwxyz1234567890');
  });

  it('redacts a JWT', () => {
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dQw4w9WgXcQ_dGVzdHNpZ25hdHVyZQ';
    const result = redactSecrets(`curl -H "Authorization: ${jwt}"`);
    expect(result).not.toContain(jwt);
  });

  it('redacts curl-style -u user:pass', () => {
    const result = redactSecrets('curl -u admin:hunter2 https://internal.example.com/api');
    expect(result).not.toContain('hunter2');
    expect(result).not.toContain('admin:hunter2');
  });

  it('redacts a PASSWORD env var assignment while keeping the variable name', () => {
    const result = redactSecrets('DB_PASSWORD=hunter2 psql mydb');
    expect(result).not.toContain('hunter2');
    expect(result).toContain('DB_PASSWORD=[REDACTED]');
  });

  it('redacts a --password CLI flag', () => {
    const result = redactSecrets('mysql --user=root --password=hunter2');
    expect(result).not.toContain('hunter2');
  });

  it('redacts a PEM private key block', () => {
    const pem = '-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----';
    const result = redactSecrets(`echo "${pem}" > key.pem`);
    expect(result).not.toContain('MIIEow');
    expect(result).toContain('[REDACTED PRIVATE KEY]');
  });

  it('truncates a command longer than the max length', () => {
    const longCommand = 'echo ' + 'a'.repeat(3000);
    const result = redactSecrets(longCommand);
    expect(result.length).toBeLessThan(longCommand.length);
    expect(result.endsWith('...[truncated]')).toBe(true);
  });

  it('does not still redact a value once it has been redacted (idempotent)', () => {
    const once = redactSecrets('DB_PASSWORD=hunter2');
    const twice = redactSecrets(once);
    expect(twice).toBe(once);
  });

  it('leaves a non-sensitive assignment untouched', () => {
    expect(redactSecrets('FOO=bar echo hi')).toBe('FOO=bar echo hi');
  });

  it('regression: does not catastrophically backtrack on a long run of a sensitive keyword with no trailing "="', () => {
    // Previously ~10s for a 250KB string of repeated "TOKEN" with no "="
    // to complete the match — the env-var rule used to be
    // `[A-Za-z0-9_]*(?:TOKEN|...)[A-Za-z0-9_]*`, a "wildcard around an
    // alternation" shape that backtracks exponentially on this input.
    const input = 'echo ' + 'TOKEN'.repeat(50_000);
    const start = Date.now();
    redactSecrets(input);
    expect(Date.now() - start).toBeLessThan(500);
  });
});

// ─── extractSlashCommand ────────────────────────────────────────────────────────

describe('extractSlashCommand', () => {
  // The raw UserPromptSubmit payload carries the literal typed line, not the
  // <command-name> tag form (confirmed by cross-checking promptCharLength
  // against the transcript — see the comment on extractSlashCommand).

  it('extracts the command name from a bare slash command with no args', () => {
    expect(extractSlashCommand('/clear')).toBe('/clear');
  });

  it('extracts the command name when followed by args', () => {
    expect(extractSlashCommand('/code-review the latest changes')).toBe('/code-review');
  });

  it('does not capture the args text', () => {
    const result = extractSlashCommand('/plan find other files that should be included in the gitignore');
    expect(result).toBe('/plan');
    expect(result).not.toContain('gitignore');
  });

  it('handles a hyphenated command name', () => {
    expect(extractSlashCommand('/code-review')).toBe('/code-review');
  });

  it('does not mistake an absolute path for a slash command', () => {
    expect(extractSlashCommand('/Users/testuser/Development/Acme is broken')).toBeNull();
  });

  it('does not mistake a path with no trailing text for a slash command', () => {
    expect(extractSlashCommand('/etc/hosts')).toBeNull();
  });

  it('returns null for a plain-text prompt with no slash command', () => {
    expect(extractSlashCommand('just a normal message')).toBeNull();
  });

  it('returns null when prompt is undefined', () => {
    expect(extractSlashCommand(undefined)).toBeNull();
  });

  it('falls back to the tagged form if a payload ever delivers it directly', () => {
    const prompt = '<command-message>code-review</command-message>\n<command-name>/code-review</command-name>';
    expect(extractSlashCommand(prompt)).toBe('/code-review');
  });
});

// ─── resolveProject ─────────────────────────────────────────────────────────────

describe('resolveProject', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('falls back to the last path segment of the declared cwd when not a git repo', async () => {
    expect(await resolveProject({ cwd: '/some/other/path' })).toBe('path');
  });

  it('never returns a full path — only the repo/folder name', async () => {
    const result = await resolveProject({ cwd: '/some/other/path' });
    expect(result).not.toContain('/');
  });

  it('prefers the repo name from the origin remote when present', async () => {
    await initGitRepo(tmpDir);
    await execFileAsync('git', ['remote', 'add', 'origin', 'https://github.com/example-org/example-repo.git'], { cwd: tmpDir });
    expect(await resolveProject({})).toBe('example-repo');
  });

  it('falls back to the git toplevel folder name when there is no remote', async () => {
    await initGitRepo(tmpDir);
    expect(await resolveProject({})).toBe(path.basename(tmpDir));
  });
});

// ─── gitClientName ───────────────────────────────────────────────────────────────

describe('gitClientName', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns null when there is no origin remote', async () => {
    await initGitRepo(tmpDir);
    expect(await gitClientName()).toBeNull();
  });

  it('returns null outside a git repo entirely', async () => {
    expect(await gitClientName()).toBeNull();
  });

  it('parses the org/owner slug from an https remote URL', async () => {
    await initGitRepo(tmpDir);
    await execFileAsync('git', ['remote', 'add', 'origin', 'https://github.com/example-org/example-repo.git'], { cwd: tmpDir });
    expect(await gitClientName()).toBe('example-org');
  });

  it('parses the org/owner slug from an ssh remote URL', async () => {
    await initGitRepo(tmpDir);
    await execFileAsync('git', ['remote', 'add', 'origin', 'git@github.com:example-org/example-repo.git'], { cwd: tmpDir });
    expect(await gitClientName()).toBe('example-org');
  });
});

// ─── remoteInfo dedup contract (gitRepoName / gitClientName / resolveProject) ───
//
// resolveIdentity fetches gitRemoteOriginInfo() once and passes it to both
// gitRepoName (via resolveProject) and gitClientName, instead of each spawning
// its own `git remote get-url origin`. These tests prove that pre-supplied
// remoteInfo is actually used — by running outside any git repo, where a live
// call would return null/fall through, but a used remoteInfo returns its value.

describe('gitRepoName / gitClientName / resolveProject — pre-supplied remoteInfo', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('gitRepoName uses the supplied remoteInfo instead of resolving live (no git repo present)', async () => {
    expect(await gitRepoName({ owner: 'fake-owner', repo: 'fake-repo' })).toBe('fake-repo');
  });

  it('gitClientName uses the supplied remoteInfo instead of resolving live (no git repo present)', async () => {
    expect(await gitClientName({ owner: 'fake-owner', repo: 'fake-repo' })).toBe('fake-owner');
  });

  it('resolveProject uses the supplied remoteInfo instead of resolving live (no git repo present)', async () => {
    expect(await resolveProject({}, { owner: 'fake-owner', repo: 'fake-repo' })).toBe('fake-repo');
  });

  it('falls back to a live resolution when no remoteInfo is supplied', async () => {
    await initGitRepo(tmpDir);
    await execFileAsync('git', ['remote', 'add', 'origin', 'https://github.com/example-org/example-repo.git'], { cwd: tmpDir });
    expect(await gitRepoName()).toBe('example-repo');
    expect(await gitClientName()).toBe('example-org');
  });
});

// ─── resolveUser ────────────────────────────────────────────────────────────────

describe('resolveUser', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;
  let envBackup: NodeJS.ProcessEnv;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
    envBackup = { ...process.env };
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    process.env = envBackup;
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns the cursor user_email when provider is cursor', async () => {
    expect(await resolveUser({ user_email: 'a@b.com' }, 'cursor')).toBe('a@b.com');
  });

  it('reads git config user.name when inside a repo', async () => {
    await initGitRepo(tmpDir);
    expect(await resolveUser({}, 'claude')).toBe('Test User');
  });

  it('falls back to the OS user when git has no user.name configured anywhere', async () => {
    process.env.HOME = tmpDir;
    process.env.GIT_CONFIG_GLOBAL = '/dev/null';
    process.env.GIT_CONFIG_SYSTEM = '/dev/null';
    expect(await resolveUser({}, 'claude')).toBe(osUser());
  });
});

// ─── resolveIdentity ─────────────────────────────────────────────────────────────

describe('resolveIdentity', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('falls back to a live git resolution when no baseline is cached yet', async () => {
    await initGitRepo(tmpDir);
    const result = await resolveIdentity({ session_id: 'sess-cold' }, 'claude');
    expect(result.user).toBe('Test User');
    expect(result.project).toBe(path.basename(tmpDir));
    expect(result.client).toBeNull();
  });

  it('reads cached user/project/client from the session baseline instead of re-resolving via git', async () => {
    // recordEvent is what production code actually calls at session_start —
    // it writes the baseline as one of its fan-out targets.
    await recordEvent('session', {
      ...baseEvent,
      sessionId: 'sess-cached',
      event: 'session_start',
      user: 'Cached User',
      project: 'cached-project',
      client: 'cached-client',
    });

    // No git repo here at all — if this fell through to a live resolution it
    // would return the OS user / tmpDir basename, not the cached values.
    const result = await resolveIdentity({ session_id: 'sess-cached' }, 'claude');
    expect(result).toEqual({ user: 'Cached User', project: 'cached-project', client: 'cached-client' });
  });

  it('treats a cached client of null as a valid cache hit, not a miss', async () => {
    // A repo with no origin remote legitimately resolves client to null.
    await recordEvent('session', {
      ...baseEvent,
      sessionId: 'sess-null-client',
      event: 'session_start',
      user: 'Cached User',
      project: 'cached-project',
      client: null,
    });
    const result = await resolveIdentity({ session_id: 'sess-null-client' }, 'claude');
    expect(result).toEqual({ user: 'Cached User', project: 'cached-project', client: null });
  });

  it('ignores a baseline record missing user or project', async () => {
    await initGitRepo(tmpDir);
    await recordEvent('session', {
      ...baseEvent,
      sessionId: 'sess-partial',
      event: 'session_start',
      user: '',
      project: '',
    });
    const result = await resolveIdentity({ session_id: 'sess-partial' }, 'claude');
    expect(result.user).toBe('Test User');
    expect(result.project).toBe(path.basename(tmpDir));
  });

  it('does not grow with a session\'s activity — the baseline is unaffected by later non-session_start events', async () => {
    await recordEvent('session', {
      ...baseEvent,
      sessionId: 'sess-long',
      event: 'session_start',
      user: 'Cached User',
      project: 'cached-project',
      client: 'cached-client',
    });
    for (let i = 0; i < 20; i++) {
      await recordEvent('post-tool-use', {
        ...baseEvent,
        sessionId: 'sess-long',
        event: 'tool_use',
        user: 'Cached User',
        project: 'cached-project',
        client: 'cached-client',
      });
    }
    const baselineFile = path.join(tmpDir, '.hooks-data', 'session-baseline.json');
    const baselines = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
    // One record for this session, not one per event.
    expect(baselines.filter((b: { sessionId: string }) => b.sessionId === 'sess-long')).toHaveLength(1);

    const result = await resolveIdentity({ session_id: 'sess-long' }, 'claude');
    expect(result).toEqual({ user: 'Cached User', project: 'cached-project', client: 'cached-client' });
  });
});

// ─── gitHeadSha / gitSessionChangeSummary ───────────────────────────────────────

describe('gitHeadSha', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns null outside a git repo', async () => {
    expect(await gitHeadSha()).toBeNull();
  });

  it('returns the current HEAD sha inside a repo', async () => {
    await initGitRepo(tmpDir);
    fs.writeFileSync(path.join(tmpDir, 'a.txt'), 'hello\n');
    await commitAll(tmpDir, 'first commit');
    expect(await gitHeadSha()).toMatch(/^[0-9a-f]{40}$/);
  });
});

describe('gitSessionChangeSummary', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns an empty summary when startSha is null', async () => {
    await initGitRepo(tmpDir);
    const summary = await gitSessionChangeSummary(null);
    expect(summary.commits).toEqual([]);
    expect(summary.filesAdded).toEqual([]);
  });

  it('summarizes commits, added/deleted files, and line counts made since startSha', async () => {
    await initGitRepo(tmpDir);
    fs.writeFileSync(path.join(tmpDir, 'a.txt'), 'hello\n');
    await commitAll(tmpDir, 'add a.txt');
    const startSha = await gitHeadSha();

    fs.writeFileSync(path.join(tmpDir, 'b.txt'), 'world\n');
    fs.appendFileSync(path.join(tmpDir, 'a.txt'), 'more\n');
    await commitAll(tmpDir, 'add b.txt, edit a.txt');

    fs.rmSync(path.join(tmpDir, 'a.txt'));
    await commitAll(tmpDir, 'remove a.txt');

    fs.writeFileSync(path.join(tmpDir, 'c.txt'), 'uncommitted\n');

    const summary = await gitSessionChangeSummary(startSha);
    expect(summary.commits).toHaveLength(2);
    expect(summary.commits[0].message).toBe('remove a.txt');
    expect(summary.commits[1].message).toBe('add b.txt, edit a.txt');
    expect(summary.filesAdded).toEqual(expect.arrayContaining(['b.txt', 'c.txt']));
    expect(summary.filesDeleted).toContain('a.txt');
    expect(typeof summary.linesAdded).toBe('number');
    expect(typeof summary.linesDeleted).toBe('number');
  });

  it('includes currently-uncommitted changes even with no new commits', async () => {
    await initGitRepo(tmpDir);
    fs.writeFileSync(path.join(tmpDir, 'a.txt'), 'hello\n');
    await commitAll(tmpDir, 'first commit');
    const startSha = await gitHeadSha();

    fs.writeFileSync(path.join(tmpDir, 'untracked.txt'), 'new\n');

    const summary = await gitSessionChangeSummary(startSha);
    expect(summary.commits).toEqual([]);
    expect(summary.filesAdded).toContain('untracked.txt');
  });

  it('preserves a leading dot on a dotfile that is the only (first) porcelain line', async () => {
    // Regression: a whole-string .trim() on git's stdout would strip the
    // leading space off " M .env" when it's the very first line, shifting
    // every column left by one and truncating the filename's leading dot.
    await initGitRepo(tmpDir);
    fs.writeFileSync(path.join(tmpDir, '.env'), 'A=1\n');
    await commitAll(tmpDir, 'add .env');
    const startSha = await gitHeadSha();

    fs.appendFileSync(path.join(tmpDir, '.env'), 'B=2\n');

    const summary = await gitSessionChangeSummary(startSha);
    expect(summary.filesModified).toContain('.env');
  });
});

// ─── readSessionGroup ────────────────────────────────────────────────────────────

describe('readSessionGroup', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns [] when sessionId is null', async () => {
    expect(await readSessionGroup(null)).toEqual([]);
  });

  it('returns [] when no consolidated file exists yet', async () => {
    expect(await readSessionGroup('sess-1')).toEqual([]);
  });

  it('returns the hooks array for a matching session group', async () => {
    await appendSessionConsolidatedEvent(baseEvent);
    expect(await readSessionGroup('sess-1')).toEqual([baseEvent]);
  });
});

// ─── parseInput ───────────────────────────────────────────────────────────────

describe('parseInput', () => {
  it('parses a valid JSON object', () => {
    expect(parseInput('{"a":1}')).toEqual({ a: 1 });
  });

  it('returns {} for a JSON string (not object)', () => {
    expect(parseInput('"hello"')).toEqual({});
  });

  it('returns {} for malformed JSON', () => {
    expect(parseInput('not json')).toEqual({});
  });

  it('returns {} for empty string', () => {
    expect(parseInput('')).toEqual({});
  });
});

// ─── appendEvent ─────────────────────────────────────────────────────────────

describe('appendEvent', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('creates a new file with a one-element array when no file exists', async () => {
    const filePath = await appendEvent(baseEvent);
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(1);
    expect(contents[0].event).toBe('session_start');
  });

  it('appends to an existing valid file', async () => {
    await appendEvent(baseEvent);
    await appendEvent({ ...baseEvent, event: 'session_end' });
    const filePath = path.join(tmpDir, 'ai-usage-events.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(2);
    expect(contents[1].event).toBe('session_end');
  });

  it('treats a corrupt file as an empty array and recovers', async () => {
    const filePath = path.join(tmpDir, 'ai-usage-events.json');
    fs.writeFileSync(filePath, 'NOT VALID JSON');
    await appendEvent(baseEvent);
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(1);
  });

  it('returns the full path to the output file', async () => {
    const result = await appendEvent(baseEvent);
    expect(result).toBe(path.join(tmpDir, 'ai-usage-events.json'));
  });
});

// ─── appendHookTypeData ───────────────────────────────────────────────────────

describe('appendHookTypeData', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('creates .hooks-data/<hookType>.json with a one-element array when missing', async () => {
    const filePath = await appendHookTypeData('session', baseEvent);
    expect(filePath).toBe(path.join(tmpDir, '.hooks-data', 'session.json'));
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(1);
    expect(contents[0].event).toBe('session_start');
  });

  it('appends subsequent events for the same hook type', async () => {
    await appendHookTypeData('session', baseEvent);
    await appendHookTypeData('session', { ...baseEvent, event: 'session_end' });
    const filePath = path.join(tmpDir, '.hooks-data', 'session.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(2);
    expect(contents[1].event).toBe('session_end');
  });

  it('keeps separate hook types in separate files', async () => {
    await appendHookTypeData('session', baseEvent);
    await appendHookTypeData('stop', { ...baseEvent, event: 'turn_stop' });
    const sessionContents = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session.json'), 'utf8'),
    );
    const stopContents = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', 'stop.json'), 'utf8'),
    );
    expect(sessionContents).toHaveLength(1);
    expect(stopContents).toHaveLength(1);
  });

  it('treats a corrupt file as an empty array and recovers', async () => {
    fs.mkdirSync(path.join(tmpDir, '.hooks-data'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, '.hooks-data', 'session.json'), 'NOT VALID JSON');
    await appendHookTypeData('session', baseEvent);
    const contents = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session.json'), 'utf8'),
    );
    expect(contents).toHaveLength(1);
  });
});

// ─── appendSessionConsolidatedEvent ────────────────────────────────────────────

describe('appendSessionConsolidatedEvent', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('creates a new session group when none exists', async () => {
    const filePath = await appendSessionConsolidatedEvent(baseEvent);
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toEqual([{ sessionId: 'sess-1', hooks: [baseEvent] }]);
  });

  it('appends a second event to the same existing session group', async () => {
    await appendSessionConsolidatedEvent(baseEvent);
    const second = { ...baseEvent, event: 'user_prompt' as const };
    await appendSessionConsolidatedEvent(second);
    const filePath = path.join(tmpDir, 'hooks-events.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(1);
    expect(contents[0].hooks).toHaveLength(2);
    expect(contents[0].hooks[1].event).toBe('user_prompt');
  });

  it('creates a second group for a different sessionId', async () => {
    await appendSessionConsolidatedEvent(baseEvent);
    await appendSessionConsolidatedEvent({ ...baseEvent, sessionId: 'sess-2' });
    const filePath = path.join(tmpDir, 'hooks-events.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(2);
    expect(contents.map((g: { sessionId: string }) => g.sessionId)).toEqual(['sess-1', 'sess-2']);
  });

  it('falls back to "unknown" when sessionId is null', async () => {
    const filePath = await appendSessionConsolidatedEvent({ ...baseEvent, sessionId: null });
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents[0].sessionId).toBe('unknown');
  });

  it('caps the number of session groups at MAX_SESSION_GROUPS, evicting the oldest first', async () => {
    for (let i = 0; i < MAX_SESSION_GROUPS + 1; i++) {
      await appendSessionConsolidatedEvent({ ...baseEvent, sessionId: `sess-${i}` });
    }
    const filePath = path.join(tmpDir, 'hooks-events.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(MAX_SESSION_GROUPS);
    // The very first session inserted (sess-0) should have aged out first.
    expect(contents.map((g: { sessionId: string }) => g.sessionId)).not.toContain('sess-0');
    expect(contents.map((g: { sessionId: string }) => g.sessionId)).toContain(`sess-${MAX_SESSION_GROUPS}`);
  });

  it('does not evict when appending to an existing session keeps the group count at the cap', async () => {
    for (let i = 0; i < MAX_SESSION_GROUPS; i++) {
      await appendSessionConsolidatedEvent({ ...baseEvent, sessionId: `sess-${i}` });
    }
    // One more event for an already-existing session — group count stays at the cap, no eviction.
    await appendSessionConsolidatedEvent({ ...baseEvent, sessionId: 'sess-0', event: 'user_prompt' });
    const filePath = path.join(tmpDir, 'hooks-events.json');
    const contents = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    expect(contents).toHaveLength(MAX_SESSION_GROUPS);
    expect(contents.map((g: { sessionId: string }) => g.sessionId)).toContain('sess-0');
  });
});

// ─── recordEvent ────────────────────────────────────────────────────────────────

describe('recordEvent', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('writes the flat log, the per-hook-type file, the consolidated file, and the session baseline', async () => {
    await recordEvent('session', baseEvent);

    const flat = JSON.parse(fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'));
    expect(flat).toHaveLength(1);

    const perType = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session.json'), 'utf8'),
    );
    expect(perType).toHaveLength(1);

    const consolidated = JSON.parse(fs.readFileSync(path.join(tmpDir, 'hooks-events.json'), 'utf8'));
    expect(consolidated).toHaveLength(1);
    expect(consolidated[0].sessionId).toBe('sess-1');
    expect(consolidated[0].hooks).toEqual([{ ...baseEvent, eventId: expect.any(String) }]);

    const baselines = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session-baseline.json'), 'utf8'),
    );
    expect(baselines).toEqual([
      { sessionId: 'sess-1', user: 'ashleigh', project: '/dev/project', client: null, gitStartCommit: null },
    ]);
  });

  it('assigns the same eventId to every write target, and a fresh id per call', async () => {
    await recordEvent('session', baseEvent);
    const flat = JSON.parse(fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'));
    const perType = JSON.parse(fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session.json'), 'utf8'));
    const consolidated = JSON.parse(fs.readFileSync(path.join(tmpDir, 'hooks-events.json'), 'utf8'));
    const id = flat[0].eventId;
    expect(id).toEqual(expect.any(String));
    expect(perType[0].eventId).toBe(id);
    expect(consolidated[0].hooks[0].eventId).toBe(id);

    expect(baseEvent).not.toHaveProperty('eventId');

    await recordEvent('session', { ...baseEvent, sessionId: 'sess-2' });
    const flat2 = JSON.parse(fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'));
    expect(flat2[1].eventId).not.toBe(id);
  });

  it('preserves an eventId the caller already set instead of generating a new one', async () => {
    await recordEvent('session', { ...baseEvent, eventId: 'caller-supplied-id' });
    const flat = JSON.parse(fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'));
    expect(flat[0].eventId).toBe('caller-supplied-id');
  });
});

// ─── readSessionBaseline / updateSessionBaseline (via recordEvent) ────────────

describe('readSessionBaseline', () => {
  let tmpDir: string;
  let cwdSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'event-log-test-'));
    cwdSpy = vi.spyOn(process, 'cwd').mockReturnValue(tmpDir);
  });

  afterEach(() => {
    cwdSpy.mockRestore();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns null when sessionId is null', async () => {
    expect(await readSessionBaseline(null)).toBeNull();
  });

  it('returns null when no baseline file exists yet', async () => {
    expect(await readSessionBaseline('sess-1')).toBeNull();
  });

  it('returns the baseline written by recordEvent at session_start', async () => {
    await recordEvent('session', { ...baseEvent, gitStartCommit: 'abc1234' });
    expect(await readSessionBaseline('sess-1')).toEqual({
      sessionId: 'sess-1',
      user: 'ashleigh',
      project: '/dev/project',
      client: null,
      gitStartCommit: 'abc1234',
    });
  });

  it('preserves gitStartCommit across later events that do not carry it', async () => {
    await recordEvent('session', { ...baseEvent, gitStartCommit: 'abc1234' });
    await recordEvent('post-tool-use', { ...baseEvent, event: 'tool_use' });
    const baseline = await readSessionBaseline('sess-1');
    expect(baseline?.gitStartCommit).toBe('abc1234');
  });

  it('updates user/project/client on a later event without duplicating the session entry', async () => {
    await recordEvent('session', baseEvent);
    await recordEvent('post-tool-use', { ...baseEvent, event: 'tool_use', project: 'renamed-project' });
    const baselineFile = path.join(tmpDir, '.hooks-data', 'session-baseline.json');
    const baselines = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
    expect(baselines).toHaveLength(1);
    expect(baselines[0].project).toBe('renamed-project');
  });

  it('caps the number of baseline records at MAX_SESSION_GROUPS, evicting the oldest first', async () => {
    for (let i = 0; i < MAX_SESSION_GROUPS + 1; i++) {
      await recordEvent('session', { ...baseEvent, sessionId: `sess-${i}` });
    }
    const baselineFile = path.join(tmpDir, '.hooks-data', 'session-baseline.json');
    const baselines = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
    expect(baselines).toHaveLength(MAX_SESSION_GROUPS);
    expect(baselines.map((b: { sessionId: string }) => b.sessionId)).not.toContain('sess-0');
    expect(baselines.map((b: { sessionId: string }) => b.sessionId)).toContain(`sess-${MAX_SESSION_GROUPS}`);
  });
});

// ─── pushEventToService ───────────────────────────────────────────────────────

describe('pushEventToService — URL not set', () => {
  let pushEventToService: (event: UsageEvent) => void;

  beforeAll(async () => {
    delete process.env.ANALYTICS_SERVICE_URL;
    vi.resetModules();
    const mod = await import('../lib/event-log.js');
    pushEventToService = mod.pushEventToService;
  });

  afterAll(() => {
    vi.resetModules();
  });

  it('does not call fetch when ANALYTICS_SERVICE_URL is absent', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 202 }));
    pushEventToService(baseEvent);
    await new Promise(r => setTimeout(r, 10));
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});

describe('pushEventToService — URL set', () => {
  let pushEventToService: (event: UsageEvent) => void;

  beforeAll(async () => {
    process.env.ANALYTICS_SERVICE_URL = 'http://test-service:9999';
    vi.resetModules();
    const mod = await import('../lib/event-log.js');
    pushEventToService = mod.pushEventToService;
  });

  afterAll(() => {
    delete process.env.ANALYTICS_SERVICE_URL;
    vi.resetModules();
  });

  it('calls fetch with the correct URL and POST body', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 202 }));
    pushEventToService(baseEvent);
    await new Promise(r => setTimeout(r, 10));
    expect(fetchSpy).toHaveBeenCalledOnce();
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://test-service:9999/events');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toMatchObject({ event: 'session_start' });
    fetchSpy.mockRestore();
  });

  it('swallows fetch errors without throwing', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network error'));
    expect(() => pushEventToService(baseEvent)).not.toThrow();
    await new Promise(r => setTimeout(r, 10));
    fetchSpy.mockRestore();
  });
});

// ─── pushSessionToService ─────────────────────────────────────────────────────

const baseSessionGroup: SessionEventGroup = {
  sessionId: 'sess-1',
  hooks: [baseEvent],
};

describe('pushSessionToService — URL not set', () => {
  let pushSessionToService: (group: SessionEventGroup) => void;

  beforeAll(async () => {
    delete process.env.ANALYTICS_SERVICE_URL;
    vi.resetModules();
    const mod = await import('../lib/event-log.js');
    pushSessionToService = mod.pushSessionToService;
  });

  afterAll(() => {
    vi.resetModules();
  });

  it('does not call fetch when ANALYTICS_SERVICE_URL is absent', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 202 }));
    pushSessionToService(baseSessionGroup);
    await new Promise(r => setTimeout(r, 10));
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});

describe('pushSessionToService — URL set', () => {
  let pushSessionToService: (group: SessionEventGroup) => void;

  beforeAll(async () => {
    process.env.ANALYTICS_SERVICE_URL = 'http://test-service:9999';
    vi.resetModules();
    const mod = await import('../lib/event-log.js');
    pushSessionToService = mod.pushSessionToService;
  });

  afterAll(() => {
    delete process.env.ANALYTICS_SERVICE_URL;
    vi.resetModules();
  });

  it('calls fetch with the correct URL and POST body', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 202 }));
    pushSessionToService(baseSessionGroup);
    await new Promise(r => setTimeout(r, 10));
    expect(fetchSpy).toHaveBeenCalledOnce();
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://test-service:9999/sessions');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ sessionId: 'sess-1', hooks: [baseEvent] });
    fetchSpy.mockRestore();
  });

  it('swallows fetch errors without throwing', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network error'));
    expect(() => pushSessionToService(baseSessionGroup)).not.toThrow();
    await new Promise(r => setTimeout(r, 10));
    fetchSpy.mockRestore();
  });
});
