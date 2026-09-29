// Shared infrastructure for all telemetry hooks.
//
// Every hook (post-tool-use, user-prompt-submit, session) builds a self-describing
// `UsageEvent` and appends it to ONE JSON-array file (`ai-usage-events.json`) in the
// CLI's working directory. Records share a common envelope and a `sessionId` join
// key so the downstream telemetry endpoint can stitch a whole session back together
// (session_start → user_prompt → tool_use → session_end).
//
// esbuild `--bundle` inlines this module into each hook's standalone `.js`, so the
// hooks stay single-file at runtime while sharing one source of truth here.

import { promises as fs } from 'fs';
import { join, basename } from 'path';
import * as os from 'os';
import { execFile } from 'child_process';
import { promisify } from 'util';
import { randomUUID } from 'crypto';

const execFileAsync = promisify(execFile);

export type Provider = 'claude' | 'gemini' | 'cursor' | 'codex' | 'copilot';

export type EventKind = 'session_start' | 'session_end' | 'user_prompt' | 'tool_use' | 'tool_failure' | 'turn_stop';

// Raw hook payload from stdin. Shape varies per CLI and per event; every field is
// optional and accessed defensively.
export interface HookInput {
  hook_event_name?: string;
  transcript_path?: string | null;
  session_id?: string;
  conversation_id?: string;
  cwd?: string;
  model?: string;
  user_email?: string | null;
  source?: string;
  reason?: string;
  prompt?: string;
  permission_mode?: string;
  prompt_id?: string;
  tool_name?: string;
  tool_use_id?: string;
  tool_input?: Record<string, unknown>;
  error?: string;
  duration_ms?: number;
  stop_hook_active?: boolean;
  last_assistant_message?: string;
  background_tasks?: unknown[];
  [key: string]: unknown;
}

// Unified, self-describing record. Absent values are JSON `null` (never a magic
// string) so the file is a clean POST body for the telemetry endpoint.
export interface UsageEvent {
  eventId?: string;
  ts: string;
  event: EventKind;
  sessionId: string | null;
  provider: Provider;
  user: string;
  project: string | null;
  client?: string | null;
  source?: string | null;
  reason?: string | null;
  model?: string | null;
  inputTokens?: number | null;
  cachedTokens?: number | null;
  toolName?: string | null;
  toolUseId?: string | null;
  error?: string | null;
  durationMs?: number | null;
  stopHookActive?: boolean | null;
  lastMessageCharLength?: number | null;
  estimatedOutputTokens?: number | null;
  backgroundTaskCount?: number | null;
  promptCharLength?: number | null;
  estimatedInputTokens?: number | null;
  permissionMode?: string | null;
  promptId?: string | null;
  gitStartCommit?: string | null;
  gitCommits?: GitCommitInfo[] | null;
  gitFilesAdded?: string[] | null;
  gitFilesModified?: string[] | null;
  gitFilesDeleted?: string[] | null;
  gitLinesAdded?: number | null;
  gitLinesDeleted?: number | null;
  command?: string | null;
  slashCommand?: string | null;
}

export interface GitCommitInfo {
  hash: string;
  message: string;
}

const OUTPUT_FILE = 'ai-usage-events.json';
const HOOKS_DATA_DIR = '.hooks-data';
const CONSOLIDATED_FILE = 'hooks-events.json';
const SESSION_BASELINE_FILE = 'session-baseline.json';

export async function readStdin(): Promise<string> {
  let data = '';
  for await (const chunk of process.stdin) {
    data += chunk;
  }
  return data;
}

export function parseInput(raw: string): HookInput {
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

// Check environment variable first (set during hook registration for Copilot/VSCode).
// Then try payload-based detection. Claude sends no `model` on its hook payloads; Gemini
// is flagged by its event name; Cursor/Codex both carry `model` (Cursor additionally carries
// user_email / conversation_id). Best-effort — unknown shapes fall through to 'claude'.
export function detectProvider(input: HookInput): Provider {
  // First: check if provider was explicitly set via environment (Copilot case)
  if (process.env.AI_PROVIDER && ['claude', 'gemini', 'cursor', 'codex', 'copilot'].includes(process.env.AI_PROVIDER)) {
    return process.env.AI_PROVIDER as Provider;
  }
  
  // Then: try payload-based detection
  if (input.hook_event_name === 'AfterTool') return 'gemini';
  if (input.model && (input.user_email !== undefined || input.conversation_id)) return 'cursor';
  if (input.model) return 'codex';
  return 'claude';
}

const MAX_COMMAND_LENGTH = 2000;

// Best-effort secret redaction for captured Bash commands — NOT a guarantee,
// just a meaningful reduction in blast radius. Shell commands are exactly
// where credentials routinely appear in plaintext (curl auth headers, env
// var exports, connection strings, cloud CLI flags), and this is the only
// field in the whole telemetry system that stores raw content rather than a
// length/metadata — so it gets redacted before it's ever assigned to the
// event, meaning both the local JSON files AND anything POSTed to
// ANALYTICS_SERVICE_URL only ever see the sanitized version.
type Replacer = string | ((...args: string[]) => string);

const SENSITIVE_NAME = /TOKEN|SECRET|PASSWORD|PASSWD|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|CREDENTIAL/i;

const REDACTION_RULES: Array<[RegExp, Replacer]> = [
  // Basic auth embedded in a URL: scheme://user:pass@host
  [/(:\/\/)([^/\s:@]+):([^/\s@]+)@/g, '$1[REDACTED]@'],
  // Bearer tokens (Authorization headers, etc.) — [^\s"']+ (not \S+) so a
  // trailing quote from `"Bearer <token>"` isn't swallowed into the match.
  [/(Bearer\s+)[^\s"']+/gi, '$1[REDACTED]'],
  // GitHub personal access / app tokens
  [/\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, '[REDACTED]'],
  // AWS access key IDs
  [/\bAKIA[0-9A-Z]{16}\b/g, '[REDACTED]'],
  // OpenAI/Anthropic-style secret keys (sk-..., sk-ant-...)
  [/\bsk-[A-Za-z0-9_-]{20,}\b/g, '[REDACTED]'],
  // JWTs (always start with the base64 of `{"`, i.e. "eyJ")
  [/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*/g, '[REDACTED]'],
  // curl-style `-u user:pass`
  [/(-u\s+)[^\s:"']+:[^\s"']+/g, '$1[REDACTED]'],
  // Sensitive env var assignments: TOKEN=, SECRET=, PASSWORD=, API_KEY=, etc.
  // Deliberately NOT `[A-Za-z0-9_]*(?:TOKEN|SECRET|...)[A-Za-z0-9_]*` — that
  // "wildcard around an alternation" shape is catastrophically slow to
  // backtrack when the input contains many repeats of a keyword with no
  // trailing `=` to complete the match (confirmed empirically: ~10s for a
  // 250KB string of repeated "TOKEN"). Instead, match any plain
  // `identifier=value` first (fast, unambiguous — no alternation inside the
  // wildcard) and only check the captured name against the keyword list
  // afterwards, as a separate, cheap string test.
  [/\b([A-Za-z_][A-Za-z0-9_]*)(\s*=\s*)([^\s"']+)/g, (match, name, eq) =>
    SENSITIVE_NAME.test(name) ? `${name}${eq}[REDACTED]` : match],
  // --password / --token / --api-key / --secret / --auth style CLI flags
  [/(--?(?:password|token|api[_-]?key|secret|auth)(?:=|\s+))[^\s"']+/gi, '$1[REDACTED]'],
  // PEM private key blocks
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, '[REDACTED PRIVATE KEY]'],
];

export function redactSecrets(command: string): string {
  let result = command;
  for (const [pattern, replacement] of REDACTION_RULES) {
    result = typeof replacement === 'string'
      ? result.replace(pattern, replacement)
      : result.replace(pattern, replacement);
  }
  if (result.length > MAX_COMMAND_LENGTH) {
    result = result.slice(0, MAX_COMMAND_LENGTH) + '...[truncated]';
  }
  return result;
}

// The Bash tool's `tool_input` is `{ command: "..." }`; every other tool's
// input shape is unrelated (file_path, pattern, etc.) so there's no
// meaningful "command" to extract from it — this only ever returns non-null
// for Bash, deliberately narrower than capturing all tool_input (which would
// include full file contents/diffs for Edit/Write). The value is redacted
// before it's ever returned — see redactSecrets above.
export function extractBashCommand(input: HookInput): string | null {
  if (input.tool_name !== 'Bash') return null;
  const command = input.tool_input?.command;
  return typeof command === 'string' ? redactSecrets(command) : null;
}

// Slash command invocations arrive on the raw UserPromptSubmit payload as the
// literal typed line — "/command optional args" — not the expanded
// <command-name>/foo</command-name> tag form that shows up later in the
// transcript (that's Claude Code's own post-processing representation, added
// after this hook already fired; confirmed by cross-checking promptCharLength
// against the transcript: it matches the raw line's length, not the tagged
// form's). Only the command name is extracted, never anything after it —
// `<command-args>`-equivalent free text is deliberately left unread since it
// can carry arbitrary/sensitive user text.
//
// Requires whitespace-or-end immediately after the name so an absolute path
// like "/Users/..." is never mistaken for a slash command.
export function extractSlashCommand(prompt: string | undefined): string | null {
  if (typeof prompt !== 'string') return null;
  const raw = prompt.match(/^\/([a-zA-Z0-9][a-zA-Z0-9_-]*)(?=\s|$)/);
  if (raw) return `/${raw[1]}`;
  // Fallback in case some provider/future version instead delivers the
  // expanded tag form directly on the raw payload.
  const tagged = prompt.match(/<command-name>\s*([^<]+?)\s*<\/command-name>/);
  return tagged ? tagged[1] : null;
}

export function osUser(): string {
  try {
    return os.userInfo().username;
  } catch {
    return 'unknown';
  }
}

export function sessionId(input: HookInput): string | null {
  return input.session_id ?? input.conversation_id ?? null;
}

const GIT_TIMEOUT_MS = 1500;

// Runs a git plumbing command against the hook's actual working directory
// (never the payload's declared `cwd`, which tests/some CLIs may fake).
// Best-effort: never throws — returns null on any failure (no repo, no git,
// timeout, detached HEAD with no commits, etc).
async function runGit(args: string[]): Promise<string | null> {
  try {
    const { stdout } = await execFileAsync('git', args, {
      cwd: process.cwd(),
      timeout: GIT_TIMEOUT_MS,
    });
    // Only strip the trailing newline — `stdout.trim()` would also eat
    // leading whitespace off the very first line, which corrupts the
    // fixed-column `git status --porcelain` / `git diff --name-status`
    // format when the first entry's status code starts with a space
    // (e.g. " M .gitignore" silently losing its leading space, then its
    // filename losing its leading dot to a now-misaligned slice offset).
    const trimmed = stdout.replace(/[\r\n]+$/, '');
    return trimmed || null;
  } catch {
    return null;
  }
}

interface ParsedRemote {
  owner: string | null;
  repo: string | null;
}

// Splits both URL forms git uses for a remote into owner/repo:
//   https://github.com/owner/repo.git   ->  owner=owner, repo=repo
//   git@github.com:owner/repo.git       ->  owner=owner, repo=repo
function parseRemoteUrl(remote: string): ParsedRemote {
  const cleaned = remote.replace(/\.git$/, '');
  const match = cleaned.match(/[/:]([^/:]+)\/([^/]+)$/);
  if (!match) return { owner: null, repo: null };
  return { owner: match[1], repo: match[2] };
}

async function gitRemoteOriginInfo(): Promise<ParsedRemote> {
  const remote = await runGit(['remote', 'get-url', 'origin']);
  return remote ? parseRemoteUrl(remote) : { owner: null, repo: null };
}

// Canonical repo name, preferred from the `origin` remote (stable even if the
// local clone folder is renamed); falls back to the git toplevel folder name.
// Accepts an already-resolved `remoteInfo` to avoid a redundant `git remote
// get-url origin` call when the caller (resolveIdentity) already has one.
export async function gitRepoName(remoteInfo?: ParsedRemote): Promise<string | null> {
  const { repo } = remoteInfo ?? (await gitRemoteOriginInfo());
  if (repo) return repo;
  const toplevel = await runGit(['rev-parse', '--show-toplevel']);
  return toplevel ? basename(toplevel) : null;
}

// The org/user that owns the repo on its git host (e.g. "acme-corp" for
// github.com/acme-corp/some-repo) — the raw slug from the remote
// URL, not a prettified display name. Null when there's no `origin` remote.
// Accepts an already-resolved `remoteInfo` for the same reason as gitRepoName.
export async function gitClientName(remoteInfo?: ParsedRemote): Promise<string | null> {
  const { owner } = remoteInfo ?? (await gitRemoteOriginInfo());
  return owner;
}

export async function gitUserName(): Promise<string | null> {
  return runGit(['config', 'user.name']);
}

export async function gitHeadSha(): Promise<string | null> {
  return runGit(['rev-parse', 'HEAD']);
}

// Project name only — never the full path. Prefers the git repo name so it
// stays stable across machines/clone locations; falls back to the last path
// segment of the declared cwd.
export async function resolveProject(input: HookInput, remoteInfo?: ParsedRemote): Promise<string> {
  const cwd = typeof input.cwd === 'string' && input.cwd ? input.cwd : process.cwd();
  return (await gitRepoName(remoteInfo)) ?? basename(cwd);
}

// Cursor already reports a user_email on its payload; everyone else is
// identified from git config user.name (falls back to the OS account).
export async function resolveUser(input: HookInput, provider: Provider): Promise<string> {
  if (provider === 'cursor' && input.user_email) return input.user_email;
  return (await gitUserName()) ?? osUser();
}

export interface ResolvedIdentity {
  user: string;
  project: string;
  client: string | null;
}

// Resolves user/project/client once per session, reading from the tiny
// per-session baseline record (see readSessionBaseline/updateSessionBaseline
// below) instead of the potentially-huge consolidated per-session log —
// that record never grows with the session's activity, so this stays cheap
// for the entire duration of even a very long-running session.
export async function resolveIdentity(input: HookInput, provider: Provider): Promise<ResolvedIdentity> {
  const baseline = await readSessionBaseline(sessionId(input));
  // client is intentionally not checked for truthiness — null is a valid,
  // legitimate value (no origin remote). user/project are checked as a
  // defensive guard against a malformed/corrupted record on disk.
  if (baseline?.user && baseline?.project) {
    return { user: baseline.user, project: baseline.project, client: baseline.client };
  }
  // Fetch the remote info once and hand it to both resolveProject and
  // gitClientName — they'd otherwise each independently spawn their own
  // `git remote get-url origin` when run concurrently below.
  const remoteInfo = await gitRemoteOriginInfo();
  const [user, project, client] = await Promise.all([
    resolveUser(input, provider),
    resolveProject(input, remoteInfo),
    gitClientName(remoteInfo),
  ]);
  return { user, project, client };
}

export async function readJsonlLines(path: string): Promise<unknown[]> {
  const content = await fs.readFile(path, 'utf8');
  const records: unknown[] = [];
  for (const line of content.trim().split('\n')) {
    if (!line.trim()) continue;
    try {
      records.push(JSON.parse(line));
    } catch {
      // skip malformed lines
    }
  }
  return records;
}

async function readExistingEvents(path: string): Promise<unknown[]> {
  try {
    const parsed = JSON.parse(await fs.readFile(path, 'utf8'));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // Missing file, unreadable, or corrupt JSON — start fresh.
    return [];
  }
}

const ANALYTICS_SERVICE_URL = process.env.ANALYTICS_SERVICE_URL;

export function pushEventToService(event: UsageEvent): void {
  if (!ANALYTICS_SERVICE_URL) return;
  fetch(`${ANALYTICS_SERVICE_URL}/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(event),
    signal: AbortSignal.timeout(5000),
  }).catch(() => {
    // intentionally silent — service being down must not affect hooks
  });
}

export function pushSessionToService(group: SessionEventGroup): void {
  if (!ANALYTICS_SERVICE_URL) return;
  fetch(`${ANALYTICS_SERVICE_URL}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(group),
    signal: AbortSignal.timeout(5000),
  }).catch(() => {
    // intentionally silent — service being down must not affect hooks
  });
}

// Read-modify-write append into the single JSON-array log. Tolerant of a
// missing/corrupt file (→ starts from []). Never throws — the host CLI must not
// break because telemetry failed.
export async function appendEvent(event: UsageEvent): Promise<string> {
  const outputPath = join(process.cwd(), OUTPUT_FILE);
  const events = await readExistingEvents(outputPath);
  events.push(event);
  await fs.writeFile(outputPath, JSON.stringify(events, null, 2));
  pushEventToService(event);
  return outputPath;
}

// One array per hook type under `.hooks-data/<hookType>.json`, so a single
// hook's raw output can be inspected without filtering the combined log.
export async function appendHookTypeData(hookType: string, event: UsageEvent): Promise<string> {
  const dir = join(process.cwd(), HOOKS_DATA_DIR);
  await fs.mkdir(dir, { recursive: true });
  const outputPath = join(dir, `${hookType}.json`);
  const events = await readExistingEvents(outputPath);
  events.push(event);
  await fs.writeFile(outputPath, JSON.stringify(events, null, 2));
  return outputPath;
}

export interface SessionEventGroup {
  sessionId: string;
  hooks: UsageEvent[];
}

// Without a cap, hooks-events.json would grow forever as more sessions
// accumulate over a project's lifetime. Bounding the number of sessions
// tracked keeps it flat regardless of history. Note this bounds the number
// of *sessions*, not events within one — a single very long-running session's
// own `hooks` array still grows unbounded; that's fine because nothing
// performance-sensitive reads this file anymore (see readSessionBaseline).
// 50 is generous headroom over realistic concurrent session counts for a
// single project directory.
export const MAX_SESSION_GROUPS = 50;

// Groups every event by sessionId into `hooks-events.json`, so a whole
// session's hook activity (start → prompts → tool calls → end) reads as one
// object instead of scattered rows in the flat log. This is a debug/analytics
// view only — nothing reads it back for identity-cache or gitStartCommit
// lookups (see readSessionBaseline), so its per-session growth doesn't affect
// hook latency.
export async function appendSessionConsolidatedEvent(event: UsageEvent): Promise<string> {
  const outputPath = join(process.cwd(), CONSOLIDATED_FILE);
  const groups = (await readExistingEvents(outputPath)) as SessionEventGroup[];
  const key = event.sessionId ?? 'unknown';
  const group = groups.find((g) => g.sessionId === key);
  if (group) {
    group.hooks.push(event);
  } else {
    groups.push({ sessionId: key, hooks: [event] });
  }
  // Evict the oldest (by insertion order) session groups once over the cap.
  while (groups.length > MAX_SESSION_GROUPS) {
    groups.shift();
  }
  await fs.writeFile(outputPath, JSON.stringify(groups, null, 2));
  return outputPath;
}

export interface SessionBaseline {
  sessionId: string;
  user: string;
  project: string;
  client: string | null;
  gitStartCommit: string | null;
}

// A tiny, fixed-size-per-session record — unlike hooks-events.json's
// per-session `hooks` array, this never grows with the session's activity.
// It's what keeps resolveIdentity's cache and session_end's gitStartCommit
// lookup cheap for the entire duration of even a very long-running session
// (previously both read/parsed the whole ever-growing consolidated log).
async function readSessionBaselines(): Promise<SessionBaseline[]> {
  const outputPath = join(process.cwd(), HOOKS_DATA_DIR, SESSION_BASELINE_FILE);
  return (await readExistingEvents(outputPath)) as SessionBaseline[];
}

export async function readSessionBaseline(id: string | null): Promise<SessionBaseline | null> {
  if (!id) return null;
  const baselines = await readSessionBaselines();
  return baselines.find((b) => b.sessionId === id) ?? null;
}

// Refreshes (or creates) a session's baseline from whatever event was just
// recorded. `gitStartCommit` is only ever present on session_start events —
// every other event kind carries it forward from the existing baseline so a
// later update doesn't erase it.
async function updateSessionBaseline(event: UsageEvent): Promise<void> {
  if (!event.sessionId) return;
  const dir = join(process.cwd(), HOOKS_DATA_DIR);
  await fs.mkdir(dir, { recursive: true });
  const outputPath = join(dir, SESSION_BASELINE_FILE);
  const baselines = await readSessionBaselines();
  const idx = baselines.findIndex((b) => b.sessionId === event.sessionId);
  const next: SessionBaseline = {
    sessionId: event.sessionId,
    user: event.user,
    project: event.project ?? '',
    client: event.client ?? null,
    gitStartCommit: event.gitStartCommit ?? baselines[idx]?.gitStartCommit ?? null,
  };
  if (idx >= 0) {
    baselines[idx] = next;
  } else {
    baselines.push(next);
  }
  while (baselines.length > MAX_SESSION_GROUPS) {
    baselines.shift();
  }
  await fs.writeFile(outputPath, JSON.stringify(baselines, null, 2));
}

// Fan-out used by every hook entrypoint: the flat analytics log, the
// per-hook-type debug capture, the session-consolidated view, and the
// session baseline cache all derive from the same event object.
export async function recordEvent(hookType: string, event: UsageEvent): Promise<void> {
  const enriched: UsageEvent = { ...event, eventId: event.eventId ?? randomUUID() };
  await Promise.all([
    appendEvent(enriched),
    appendHookTypeData(hookType, enriched),
    appendSessionConsolidatedEvent(enriched),
    updateSessionBaseline(enriched),
  ]);
}

// Looks up a session's prior hook events from the consolidated log. Not used
// for the identity cache or gitStartCommit lookup anymore (see
// readSessionBaseline) — kept as a general-purpose read for debugging/future use.
export async function readSessionGroup(id: string | null): Promise<UsageEvent[]> {
  if (!id) return [];
  const outputPath = join(process.cwd(), CONSOLIDATED_FILE);
  const groups = (await readExistingEvents(outputPath)) as SessionEventGroup[];
  return groups.find((g) => g.sessionId === id)?.hooks ?? [];
}

export interface GitChangeSummary {
  commits: GitCommitInfo[];
  filesAdded: string[];
  filesModified: string[];
  filesDeleted: string[];
  linesAdded: number | null;
  linesDeleted: number | null;
}

function addUnique(list: string[], value: string | undefined): void {
  if (value && !list.includes(value)) list.push(value);
}

// `git diff --name-status` lines: "A\tpath", "D\tpath", "M\tpath",
// "R100\told\tnew" (renames/copies use the last column, the new path).
function parseNameStatus(output: string, into: GitChangeSummary): void {
  for (const line of output.split('\n')) {
    if (!line.trim()) continue;
    const columns = line.split('\t');
    const status = columns[0];
    const filePath = columns[columns.length - 1];
    if (status.startsWith('A')) addUnique(into.filesAdded, filePath);
    else if (status.startsWith('D')) addUnique(into.filesDeleted, filePath);
    else addUnique(into.filesModified, filePath);
  }
}

// `git status --porcelain` lines: 2-char XY status code + path (renames use
// "old -> new"; `??` marks an untracked/new file).
function parsePorcelainStatus(output: string, into: GitChangeSummary): void {
  for (const line of output.split('\n')) {
    if (!line) continue;
    const code = line.slice(0, 2);
    const rest = line.slice(3);
    const filePath = rest.includes(' -> ') ? rest.split(' -> ')[1] : rest;
    if (code.includes('D')) addUnique(into.filesDeleted, filePath);
    else if (code === '??' || code.includes('A')) addUnique(into.filesAdded, filePath);
    else addUnique(into.filesModified, filePath);
  }
}

// Summarizes everything that changed during a session: commits made since
// `startSha` (recorded at session_start), plus the files those commits
// touched, plus whatever is still uncommitted in the working tree right now.
// Best-effort — returns an all-empty summary outside a git repo.
export async function gitSessionChangeSummary(startSha: string | null): Promise<GitChangeSummary> {
  const summary: GitChangeSummary = {
    commits: [],
    filesAdded: [],
    filesModified: [],
    filesDeleted: [],
    linesAdded: null,
    linesDeleted: null,
  };

  const headSha = await gitHeadSha();
  if (startSha && headSha && startSha !== headSha) {
    const log = await runGit(['log', `${startSha}..${headSha}`, '--format=%h%x1f%s']);
    if (log) {
      for (const line of log.split('\n')) {
        if (!line.trim()) continue;
        const [hash, message] = line.split('\x1f');
        summary.commits.push({ hash, message: message ?? '' });
      }
    }

    const diff = await runGit(['diff', '--name-status', startSha, headSha]);
    if (diff) parseNameStatus(diff, summary);

    const shortstat = await runGit(['diff', '--shortstat', startSha, headSha]);
    if (shortstat) {
      const insertions = shortstat.match(/(\d+) insertion/);
      const deletions = shortstat.match(/(\d+) deletion/);
      summary.linesAdded = insertions ? parseInt(insertions[1], 10) : 0;
      summary.linesDeleted = deletions ? parseInt(deletions[1], 10) : 0;
    }
  }

  const status = await runGit(['status', '--porcelain']);
  if (status) parsePorcelainStatus(status, summary);

  return summary;
}
