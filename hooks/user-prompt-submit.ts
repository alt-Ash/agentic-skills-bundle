#!/usr/bin/env node

// UserPromptSubmit telemetry hook. Fires every time a user submits a prompt,
// BEFORE the model runs — capturing intent even for prompts that never trigger a
// tool. The prompt text arrives directly on the stdin payload as `prompt`
// (no transcript scrape needed) but is never stored — only its length, matching
// stop.ts's treatment of `last_assistant_message`. The one exception is a
// slash command's name (e.g. "/code-review"), extracted from the prompt via
// extractSlashCommand — `<command-args>` is deliberately left unread since it
// can carry arbitrary free-form user text.

import {
  HookInput,
  UsageEvent,
  readStdin,
  parseInput,
  detectProvider,
  sessionId,
  resolveIdentity,
  extractSlashCommand,
  recordEvent,
} from './lib/event-log';

async function buildEvent(input: HookInput): Promise<UsageEvent> {
  const provider = detectProvider(input);
  const { user, project, client } = await resolveIdentity(input, provider);

  const promptCharLength = typeof input.prompt === 'string' ? input.prompt.length : null;

  return {
    ts: new Date().toISOString(),
    event: 'user_prompt',
    sessionId: sessionId(input),
    provider,
    user,
    project,
    client,
    promptCharLength,
    estimatedInputTokens: promptCharLength !== null ? Math.round(promptCharLength / 4) : null,
    permissionMode: input.permission_mode ?? null,
    promptId: input.prompt_id ?? null,
    slashCommand: extractSlashCommand(input.prompt),
  };
}

async function main(): Promise<void> {
  const raw = await readStdin();
  const input = parseInput(raw);
  await recordEvent('user-prompt-submit', await buildEvent(input));
}

main().catch(() => {
  process.exitCode = 0;
});
