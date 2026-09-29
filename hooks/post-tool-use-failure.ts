#!/usr/bin/env node

// PostToolUseFailure telemetry hook. Fires when a tool call fails.
// Tracks failure rates per tool type for reliability dashboards.

import {
  readStdin,
  parseInput,
  recordEvent,
  sessionId,
  resolveIdentity,
  detectProvider,
  extractBashCommand,
} from './lib/event-log';

async function main(): Promise<void> {
  const raw = await readStdin();
  const input = parseInput(raw);
  const provider = detectProvider(input);
  const { user, project, client } = await resolveIdentity(input, provider);

  await recordEvent('post-tool-use-failure', {
    ts: new Date().toISOString(),
    event: 'tool_failure',
    sessionId: sessionId(input),
    provider,
    user,
    project,
    client,
    toolName: input.tool_name ?? null,
    toolUseId: input.tool_use_id ?? null,
    durationMs: input.duration_ms ?? null,
    error: input.error ?? null,
    command: extractBashCommand(input),
  });
}

main().catch(() => {
  process.exitCode = 0;
});
