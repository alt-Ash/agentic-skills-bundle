import { describe, it, expect } from 'vitest';
import {
  extractFromClaudeTranscript,
  extractFromGeminiTranscript,
  extractFromCodexRollout,
} from '../post-tool-use.js';

const fix = (name: string) =>
  new URL(`./fixtures/${name}`, import.meta.url).pathname;

describe('extractFromClaudeTranscript', () => {
  it('extracts model and tokens from a valid fixture', async () => {
    const result = await extractFromClaudeTranscript(fix('claude-transcript.jsonl'));
    expect(result.model).toBe('claude-opus-4-5');
    expect(result.inputTokens).toBe(150);
    expect(result.cachedTokens).toBe(60); // 50 + 10
  });

  it('returns {} without throwing for a non-existent path', async () => {
    const result = await extractFromClaudeTranscript('/no/such/file.jsonl');
    expect(result).toEqual({});
  });
});

describe('extractFromGeminiTranscript', () => {
  it('extracts model and tokens from a valid fixture', async () => {
    const result = await extractFromGeminiTranscript(fix('gemini-transcript.jsonl'));
    expect(result.model).toBe('gemini-1.5-pro');
    expect(result.inputTokens).toBe(200);
    expect(result.cachedTokens).toBe(30);
  });

  it('returns {} without throwing for a non-existent path', async () => {
    const result = await extractFromGeminiTranscript('/no/such/file.jsonl');
    expect(result).toEqual({});
  });
});

describe('extractFromCodexRollout', () => {
  it('extracts model and tokens from a valid fixture', async () => {
    const result = await extractFromCodexRollout(fix('codex-rollout.jsonl'));
    expect(result.model).toBe('codex-mini');
    expect(result.inputTokens).toBe(300);
    expect(result.cachedTokens).toBe(50);
  });

  it('returns {} without throwing for a non-existent path', async () => {
    const result = await extractFromCodexRollout('/no/such/file.jsonl');
    expect(result).toEqual({});
  });
});

