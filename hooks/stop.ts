#!/usr/bin/env node

// Stop telemetry hook. Fires at the end of every turn after the assistant
// finishes responding. Records output size as a character-length proxy —
// the message content itself is not stored.

import {
  readStdin,
  parseInput,
  recordEvent,
  sessionId,
  resolveIdentity,
  detectProvider,
} from './lib/event-log';

async function main(): Promise<void> {
  const raw = await readStdin();
  const input = parseInput(raw);
  const provider = detectProvider(input);

  const msgLength = typeof input.last_assistant_message === 'string'
    ? input.last_assistant_message.length
    : 0;

  const { user, project, client } = await resolveIdentity(input, provider);

  await recordEvent('stop', {
    ts: new Date().toISOString(),
    event: 'turn_stop',
    sessionId: sessionId(input),
    provider,
    user,
    project,
    client,
    stopHookActive: input.stop_hook_active ?? null,
    lastMessageCharLength: msgLength,
    estimatedOutputTokens: Math.round(msgLength / 4),
    backgroundTaskCount: Array.isArray(input.background_tasks)
      ? input.background_tasks.length
      : 0,
  });
}

main().catch(() => {
  process.exitCode = 0;
});
