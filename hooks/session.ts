#!/usr/bin/env node

// Session lifecycle telemetry hook. One source registered against both
// SessionStart and SessionEnd; the `hook_event_name` discriminates which.
//   SessionStart → emits `source`  (startup | resume | clear | compact)
//   SessionEnd   → emits `reason`  (clear | logout | prompt_input_exit | other)
// Both carry session_id + cwd, giving the denominator for every usage metric:
// sessions/user/day, duration (start↔end via sessionId), active hours, project.

import {
  HookInput,
  UsageEvent,
  EventKind,
  readStdin,
  parseInput,
  detectProvider,
  sessionId,
  resolveIdentity,
  recordEvent,
  gitHeadSha,
  gitSessionChangeSummary,
  readSessionBaseline,
  readSessionGroup,
  pushSessionToService,
  appendHookTypeData,
} from './lib/event-log';

// Maps each CLI's start/end event names to our normalized kind. Claude uses
// SessionStart/SessionEnd; other CLIs are added here as their event keys are
// confirmed. Unknown names default to a start event.
function eventKind(input: HookInput): EventKind {
  const name = (input.hook_event_name ?? '').toLowerCase();
  if (name.includes('end') || name.includes('stop')) return 'session_end';
  return 'session_start';
}

async function buildEvent(input: HookInput): Promise<UsageEvent> {
  const provider = detectProvider(input);
  const kind = eventKind(input);
  const { user, project, client } = await resolveIdentity(input, provider);

  const event: UsageEvent = {
    ts: new Date().toISOString(),
    event: kind,
    sessionId: sessionId(input),
    provider,
    user,
    project,
    client,
    source: kind === 'session_start' ? (input.source ?? null) : undefined,
    reason: kind === 'session_end' ? (input.reason ?? null) : undefined,
  };

  if (kind === 'session_start') {
    event.gitStartCommit = await gitHeadSha();
  }

  // Recover the commit sha recorded at session_start (from the tiny session
  // baseline record) so we can diff "what changed this session" against it.
  if (kind === 'session_end') {
    const baseline = await readSessionBaseline(sessionId(input));
    const summary = await gitSessionChangeSummary(baseline?.gitStartCommit ?? null);
    event.gitCommits = summary.commits;
    event.gitFilesAdded = summary.filesAdded;
    event.gitFilesModified = summary.filesModified;
    event.gitFilesDeleted = summary.filesDeleted;
    event.gitLinesAdded = summary.linesAdded;
    event.gitLinesDeleted = summary.linesDeleted;
  }

  return event;
}

async function main(): Promise<void> {
  const raw = await readStdin();
  const input = parseInput(raw);
  const event = await buildEvent(input);
  await recordEvent('session', event);

  if (event.event === 'session_end') {
    const id = sessionId(input);
    if (id) {
      const hooks = await readSessionGroup(id);
      const hasSessionStart = hooks.some((h) => h.event === 'session_start');
      if (!hasSessionStart) {
        await appendHookTypeData('session-catchup-miss', event);
      } else {
        pushSessionToService({ sessionId: id, hooks });
      }
    }
  }
}

main().catch(() => {
  process.exitCode = 0;
});
