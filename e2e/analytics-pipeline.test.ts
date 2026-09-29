/**
 * End-to-end: hooks spawn → analytics-service process → real analytics_test DB
 *
 * Starts the analytics-service as a child process (tsx src/server.ts) on a
 * dedicated test port, then spawns each hook script via tsx with
 * ANALYTICS_SERVICE_URL pointed at it. Asserts rows land in the DB.
 *
 * Requires ANALYTICS_SERVICE_ROOT to point at a checkout of the (separate,
 * not part of this repo) analytics-service. The whole suite is skipped when
 * it's unset.
 */

import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { spawn, type ChildProcess } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as url from 'url';
import type postgres from 'postgres';

const SERVICE_ROOT = process.env.ANALYTICS_SERVICE_ROOT;
const E2E_PORT = 3099;
const E2E_DB = 'postgresql://localhost/analytics_test';
const serviceBaseUrl = `http://127.0.0.1:${E2E_PORT}`;

let serviceProcess: ChildProcess;
let sql: ReturnType<typeof postgres>;

// ─── Service lifecycle ────────────────────────────────────────────────────────

async function waitForHealth(retries = 20, delayMs = 200): Promise<void> {
  for (let i = 0; i < retries; i++) {
    try {
      const res = await fetch(`${serviceBaseUrl}/health`);
      if (res.ok) return;
    } catch { /* not ready yet */ }
    await new Promise(r => setTimeout(r, delayMs));
  }
  throw new Error('analytics-service did not become healthy in time');
}

describe.skipIf(!SERVICE_ROOT)('analytics pipeline e2e', () => {
  beforeAll(async () => {
    const { default: postgres } = await import('postgres');
    sql = postgres(E2E_DB);
    await sql`TRUNCATE events`;

    serviceProcess = spawn('npx', ['tsx', 'src/server.ts'], {
      cwd: SERVICE_ROOT,
      env: { ...process.env, DATABASE_URL: E2E_DB, PORT: String(E2E_PORT), NODE_ENV: 'test' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    await waitForHealth();
  });

  afterAll(async () => {
    serviceProcess?.kill('SIGTERM');
    await sql.end();
  });

  beforeEach(async () => {
    await sql`TRUNCATE events`;
  });

  // ─── Hook runner ──────────────────────────────────────────────────────────────

  const HOOKS_DIR = path.join(
    path.dirname(url.fileURLToPath(import.meta.url)),
    '..',
    'hooks',
  );

  async function runHook(
    hookFile: string,
    payload: object,
  ): Promise<{ exitCode: number; events: unknown[] }> {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-hook-'));
    const hookPath = path.join(HOOKS_DIR, hookFile);
    const child = spawn('npx', ['tsx', hookPath], {
      cwd: tmpDir,
      env: { ...process.env, ANALYTICS_SERVICE_URL: serviceBaseUrl },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    child.stdin.write(JSON.stringify(payload));
    child.stdin.end();
    const exitCode = await new Promise<number>(r => child.on('close', r));
    let events: unknown[] = [];
    try {
      events = JSON.parse(
        fs.readFileSync(path.join(tmpDir, 'ai-usage-events.json'), 'utf8'),
      );
    } catch { /* no file on failure paths */ }
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return { exitCode, events };
  }

  const settle = () => new Promise(r => setTimeout(r, 300));

  // ─── Tests ────────────────────────────────────────────────────────────────────

  describe('session.ts → analytics-service → DB', () => {
    it('inserts a session_start row with correct promoted columns', async () => {
      const { exitCode } = await runHook('session.ts', {
        hook_event_name: 'SessionStart',
        session_id: 'e2e-sess-1',
        cwd: '/tmp/e2e-project',
      });
      expect(exitCode).toBe(0);
      await settle();
      const rows = await sql`SELECT type, session_id, provider, "user" FROM events`;
      expect(rows).toHaveLength(1);
      expect(rows[0].type).toBe('session_start');
      expect(rows[0].session_id).toBe('e2e-sess-1');
      expect(rows[0].provider).toBe('claude');
      expect(rows[0].user).toBeTruthy();
    });

    it('inserts a session_end row', async () => {
      await runHook('session.ts', {
        hook_event_name: 'SessionEnd',
        session_id: 'e2e-sess-end',
        cwd: '/tmp/e2e-project',
      });
      await settle();
      const [row] = await sql`SELECT type FROM events WHERE session_id = 'e2e-sess-end'`;
      expect(row.type).toBe('session_end');
    });
  });

  describe('user-prompt-submit.ts → analytics-service → DB', () => {
    it('inserts a user_prompt row with the char length but never the raw prompt text', async () => {
      const { exitCode } = await runHook('user-prompt-submit.ts', {
        session_id: 'e2e-prompt-1',
        prompt: 'What is the capital of France?',
        cwd: '/tmp/e2e-project',
      });
      expect(exitCode).toBe(0);
      await settle();
      const [row] = await sql`SELECT type, data FROM events`;
      expect(row.type).toBe('user_prompt');
      expect(row.data.promptCharLength).toBe('What is the capital of France?'.length);
      expect(row.data).not.toHaveProperty('prompt');
    });
  });

  describe('post-tool-use.ts → analytics-service → DB', () => {
    it('inserts a tool_use row for a Claude payload', async () => {
      const { exitCode } = await runHook('post-tool-use.ts', {
        session_id: 'e2e-tool-1',
        cwd: '/tmp/e2e-project',
      });
      expect(exitCode).toBe(0);
      await settle();
      const [row] = await sql`SELECT type, provider FROM events`;
      expect(row.type).toBe('tool_use');
      expect(row.provider).toBe('claude');
    });

    it('inserts a tool_use row for a Gemini payload with fixture transcript', async () => {
      const fixturePath = path.join(HOOKS_DIR, 'tests', 'fixtures', 'gemini-transcript.jsonl');
      const { exitCode } = await runHook('post-tool-use.ts', {
        hook_event_name: 'AfterTool',
        session_id: 'e2e-gemini-1',
        transcript_path: fixturePath,
        cwd: '/tmp/e2e-project',
      });
      expect(exitCode).toBe(0);
      await settle();
      const [row] = await sql`SELECT type, provider, data FROM events`;
      expect(row.type).toBe('tool_use');
      expect(row.provider).toBe('gemini');
      expect(row.data.model).toBe('gemini-1.5-pro');
    });
  });

  describe('fire-and-forget guarantee', () => {
    it('hook exits 0 and completes well within the 5s timeout', async () => {
      const start = Date.now();
      const { exitCode } = await runHook('session.ts', {
        hook_event_name: 'SessionStart',
        session_id: 'e2e-fast',
        cwd: '/tmp/e2e-project',
      });
      expect(exitCode).toBe(0);
      expect(Date.now() - start).toBeLessThan(4000);
    });
  });
});
