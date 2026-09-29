import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { glob } from 'glob';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import matter from 'gray-matter';

const __dirname = dirname(fileURLToPath(import.meta.url));
const COMMANDS_DIR = join(__dirname, '../../.opencode/commands');

// Frontmatter keys a command file is allowed to carry. `.opencode/commands/*.md`
// is the single source of truth installed to every tool with `supportsCommands`
// (currently OpenCode and Claude Code) — unknown keys must be ones every
// supported tool tolerates, not opencode-only fields that could break another
// tool's command parser.
const KNOWN_KEYS = new Set(['description', 'subtask']);

const commandFiles = await glob('*.md', { cwd: COMMANDS_DIR, absolute: true });

describe('commands structural validation', () => {
  it('at least one command file found', () => {
    expect(commandFiles.length).toBeGreaterThan(0);
  });

  for (const filePath of commandFiles) {
    const relPath = filePath.replace(COMMANDS_DIR + '/', '');
    const raw = readFileSync(filePath, 'utf-8');
    const { data, content } = matter(raw);

    describe(relPath, () => {
      it('is non-empty', () => {
        expect(raw.trim().length).toBeGreaterThan(0);
      });

      it('frontmatter has required field: description', () => {
        expect(typeof data.description).toBe('string');
        expect((data.description as string).length).toBeGreaterThan(0);
      });

      it('has no unknown frontmatter keys', () => {
        const unknown = Object.keys(data).filter((k) => !KNOWN_KEYS.has(k));
        expect(unknown, `unexpected frontmatter keys: ${unknown.join(', ')}`).toEqual([]);
      });

      it('has a non-empty body', () => {
        expect(content.trim().length).toBeGreaterThan(0);
      });

      it('has no hardcoded absolute paths', () => {
        expect(raw).not.toMatch(/\/Users\/|\/home\/[a-z]|C:\\Users\\/);
      });
    });
  }
});
