import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'fs';
import { join, dirname, basename } from 'path';
import { fileURLToPath } from 'url';
import matter from 'gray-matter';

const __dirname = dirname(fileURLToPath(import.meta.url));
const AGENTS_DIR = join(__dirname, '../../agents');

const KNOWN_PERMISSION_KEYS = new Set([
  'edit', 'write', 'bash', 'read', 'glob', 'grep', 'webfetch', 'task', 'mcp',
]);
const VALID_PERMISSION_SCALARS = new Set(['allow', 'deny', 'ask']);

function isValidPermissionValue(val: unknown): boolean {
  if (typeof val === 'boolean') return true;
  if (typeof val === 'string') return VALID_PERMISSION_SCALARS.has(val);
  if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
    return Object.values(val as Record<string, unknown>).every(
      v => typeof v === 'string' && VALID_PERMISSION_SCALARS.has(v),
    );
  }
  return false;
}

const agentFiles = readdirSync(AGENTS_DIR)
  .filter(f => f.endsWith('.md'))
  .map(f => ({
    name: f,
    path: join(AGENTS_DIR, f),
    content: readFileSync(join(AGENTS_DIR, f), 'utf-8'),
  }));

describe('agents structural validation', () => {
  it('at least one agent file found', () => {
    expect(agentFiles.length).toBeGreaterThan(0);
  });

  for (const file of agentFiles) {
    describe(file.name, () => {
      const { data, content } = matter(file.content);

      it('frontmatter parses without error', () => {
        expect(() => matter(file.content)).not.toThrow();
      });

      // --- description ---
      it('description is a non-empty string of at least 20 chars', () => {
        expect(typeof data.description).toBe('string');
        expect((data.description as string).length).toBeGreaterThanOrEqual(20);
      });

      // --- mode ---
      it('mode is subagent or primary', () => {
        expect(['subagent', 'primary']).toContain(data.mode);
      });

      // --- temperature ---
      it('temperature is a number between 0 and 1', () => {
        expect(typeof data.temperature).toBe('number');
        expect(data.temperature as number).toBeGreaterThanOrEqual(0);
        expect(data.temperature as number).toBeLessThanOrEqual(1);
      });

      // --- color: valid hex ---
      it('color is a valid hex color (#RGB or #RRGGBB)', () => {
        expect(typeof data.color).toBe('string');
        expect(data.color as string).toMatch(/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/);
      });

      // --- permission: structure ---
      it('permission is a non-null plain object', () => {
        expect(data.permission).toBeDefined();
        expect(typeof data.permission).toBe('object');
        expect(data.permission).not.toBeNull();
        expect(Array.isArray(data.permission)).toBe(false);
      });

      it('permission keys are all from the known set', () => {
        const unknownKeys = Object.keys(data.permission ?? {}).filter(
          k => !KNOWN_PERMISSION_KEYS.has(k),
        );
        expect(unknownKeys, `unknown permission keys: ${unknownKeys.join(', ')}`).toHaveLength(0);
      });

      it('permission values are valid (allow/deny/ask, boolean, or pattern-object)', () => {
        const perm = data.permission ?? {};
        const invalidEntries = Object.entries(perm as Record<string, unknown>).filter(
          ([, v]) => !isValidPermissionValue(v),
        );
        expect(
          invalidEntries,
          `invalid permission values: ${invalidEntries.map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')}`,
        ).toHaveLength(0);
      });

      // --- body headings ---
      it('body has at least 2 ## sections', () => {
        const count = (content.match(/^## /gm) ?? []).length;
        expect(count, 'expected at least 2 ## sections').toBeGreaterThanOrEqual(2);
      });

      // --- output documentation ---
      it('documents its output format', () => {
        const hasHandoff = /\*\*\*HANDOFF BLOCK\*\*\*/.test(content);
        const hasIssueResult = /issue_result:/.test(content);
        const hasImplementResult = /implement_result:/.test(content);
        const hasOutputSection = /^## Output format/m.test(content);
        const hasHandoffSection = /^### Handoff Block/m.test(content);
        expect(
          hasHandoff || hasIssueResult || hasImplementResult || hasOutputSection || hasHandoffSection,
          'agent must document its output via HANDOFF BLOCK, a named YAML result block, ## Output format, or ### Handoff Block',
        ).toBe(true);
      });

      // --- HANDOFF BLOCK integrity ---
      it('HANDOFF BLOCK is properly closed and contains all required subsections', () => {
        if (!/\*\*\*HANDOFF BLOCK\*\*\*/.test(content)) return;

        expect(content, 'HANDOFF BLOCK must have a matching ***END HANDOFF BLOCK***').toMatch(
          /\*\*\*END HANDOFF BLOCK\*\*\*/,
        );

        const block =
          content.match(/\*\*\*HANDOFF BLOCK\*\*\*([\s\S]*?)\*\*\*END HANDOFF BLOCK\*\*\*/)?.[1] ?? '';

        expect(block, 'HANDOFF BLOCK must have Skill/Agent field').toMatch(/Skill\/Agent\s*:/);
        expect(block, 'HANDOFF BLOCK must have Timestamp field').toMatch(/Timestamp\s*:/);
        expect(block, 'HANDOFF BLOCK must have Status field').toMatch(/Status\s*:/);
        expect(block, 'HANDOFF BLOCK must have ### What was done').toMatch(/### What was done/);
        expect(block, 'HANDOFF BLOCK must have ### Artifacts produced').toMatch(/### Artifacts produced/);
        expect(block, 'HANDOFF BLOCK must have ### Checks').toMatch(/### Checks/);
        expect(block, 'HANDOFF BLOCK must have ### Blocked items').toMatch(/### Blocked items/);
        expect(block, 'HANDOFF BLOCK must have ### For the next agent or step').toMatch(
          /### For the next agent or step/,
        );
      });

      // --- name ---
      it('name field matches filename if present', () => {
        if (data.name) {
          expect(data.name).toBe(basename(file.name, '.md'));
        }
      });
    });
  }
});
