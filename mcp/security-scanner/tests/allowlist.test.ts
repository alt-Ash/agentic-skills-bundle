import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, writeFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import path from 'path';
import { loadAllowlist, checkTarget, authorizeTarget } from '../src/allowlist.js';

let tmpDir: string;

beforeEach(() => {
  tmpDir = mkdtempSync(path.join(tmpdir(), 'security-scanner-test-'));
});

afterEach(() => {
  delete process.env.SCANNER_ALLOWLIST_PATH;
  rmSync(tmpDir, { recursive: true, force: true });
});

function writeAllowlist(content: string) {
  const file = path.join(tmpDir, 'allowlist.json');
  writeFileSync(file, content, 'utf8');
  process.env.SCANNER_ALLOWLIST_PATH = file;
  return file;
}

describe('loadAllowlist', () => {
  it('fails closed when the config file does not exist', () => {
    process.env.SCANNER_ALLOWLIST_PATH = path.join(tmpDir, 'nope.json');
    const result = loadAllowlist();
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/refuses to scan/i);
  });

  it('fails closed on invalid JSON', () => {
    writeAllowlist('{ not json');
    const result = loadAllowlist();
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/not valid JSON/i);
  });

  it('fails closed when environment is "prod"', () => {
    writeAllowlist(JSON.stringify({ targets: [{ host: 'app.example.com', environment: 'prod' }] }));
    const result = loadAllowlist();
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/refuses to scan production/i);
  });

  it('fails closed when environment is "production"', () => {
    writeAllowlist(JSON.stringify({ targets: [{ host: 'app.example.com', environment: 'production' }] }));
    const result = loadAllowlist();
    expect(result.ok).toBe(false);
  });

  it('loads successfully for valid non-prod environments', () => {
    writeAllowlist(
      JSON.stringify({
        targets: [
          { host: 'localhost:3000', environment: 'local' },
          { host: 'staging.example.com', environment: 'staging' },
        ],
      }),
    );
    const result = loadAllowlist();
    expect(result.ok).toBe(true);
    expect(result.allowlist?.targets).toHaveLength(2);
  });

  it('rejects a config with one bad entry rather than silently dropping it', () => {
    writeAllowlist(
      JSON.stringify({
        targets: [
          { host: 'localhost:3000', environment: 'local' },
          { host: 'prod.example.com', environment: 'prod' },
        ],
      }),
    );
    const result = loadAllowlist();
    expect(result.ok).toBe(false);
  });
});

describe('checkTarget', () => {
  const allowlist = {
    targets: [
      { host: 'localhost:3000', environment: 'local' as const },
      { host: 'Staging.Example.com', environment: 'staging' as const },
    ],
  };

  it('allows an exact host match', () => {
    expect(checkTarget('localhost:3000', allowlist).allowed).toBe(true);
  });

  it('matches case-insensitively', () => {
    const result = checkTarget('staging.example.com', allowlist);
    expect(result.allowed).toBe(true);
    expect(result.environment).toBe('staging');
  });

  it('refuses a host not on the list', () => {
    const result = checkTarget('unknown.example.com', allowlist);
    expect(result.allowed).toBe(false);
    expect(result.reason).toMatch(/not in the allowlist/i);
  });

  it('does not wildcard-match subdomains', () => {
    const result = checkTarget('api.staging.example.com', allowlist);
    expect(result.allowed).toBe(false);
  });
});

describe('authorizeTarget', () => {
  it('surfaces a configError distinct from an allowed:false rejection', () => {
    process.env.SCANNER_ALLOWLIST_PATH = path.join(tmpDir, 'nope.json');
    const result = authorizeTarget('localhost:3000');
    expect(result.allowed).toBe(false);
    expect(result.configError).toBeDefined();
  });

  it('allows a target present in a valid allowlist', () => {
    writeAllowlist(JSON.stringify({ targets: [{ host: 'localhost:3000', environment: 'local' }] }));
    const result = authorizeTarget('localhost:3000');
    expect(result.allowed).toBe(true);
    expect(result.configError).toBeUndefined();
  });
});
