import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { spawn, execFile } from 'child_process';
import { createServer } from 'http';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);

// ─── Capture HTTP server ──────────────────────────────────────────────────────

let capturedBodies: unknown[] = [];
let captureServer: ReturnType<typeof createServer>;
let capturePort: number;

beforeAll(async () => {
  captureServer = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      try { capturedBodies.push(JSON.parse(body)); } catch { /* ignore */ }
      res.writeHead(202);
      res.end();
    });
  });
  await new Promise<void>((r) =>
    captureServer.listen(0, '127.0.0.1', () => {
      capturePort = (captureServer.address() as { port: number }).port;
      r();
    }),
  );
});

afterAll(() => new Promise<void>((r) => captureServer.close(() => r())));
beforeEach(() => { capturedBodies = []; });

// ─── Helper ───────────────────────────────────────────────────────────────────

async function runHook(
  hookFile: string,
  payload: object,
  extraEnv: Record<string, string> = {},
): Promise<{
  exitCode: number;
  events: unknown[];
  hookTypeEvents: unknown[];
  consolidated: Array<{ sessionId: string; hooks: unknown[] }>;
}> {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hook-test-'));
  const hookPath = path.join(process.cwd(), 'hooks', hookFile);
  const hookKey = hookFile.replace(/\.ts$/, '');
  const child = spawn('npx', ['tsx', hookPath], {
    cwd: tmpDir,
    env: { ...process.env, ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  child.stdin.write(JSON.stringify(payload));
  child.stdin.end();
  const exitCode = await new Promise<number>((r) => child.on('close', r));
  let events: unknown[] = [];
  try {
    events = JSON.parse(
      fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'),
    );
  } catch { /* file may not exist on error paths */ }
  let hookTypeEvents: unknown[] = [];
  try {
    hookTypeEvents = JSON.parse(
      fs.readFileSync(path.join(tmpDir, '.hooks-data', `${hookKey}.json`), 'utf8'),
    );
  } catch { /* file may not exist on error paths */ }
  let consolidated: Array<{ sessionId: string; hooks: unknown[] }> = [];
  try {
    consolidated = JSON.parse(
      fs.readFileSync(path.join(tmpDir, 'hooks-events.json'), 'utf8'),
    );
  } catch { /* file may not exist on error paths */ }
  fs.rmSync(tmpDir, { recursive: true, force: true });
  return { exitCode, events, hookTypeEvents, consolidated };
}

// ─── session.ts ───────────────────────────────────────────────────────────────

describe('session.ts', () => {
  it('writes a session_start event for a SessionStart payload', async () => {
    const { exitCode, events, hookTypeEvents, consolidated } = await runHook('session.ts', {
      hook_event_name: 'SessionStart',
      session_id: 'sess-1',
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    expect(events).toHaveLength(1);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.event).toBe('session_start');
    expect(ev.sessionId).toBe('sess-1');
    expect(ev.provider).toBe('claude');

    expect(hookTypeEvents).toHaveLength(1);
    expect((hookTypeEvents[0] as Record<string, unknown>).event).toBe('session_start');

    expect(consolidated).toHaveLength(1);
    expect(consolidated[0].sessionId).toBe('sess-1');
    expect(consolidated[0].hooks).toHaveLength(1);
  });

  it('also POSTs the event to the capture server when ANALYTICS_SERVICE_URL is set', async () => {
    await runHook(
      'session.ts',
      { hook_event_name: 'SessionStart', session_id: 'sess-http', cwd: '/tmp/proj' },
      { ANALYTICS_SERVICE_URL: `http://127.0.0.1:${capturePort}` },
    );
    // Give the fire-and-forget fetch a moment to arrive
    await new Promise((r) => setTimeout(r, 200));
    expect(capturedBodies).toHaveLength(1);
    const body = capturedBodies[0] as Record<string, unknown>;
    expect(body.event).toBe('session_start');
  });

  it('POSTs the full accumulated session to /sessions on SessionEnd, in addition to the per-event /events POST', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hook-session-flush-'));
    try {
      const extraEnv = { ANALYTICS_SERVICE_URL: `http://127.0.0.1:${capturePort}` };

      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionStart', session_id: 'sess-flush', cwd: tmpDir },
        tmpDir,
        extraEnv,
      );
      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionEnd', session_id: 'sess-flush', reason: 'other', cwd: tmpDir },
        tmpDir,
        extraEnv,
      );

      // Give the fire-and-forget fetches a moment to arrive
      await new Promise((r) => setTimeout(r, 200));

      const eventsPosts = capturedBodies.filter(
        (b) => !(b as Record<string, unknown>).hooks,
      ) as Array<Record<string, unknown>>;
      expect(eventsPosts).toHaveLength(2);
      expect(eventsPosts.map((b) => b.event)).toEqual(['session_start', 'session_end']);

      const sessionPosts = capturedBodies.filter(
        (b) => (b as Record<string, unknown>).hooks,
      ) as Array<{ sessionId: string; hooks: Array<Record<string, unknown>> }>;
      expect(sessionPosts).toHaveLength(1);
      expect(sessionPosts[0].sessionId).toBe('sess-flush');
      expect(sessionPosts[0].hooks.map((h) => h.event)).toEqual(['session_start', 'session_end']);

      expect(eventsPosts.every((b) => typeof b.eventId === 'string')).toBe(true);
      expect(sessionPosts[0].hooks.map((h) => h.eventId)).toEqual(eventsPosts.map((b) => b.eventId));
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('skips the /sessions POST and records a catch-up miss when the local session group was lost', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hook-session-miss-'));
    try {
      const extraEnv = { ANALYTICS_SERVICE_URL: `http://127.0.0.1:${capturePort}` };

      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionStart', session_id: 'sess-miss', cwd: tmpDir },
        tmpDir,
        extraEnv,
      );

      fs.writeFileSync(path.join(tmpDir, 'hooks-events.json'), 'NOT VALID JSON');

      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionEnd', session_id: 'sess-miss', reason: 'other', cwd: tmpDir },
        tmpDir,
        extraEnv,
      );

      await new Promise((r) => setTimeout(r, 200));

      const sessionPosts = capturedBodies.filter((b) => (b as Record<string, unknown>).hooks);
      expect(sessionPosts).toHaveLength(0);

      const missLog = JSON.parse(
        fs.readFileSync(path.join(tmpDir, '.hooks-data', 'session-catchup-miss.json'), 'utf8'),
      );
      expect(missLog).toHaveLength(1);
      expect(missLog[0].sessionId).toBe('sess-miss');
      expect(missLog[0].event).toBe('session_end');

      const consolidated = JSON.parse(fs.readFileSync(path.join(tmpDir, 'hooks-events.json'), 'utf8'));
      const group = consolidated.find((g: { sessionId: string }) => g.sessionId === 'sess-miss');
      expect(group.hooks.map((h: { event: string }) => h.event)).toEqual(['session_end']);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});

// ─── session.ts — git change summary end-to-end ───────────────────────────────

async function spawnHook(
  hookFile: string,
  payload: object,
  cwd: string,
  extraEnv: Record<string, string> = {},
): Promise<number> {
  const hookPath = path.join(process.cwd(), 'hooks', hookFile);
  const child = spawn('npx', ['tsx', hookPath], {
    cwd,
    env: { ...process.env, ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  child.stdin.write(JSON.stringify(payload));
  child.stdin.end();
  return new Promise((r) => child.on('close', r));
}

describe('session.ts — git change summary end-to-end', () => {
  it('captures commits, added/deleted files, and line counts made between session_start and session_end', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hook-git-test-'));
    try {
      await execFileAsync('git', ['init', '-q'], { cwd: tmpDir });
      await execFileAsync('git', ['config', 'user.name', 'Test User'], { cwd: tmpDir });
      await execFileAsync('git', ['config', 'user.email', 'test@example.com'], { cwd: tmpDir });
      fs.writeFileSync(path.join(tmpDir, 'a.txt'), 'hello\n');
      await execFileAsync('git', ['add', '-A'], { cwd: tmpDir });
      await execFileAsync('git', ['commit', '-q', '-m', 'init'], { cwd: tmpDir });

      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionStart', session_id: 'sess-git-1', cwd: tmpDir },
        tmpDir,
      );

      fs.writeFileSync(path.join(tmpDir, 'b.txt'), 'world\n');
      await execFileAsync('git', ['add', '-A'], { cwd: tmpDir });
      await execFileAsync('git', ['commit', '-q', '-m', 'add b.txt'], { cwd: tmpDir });
      fs.writeFileSync(path.join(tmpDir, 'c.txt'), 'uncommitted\n');

      await spawnHook(
        'session.ts',
        { hook_event_name: 'SessionEnd', session_id: 'sess-git-1', reason: 'other', cwd: tmpDir },
        tmpDir,
      );

      const events = JSON.parse(
        fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'),
      ) as Array<Record<string, unknown>>;
      const startEvent = events.find((e) => e.event === 'session_start')!;
      const endEvent = events.find((e) => e.event === 'session_end')!;

      expect(typeof startEvent.gitStartCommit).toBe('string');
      expect(endEvent.gitCommits).toHaveLength(1);
      expect((endEvent.gitCommits as Array<{ message: string }>)[0].message).toBe('add b.txt');
      expect(endEvent.gitFilesAdded).toEqual(expect.arrayContaining(['b.txt', 'c.txt']));
      expect(typeof endEvent.gitLinesAdded).toBe('number');
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});

// ─── user-prompt-submit.ts ────────────────────────────────────────────────────

describe('user-prompt-submit.ts', () => {
  it('writes a user_prompt event with derived prompt metadata but not the prompt text', async () => {
    const { exitCode, events, hookTypeEvents, consolidated } = await runHook('user-prompt-submit.ts', {
      session_id: 'sess-2',
      prompt: 'Hello world',
      permission_mode: 'default',
      prompt_id: 'prompt-abc',
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.event).toBe('user_prompt');
    expect(ev.promptCharLength).toBe(11);
    expect(ev.estimatedInputTokens).toBe(3);
    expect(ev.permissionMode).toBe('default');
    expect(ev.promptId).toBe('prompt-abc');
    expect(ev).not.toHaveProperty('prompt');

    expect((hookTypeEvents[0] as Record<string, unknown>).event).toBe('user_prompt');
    expect(consolidated[0].sessionId).toBe('sess-2');
    expect(consolidated[0].hooks[0]).not.toHaveProperty('prompt');
  });

  it('extracts the slash command name but not the args, for a slash-command prompt', async () => {
    // The raw payload carries the literal typed line, not the expanded
    // <command-name> tag form — see extractSlashCommand's own comment.
    const { exitCode, events } = await runHook('user-prompt-submit.ts', {
      session_id: 'sess-slash',
      prompt: '/plan find gitignore candidates',
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.slashCommand).toBe('/plan');
    expect(JSON.stringify(ev)).not.toContain('find gitignore candidates');
  });

  it('sets slashCommand to null for a plain-text prompt', async () => {
    const { events } = await runHook('user-prompt-submit.ts', {
      session_id: 'sess-plain',
      prompt: 'just a normal message',
      cwd: '/tmp/proj',
    });
    const ev = events[0] as Record<string, unknown>;
    expect(ev.slashCommand).toBeNull();
  });
});

// ─── post-tool-use.ts ─────────────────────────────────────────────────────────

describe('post-tool-use.ts', () => {
  it('writes a tool_use event for a Claude payload without a transcript', async () => {
    const { exitCode, events, hookTypeEvents, consolidated } = await runHook('post-tool-use.ts', {
      session_id: 'sess-3',
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.event).toBe('tool_use');
    expect(ev.provider).toBe('claude');
    expect(ev.model).toBeNull();

    expect((hookTypeEvents[0] as Record<string, unknown>).event).toBe('tool_use');
    expect(consolidated[0].sessionId).toBe('sess-3');
  });

  it('exits 0 and writes an event for an empty payload', async () => {
    const { exitCode, events } = await runHook('post-tool-use.ts', {});
    expect(exitCode).toBe(0);
    expect(events).toHaveLength(1);
  });

  it('captures the command for a Bash tool call', async () => {
    const { events } = await runHook('post-tool-use.ts', {
      session_id: 'sess-bash',
      tool_name: 'Bash',
      tool_input: { command: 'git status' },
      cwd: '/tmp/proj',
    });
    const ev = events[0] as Record<string, unknown>;
    expect(ev.command).toBe('git status');
  });

  it('sets command to null for a non-Bash tool call', async () => {
    const { events } = await runHook('post-tool-use.ts', {
      session_id: 'sess-read',
      tool_name: 'Read',
      tool_input: { file_path: '/tmp/some-file.ts' },
      cwd: '/tmp/proj',
    });
    const ev = events[0] as Record<string, unknown>;
    expect(ev.command).toBeNull();
  });
});

// ─── post-tool-use-failure.ts ─────────────────────────────────────────────────

describe('post-tool-use-failure.ts', () => {
  it('writes a tool_failure event with tool name and error', async () => {
    const { exitCode, events, hookTypeEvents, consolidated } = await runHook('post-tool-use-failure.ts', {
      session_id: 'sess-fail',
      tool_name: 'Bash',
      tool_use_id: 'tu-1',
      error: 'command not found',
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.event).toBe('tool_failure');
    expect(ev.provider).toBe('claude');
    expect(ev.toolName).toBe('Bash');
    expect(ev.error).toBe('command not found');

    expect((hookTypeEvents[0] as Record<string, unknown>).toolName).toBe('Bash');
    expect(consolidated[0].sessionId).toBe('sess-fail');
  });

  it('captures the command for a failed Bash tool call', async () => {
    const { events } = await runHook('post-tool-use-failure.ts', {
      session_id: 'sess-fail-2',
      tool_name: 'Bash',
      tool_input: { command: 'exit 1' },
      error: 'command failed',
      cwd: '/tmp/proj',
    });
    const ev = events[0] as Record<string, unknown>;
    expect(ev.command).toBe('exit 1');
  });
});

// ─── stop.ts ──────────────────────────────────────────────────────────────────

describe('stop.ts', () => {
  it('writes a turn_stop event with char length but not message content', async () => {
    const { exitCode, events, hookTypeEvents, consolidated } = await runHook('stop.ts', {
      session_id: 'sess-stop',
      last_assistant_message: 'Hello world',
      stop_hook_active: false,
      cwd: '/tmp/proj',
    });
    expect(exitCode).toBe(0);
    const ev = events[0] as Record<string, unknown>;
    expect(ev.event).toBe('turn_stop');
    expect(ev.lastMessageCharLength).toBe(11);
    expect(ev.estimatedOutputTokens).toBe(3);
    expect(ev).not.toHaveProperty('last_assistant_message');

    expect(hookTypeEvents).toHaveLength(1);
    expect(consolidated[0].sessionId).toBe('sess-stop');
    expect(consolidated[0].hooks[0]).not.toHaveProperty('last_assistant_message');
  });
});
