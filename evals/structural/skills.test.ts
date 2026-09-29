import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { glob } from 'glob';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import matter from 'gray-matter';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SKILLS_DIR = join(__dirname, '../../skills');

// Only `<category>/<skill>/SKILL.md` is independently installable (discoverSkills()
// in bin/install.js recurses exactly one level under skills/<category>/); anything
// nested deeper (e.g. framework-migrations/<name>/SKILL.md) is a bundled reference
// sub-guide for a parent skill, not a standalone skill.
const skillFiles = await glob('*/*/SKILL.md', { cwd: SKILLS_DIR, absolute: true });

describe('skills structural validation', () => {
  it('at least one SKILL.md found', () => {
    expect(skillFiles.length).toBeGreaterThan(0);
  });

  for (const filePath of skillFiles) {
    const relPath = filePath.replace(SKILLS_DIR + '/', '');
    const raw = readFileSync(filePath, 'utf-8');
    const { data, content } = matter(raw);

    describe(relPath, () => {
      it('is non-empty', () => {
        expect(raw.trim().length).toBeGreaterThan(0);
      });

      // --- frontmatter ---
      it('frontmatter has required field: name', () => {
        expect(typeof data.name).toBe('string');
        expect((data.name as string).length).toBeGreaterThan(0);
      });

      it('frontmatter has required field: description', () => {
        expect(typeof data.description).toBe('string');
        expect((data.description as string).length).toBeGreaterThan(0);
      });

      it('version follows semver if present', () => {
        if (data.version !== undefined) {
          expect(String(data.version)).toMatch(/^\d+\.\d+\.\d+$/);
        }
      });

      // --- body ---
      it('has at least 2 ## sections', () => {
        const count = (content.match(/^## /gm) ?? []).length;
        expect(count, 'expected at least 2 ## sections').toBeGreaterThanOrEqual(2);
      });

      it('has no hardcoded absolute paths', () => {
        expect(raw).not.toMatch(/\/Users\/|\/home\/[a-z]|C:\\Users\\/);
      });

      // --- CONTEXT BLOCK required ---
      it('has ***CONTEXT BLOCK*** template', () => {
        expect(raw, 'skill must define a ***CONTEXT BLOCK*** template').toMatch(
          /\*\*\*CONTEXT BLOCK\*\*\*/,
        );
      });

      // --- CONTEXT BLOCK integrity ---
      it('CONTEXT BLOCK is properly closed', () => {
        if (!/\*\*\*CONTEXT BLOCK\*\*\*/.test(raw)) return;
        expect(raw, 'CONTEXT BLOCK must have a matching ***END CONTEXT BLOCK***').toMatch(
          /\*\*\*END CONTEXT BLOCK\*\*\*/,
        );
      });

      // --- HANDOFF BLOCK required ---
      it('has ***HANDOFF BLOCK*** template', () => {
        expect(raw, 'skill must define a ***HANDOFF BLOCK*** template').toMatch(
          /\*\*\*HANDOFF BLOCK\*\*\*/,
        );
      });

      // --- HANDOFF BLOCK integrity ---
      it('HANDOFF BLOCK is properly closed and contains all required subsections', () => {
        if (!/\*\*\*HANDOFF BLOCK\*\*\*/.test(raw)) return;

        expect(raw, 'HANDOFF BLOCK must have a matching ***END HANDOFF BLOCK***').toMatch(
          /\*\*\*END HANDOFF BLOCK\*\*\*/,
        );

        const block =
          raw.match(/\*\*\*HANDOFF BLOCK\*\*\*([\s\S]*?)\*\*\*END HANDOFF BLOCK\*\*\*/)?.[1] ?? '';

        expect(block, 'HANDOFF BLOCK must have Skill or Skill/Agent field').toMatch(
          /Skill\/Agent\s*:|Skill\s*:/,
        );
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
    });
  }
});
