#!/usr/bin/env node

import {
  readStdin,
  parseInput,
  recordEvent,
  sessionId,
  resolveIdentity,
  detectProvider,
  extractBashCommand,
  readJsonlLines,
  type Provider,
  type HookInput,
} from './lib/event-log.js';

interface UsageRecord {
  provider: Provider;
  model: string;
  inputTokens: number | null;
  cachedTokens: number | null;
}

// Claude Code transcript: per-event JSONL with `type` and a nested `message`.
export async function extractFromClaudeTranscript(path: string): Promise<Partial<UsageRecord>> {
  const out: Partial<UsageRecord> = {};
  let records: unknown[];
  try {
    records = await readJsonlLines(path);
  } catch {
    return out;
  }
  for (const record of records) {
    const obj = record as any;
    if (obj?.type === 'assistant' && obj.message) {
      if (obj.message.model) out.model = obj.message.model;
      const usage = obj.message.usage;
      if (usage && typeof usage.input_tokens === 'number') {
        out.inputTokens = usage.input_tokens;
        const cacheRead = usage.cache_read_input_tokens ?? 0;
        const cacheCreate = usage.cache_creation_input_tokens ?? 0;
        out.cachedTokens = cacheRead + cacheCreate;
      }
    }
  }
  return out;
}

// Gemini CLI transcript: Gemini-specific records (`type: "gemini" | "user"`),
// optionally wrapped in a ConversationRecord with a `messages[]` array.
export async function extractFromGeminiTranscript(path: string): Promise<Partial<UsageRecord>> {
  const out: Partial<UsageRecord> = {};
  let records: unknown[];
  try {
    records = await readJsonlLines(path);
  } catch {
    return out;
  }

  // Flatten any ConversationRecord wrappers into a single record stream.
  const flat: any[] = [];
  for (const record of records) {
    const obj = record as any;
    if (obj && Array.isArray(obj.messages)) {
      if (obj.model && !out.model) out.model = obj.model;
      flat.push(...obj.messages);
    } else {
      flat.push(obj);
    }
  }

  for (const obj of flat) {
    if (obj?.type === 'gemini') {
      if (obj.model) out.model = obj.model;
      const tokens = obj.tokens;
      if (tokens && typeof tokens.input === 'number') {
        out.inputTokens = tokens.input;
        out.cachedTokens = typeof tokens.cached === 'number' ? tokens.cached : null;
      }
    }
  }
  return out;
}

// Codex rollout JSONL: RolloutItems discriminated by `type`.
export async function extractFromCodexRollout(path: string): Promise<Partial<UsageRecord>> {
  const out: Partial<UsageRecord> = {};
  let records: unknown[];
  try {
    records = await readJsonlLines(path);
  } catch {
    return out;
  }
  for (const record of records) {
    const obj = record as any;
    if (obj?.type === 'TurnContext' && obj.model) {
      out.model = obj.model;
    }
    if (obj?.type === 'TokenCount') {
      // TokenUsage may sit on the record or a nested `usage`/`info` object.
      const usage = obj.usage ?? obj.info ?? obj;
      if (usage && typeof usage.input_tokens === 'number') {
        // Codex `input_tokens` already INCLUDES cached; do not sum.
        out.inputTokens = usage.input_tokens;
        out.cachedTokens = typeof usage.cached_input_tokens === 'number'
          ? usage.cached_input_tokens
          : null;
      }
    }
  }
  return out;
}

export async function buildRecord(input: HookInput): Promise<UsageRecord> {
  const provider = detectProvider(input);
  const transcript = typeof input.transcript_path === 'string' ? input.transcript_path : null;

  let extracted: Partial<UsageRecord> = {};
  if (provider === 'claude' && transcript) {
    extracted = await extractFromClaudeTranscript(transcript);
  } else if (provider === 'gemini' && transcript) {
    extracted = await extractFromGeminiTranscript(transcript);
  } else if (provider === 'codex') {
    extracted = transcript ? await extractFromCodexRollout(transcript) : {};
    if (input.model) extracted.model = input.model;
  } else if (provider === 'cursor') {
    extracted.model = input.model;
    // tokens intentionally left null — Cursor hooks do not expose them.
  }

  return {
    provider,
    model: extracted.model ?? 'unavailable',
    inputTokens: extracted.inputTokens ?? null,
    cachedTokens: extracted.cachedTokens ?? null,
  };
}

async function main(): Promise<void> {
  const raw = await readStdin();
  const input = parseInput(raw);
  const [record, { user, project, client }] = await Promise.all([
    buildRecord(input),
    resolveIdentity(input, detectProvider(input)),
  ]);

  await recordEvent('post-tool-use', {
    ts: new Date().toISOString(),
    event: 'tool_use',
    sessionId: sessionId(input),
    provider: record.provider,
    user,
    project,
    client,
    model: record.model !== 'unavailable' ? record.model : null,
    inputTokens: record.inputTokens,
    cachedTokens: record.cachedTokens,
    command: extractBashCommand(input),
  });
}

main().catch(() => {
  process.exitCode = 0;
});
