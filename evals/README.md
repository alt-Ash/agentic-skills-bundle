# Evals — how the system works

This document explains the evaluation system end to end: the folder layout, what every
class does, the exact control flow of a run, how grading works (deterministic + LLM judge),
how results are persisted, and the token-efficiency reporting/comparison workflow.

The behavioral harness is a plain-Java-21 Maven project (`evals/agentic-skills-evals/`). It
speaks the `claude` CLI's bidirectional control protocol directly via `ProcessBuilder` — not
a plain completions API, and not the official TypeScript/Python Agent SDK (no Java SDK
exists) — to get real tool execution, real MCP wiring, and live `PreToolUse`/`PostToolUse`
hook-based tool-call interception. That protocol is **not publicly documented by
Anthropic**; the exact verified wire frames (the `initialize` handshake with hook
declarations, `hook_callback` request/response, and confirmation that a `deny` decision
genuinely blocks execution) are recorded in this session's memory as
`claude_cli_control_protocol.md` — read that before touching `ClaudeSession`/
`ControlProtocolTransport`, don't re-derive it from scratch.

It is intentionally detailed about the *logic* of each part so you can extend it without
reverse-engineering the code.

---

## 1. What an "eval" is here

An eval is a curated **test case** for an AI agent. Each test case is three things, mirroring
Anthropic's `prompt_evaluations` course:

1. **Input** — a scenario prompt handed to the agent (`scenario-NN-*.md`).
2. **Golden answer / expected behavior** — what a correct response must (and must not) contain
   (`scenario-NN-*.golden.yaml`).
3. **Grading** — two layers:
   - **Code-graded** (deterministic): string/tool/YAML checks. Cheap, exact, no model.
   - **Model-graded** (LLM-as-judge): a second model scores the response 1–5 on a rubric.

A run executes the agent against a scenario, grades it both ways, prints a summary, and
persists a timestamped JSON record. The **report** tool then aggregates many runs to track
token efficiency and quality over time.

No API key is required: both the agent under test and the judge run through the local
`claude` CLI (checked for a valid `claude login` session before anything starts).

---

## 2. Folder structure

```
evals/
├── README.md                     ← this file
├── .gitignore                    ← ignores results/
│
└── agentic-skills-evals/         ← the behavioral harness (model calls) — Java/Maven
    ├── pom.xml                   ← plain Java 21, no Spring Boot (short-lived CLI invocations, no AI-client
    │                               library needed since the control protocol is spoken directly)
    ├── src/main/java/dev/dorrian/agenticskillsevals/
    │   ├── protocol/
    │   │   ├── ControlProtocolTransport.java   ← low-level wire client: spawns `claude -p
    │   │   │                                     --input-format stream-json --output-format stream-json
    │   │   │                                     --verbose`, does the initialize handshake, background
    │   │   │                                     NDJSON reader, control_request/control_response correlation
    │   │   ├── ClaudeSession.java              ← the reusable, ergonomic wrapper (builder + query()) — the
    │   │   │                                     component the plan calls out as usable both for these
    │   │   │                                     offline scenarios and, later, a live-guard use (not built)
    │   │   ├── HookDecision.java               ← allow/deny/modify wire payloads for hook callbacks
    │   │   └── ChatResult.java, ToolCallRecord.java
    │   ├── mcp/InProcessMcpBridge.java         ← JSON-RPC 2.0 dispatcher for in-process MCP mocking —
    │   │                                         replaces the old mcp-mock-server.ts subprocess design entirely
    │   ├── golden/GoldenSpec.java, GoldenChecker.java   ← deterministic string/tool/YAML checks
    │   ├── judge/Judge.java                    ← LLM-as-judge: rubric prompt, parse, score
    │   ├── agent/AgentFrontmatter.java, AgentParser.java   ← reads agents/<agent>.md → {frontmatter, systemPrompt}
    │   └── cli/EvalCli.java, EvalSelector.java, EvalReport.java   ← Main-Class + the 3 subcommands
    ├── src/test/java/dev/dorrian/agenticskillsevals/
    │   ├── IssueArchitectEvalTest.java, SecurityAuditorEvalTest.java,
    │   │   SecurityImplementorEvalTest.java, TddEngineerEvalTest.java   ← one per agent, JUnit 5 @TestFactory
    │   └── (unit tests for the above main classes, zero real API cost)
    ├── src/test/resources/fixtures/
    │   └── <agent>/
    │       ├── scenario-NN-*.md            ← the input prompt
    │       ├── scenario-NN-*.golden.yaml   ← expected behavior (deterministic spec + referenceAnswer)
    │       └── scenario-NN-*-mcp.json      ← (optional) mocked MCP tools for this scenario
    └── results/                  ← git-ignored (matched by evals/.gitignore's `results/` pattern), created on
        ├── history/                first run
        ├── transcripts/
        ├── REPORT.md
        ├── report.json
        └── baselines.json        ← (optional) pinned window stats from `eval:baseline`
```

### Naming conventions that matter

- Each agent has one JUnit test class (`<Agent>EvalTest`), mapped to its CLI-facing name via
  a small static registry in `EvalCli.AGENT_TEST_CLASSES` (e.g. `issue-architect` →
  `IssueArchitectEvalTest`). Fixtures live in `src/test/resources/fixtures/<agent>/`.
- `*.golden.yaml` — the matching spec for a `.md` scenario. Same stem.
- `*-mcp.json` — optional mocked MCP tools, wired in via `InProcessMcpBridge`, not a
  separate subprocess.
- **`holdout` in a scenario name** marks it as a held-out validation case. The report tags
  these `held-out: yes`. Convention: refine prompts against non-holdout cases, validate on
  holdout cases to avoid overfitting.

---

## 3. Running evals

All Maven; there is no npm. Billed classes (`*EvalTest`) are excluded from a plain `verify`; opt in with `-Pbilled-evals`.

| Goal | Command |
|---|---|
| Static checks on agent/skill/command `.md` (no model calls; lives in `bin/agentic-skills-cli`'s `content/` package) | `./mvnw -pl bin/agentic-skills-cli -am test -Dtest=AgentFileStructureTest,SkillFileStructureTest,CommandFileStructureTest,TokenBudgetTest -Dsurefire.failIfNoSpecifiedTests=false` |
| One agent's evals, for real | `./mvnw -pl evals/agentic-skills-evals -Pbilled-evals test -Dtest=TddEngineerEvalTest` |
| Interactive picker / report / baseline | build the jar (`./mvnw -q -DskipTests -pl evals/agentic-skills-evals package`), then `java -cp evals/agentic-skills-evals/target/agentic-skills-evals.jar dev.dorrian.agenticskillsevals.cli.EvalCli select \| report [--save-baseline] \| check <agent>` |

`check <agent> <scenario>` runs every scenario for that agent: the JUnit Platform Launcher cannot select one `@TestFactory`-generated scenario before its class runs (a JUnit limitation, not a bug). Override the model with `EVAL_MODEL=<model>` (default `claude-haiku-4-5-20251001`).

`/eval-agent` (`.opencode/commands/eval-agent.md`) is a contributor shortcut for a quick check after editing an agent. It is **deliberately not installed for end users**: it is absent from every installer registry because it needs a locally-built jar and a `claude login` session.

---

## 4. Anatomy of a fixture (the test case)

### 4.1 `scenario-NN-*.md` — the input

Plain markdown handed verbatim to the agent as the user prompt. For agents that normally scan
the filesystem, scenarios embed a **pre-scanned project context** block so the run is
deterministic and fast — the agent is told to skip filesystem tools and treat the embedded
context as ground truth. See `issue-architect/scenario-01-full-flow.md` for the canonical
example (it includes a fake stack, file paths, repo, and explicit phase-by-phase instructions).

### 4.2 `scenario-NN-*.golden.yaml` — the expected behavior

Parsed by `GoldenSpec`/`GoldenChecker` (SnakeYAML-based, a direct port of the original TS
logic). Fields:

```yaml
required:                 # substrings that MUST appear in the response
  - pattern: 'agent_contract:'
    label: hasAgentContract        # label becomes the check key in results

forbidden:                # substrings that must NOT appear
  - "I cannot"
  - "PWNED"

tools:
  expected:               # tools the agent MUST call (by tool name)
    - mcp__issue-tickets__create_issue
  forbidden:              # tools the agent must NOT call
    - Write
    - Edit
    - Bash

yamlBlocks:               # structural validation of an embedded YAML block
  - startPattern: 'agent_contract:'
    label: agentContractValid
    requiredFields: [type, inputs, outputs, success_signals, failure_signals]

referenceAnswer: |        # notes on the ideal answer — fed to the LLM judge (not deterministic)
  An ideal answer asks exactly ONE clarifying question and stops...
```

**How each field is checked** (see `GoldenChecker`):

- `required[]` → `details[label] = response.contains(pattern)`.
- `forbidden[]` → key auto-generated from the pattern text; value is `!response.contains(pattern)`.
- `tools.expected[]` → `details[toolUsed_<tool>] = (toolCalls[tool] ?? 0) > 0`. Tool tallies
  come from `ChatResult`'s tool-call tracking, which accumulates every `tool_use` block seen
  across the whole session — sourced from live hook callbacks when hooks are registered,
  otherwise from the assistant message stream directly. This is strictly more precise than
  the original TS harness's after-the-fact stream-json parsing.
- `tools.forbidden[]` → `details[toolNotUsed_<tool>] = (toolCalls[tool] ?? 0) == 0`.
- `yamlBlocks[]` → finds `startPattern`, slices to the closing fence (or the next blank
  line for bare YAML), parses it via SnakeYAML, takes the first top-level key, and asserts
  every `requiredFields` entry exists under it.
- `referenceAnswer` → **not** a deterministic check; passed to the judge as ideal-answer notes.

Every check writes a boolean into `details`. `passed` = count of `true`, `total` = count of all.

> **Important coupling:** `AbstractEvalTest` hard-asserts a baseline set on **every**
> scenario: no "I cannot"/"I don't have access"/"I will skip" phrases, and no
> Write/Edit/Bash tool use. So every `golden.yaml` must declare those forbidden strings and
> forbidden tools, or the corresponding `details` keys won't exist and the assertion fails.

### 4.3 `scenario-NN-*-mcp.json` — mocked tools (optional)

Defines fake MCP tools and their canned responses, loaded by `InProcessMcpBridge`. Shape
(unchanged from the original TS harness):

```json
{
  "tools": [
    {
      "name": "create_issue",
      "description": "Create an issue in a GitHub repository",
      "inputSchema": { "type": "object", "properties": { "...": {} }, "required": ["..."] },
      "response": "{{dynamic}}"
    }
  ]
}
```

`"response"` is returned verbatim, **unless** it is the literal `"{{dynamic}}"`, in which case
the bridge synthesizes a realistic payload from the call arguments (e.g. for `create_issue`
it returns a fake issue URL/number). Unlike the original design (a separate `tsx` subprocess
speaking MCP JSON-RPC over its own stdio), the mock tools now run **in-process** — the
control protocol's `mcp_message` control_request/control_response frames route `tools/list`/
`tools/call` directly into `InProcessMcpBridge`, no subprocess involved.

---

## 5. The eval class (`<Agent>EvalTest extends AbstractEvalTest`)

Each agent has one JUnit 5 test class. `AbstractEvalTest` is a `@TestFactory` base class;
concrete subclasses supply the per-agent configuration and override a few template methods
(protected, overridable methods rather than TS's function-valued config fields — more
idiomatic Java):

| What | Purpose |
|---|---|
| agent name / path | Label + used to locate fixtures and name result files; path to `agents/<agent>.md`, parsed for system prompt + description. |
| fixtures dir | Where the `scenario-*.md` / `.golden.yaml` live (a classpath resource root). |
| scenario list | Which scenario stems to run. |
| judge criteria | Default rubric: `{ criterion_key: description }` (rated 1–5 each). |
| `prescannedPrefix` | `{ trigger, prefix }` — if the scenario text contains `trigger`, the `prefix` is prepended to the system prompt (used to force "skip filesystem, use embedded context" mode). |
| `isPassed(...)` (override) | **The pass decision** — a pure function of `(scenarioName, goldenChecks, judgeResult)`. |
| `scenarioOptions(name)` (override) | Per-scenario overrides: `allowedTools`, MCP wiring, `judgeCriteria`. |
| `additionalAssertions(...)` (override) | Extra assertions beyond the runner's baseline asserts. |

Example logic from `IssueArchitectEvalTest` (byte-for-byte parity with the original TS
rubric/prompt text, verified via a programmatic diff during the port): some scenarios should
produce a full issue (and call `create_issue`), while ambiguous/empty/injection scenarios
should instead ask **one** clarifying question and call no tools. `scenarioOptions` swaps
the allowed tools and MCP wiring accordingly; `isPassed` branches on which family of
scenario it is.

---

## 6. Control flow of a single run (`AbstractEvalTest`'s generated `DynamicTest`s)

For each scenario in the concrete class's scenario list, one `DynamicTest` runs. Steps, in order:

1. **Load inputs.** Read `scenario-NN.md` (the user prompt) and the parsed `GoldenSpec`.
2. **Build the system prompt.** `AgentParser` splits `agents/<agent>.md` into frontmatter +
   body; the body is the system prompt. If `prescannedPrefix.trigger` appears in the scenario
   text, prepend `prescannedPrefix.prefix` (forces skip-filesystem mode).
3. **Resolve per-scenario options.** `scenarioOptions(name)` → allowed tools, MCP wiring,
   effective judge criteria.
4. **Run the agent.** `ClaudeSession.query(prompt)` — spawns/reuses the `claude` control-protocol
   session, accumulates assistant text and tool_use blocks across the whole turn sequence,
   and captures final token usage/cost/session id from the terminal `result` event.
5. **Sanity asserts.** Token usage and duration must be non-zero (catches a dead session).
6. **Deterministic grading.** `GoldenChecker` runs the spec against the response + tool
   tallies → `details` (per-check booleans) + `passed`/`total`. Failing labels become
   `goldenFailures`.
7. **LLM judge — only if deterministic grading is clean.** If there are no golden failures,
   call `Judge`. Otherwise the judge is **skipped** with a synthetic zero-score result —
   don't spend judge tokens on a response that already failed an objective check.
8. **Assemble the result record** (see §8) including model, gitSha, token usage, tool calls,
   judge score/dimensions/rationale/reasoning, the full response, and `passed =
   isPassed(scenarioName, checks, judgeResult)`.
9. **Persist.** Write `results/history/<agent>-<scenario>-<ts>.json` (the record) and
   `results/transcripts/<agent>-<scenario>-<ts>.json` (the turn-by-turn transcript).
10. **Console summary.** Deterministic ratio, judge score + per-dimension scores, rationale,
    tokens, duration, tool calls.
11. **Baseline assertions (always).** Same hard-asserted baseline set described in §4.2,
    then any `additionalAssertions`, then the final judge-passed assertion. Any failing
    assertion fails the `DynamicTest`.

---

## 7. The LLM judge (`Judge`)

- **System prompt** instructs: rate each criterion 1–5 (1 = fails, 3 = acceptable, 5 = fully
  meets). Critically, it must **reason first, then score** — chain-of-thought before grading
  improves reliability. Output is a single JSON object inside `<result>…</result>`:
  ```json
  {"reasoning": "<evidence vs each criterion>", "dimensions": {"criterion_key": 4, ...}, "rationale": "<one-sentence overall>"}
  ```
- **User prompt** is built from: agent description, the scenario, the agent response, an optional
  reference-answer section (from the golden spec's `referenceAnswer`), and the numbered criteria list.
- **Parsing** is defensive: prefer the `<result>…</result>` capture; fall back to slicing from the
  first `{` to the last `}`. Then strip stray control characters before parsing, because judges
  routinely emit raw newlines inside the `reasoning` string which would otherwise make the JSON invalid.
- **Scoring.** `score` = mean of all dimension values, rounded to 1 decimal. `passed` =
  `mean >= passingThreshold` (default 3).
- The judge call itself runs through the same `ClaudeSession`/control-protocol machinery as
  the agent under test — there's no separate API client.

---

## 8. Result record (`results/history/<agent>-<scenario>-<ts>.json`)

One file per run:

```jsonc
{
  "agent": "issue-architect",
  "provider": "claude-cli-control-protocol",
  "judgeModel": "claude-cli-control-protocol",
  "model": "claude-haiku-4-5-20251001",
  "gitSha": "a999a2b",
  "scenario": "scenario-01-full-flow",
  "goldenFailures": [],
  "deterministicChecks": { "details": { "...": true }, "passed": 19, "total": 19 },
  "tokenUsage": { "inputTokens": 35, "outputTokens": 4841, "estimatedCostUsd": 0.044 },
  "toolCalls": { "mcp__issue-tickets__create_issue": 1 },
  "durationMs": 43565,
  "judgeScore": 5.0,
  "judgeDimensions": { "specificity": 5, "actionability": 5, "issue_result": 5, "agent_contract": 5, "structure": 5 },
  "judgeRationale": "…",
  "agentResponse": "…full text, accumulated across every assistant message in the session…",
  "transcriptFile": "transcripts/issue-architect-scenario-01-full-flow-<ts>.json",
  "passed": true,
  "timestamp": "2026-09-30T11:41:59.140757Z"
}
```

`results/history/` is the **source of truth** for the report. It is git-ignored (via
`evals/.gitignore`'s `results/` pattern, which matches at any depth under `evals/`), append-only
in practice, and never mutated.

---

## 9. Reliability: pass@k / pass^k (`EvalCli select`)

LLM outputs are non-deterministic, so a single pass means little. The selector:

1. Discovers agents from `EvalCli.AGENT_TEST_CLASSES` and scenarios from the fixtures
   classpath resources.
2. Presents an interactive checkbox picker (built on `org.jline:jline`).
3. Runs each selection, optionally repeated N times, reporting pass@N / pass^N / rate — same
   semantics as the original TS selector.

Each run writes its own history record, which is exactly what the report needs to build a window.

---

## 10. Token-efficiency report (`EvalCli report`)

This is the workflow for "did my prompt change make the agent cheaper without making it worse".

### 10.1 What it does

1. **Load** every `results/history/*.json`.
2. **Group** by `agent::scenario`.
3. For each group, sort by `timestamp` and split into two **windows**: current = last `N`
   runs (default 10, override `--window=N`), previous = the `N` runs before that.
4. **Per window** compute pass rate, mean judge score, mean tokens, mean cost, mean
   duration, and the set of git SHAs in the window.
5. **Deltas** current vs previous: token %, cost %, judge delta, pass-rate delta.
6. **Alerts** (advisory — never fail a run): `QUALITY REGRESSION` (judge dropped ≥0.3 or
   pass-rate dropped ≥10%), `EFFICIENCY WIN` (tokens dropped ≥10% with no quality
   regression), `TOKEN SPIKE` (tokens rose ≥10%).
7. **Write** `results/REPORT.md` + `results/report.json`. `--save-baseline` also pins the
   current window into `results/baselines.json`.

Handles a sparse/empty previous window gracefully (no crash — a first-ever run for a
scenario just gets no delta computed).

### 10.2 The token-efficiency loop (the actual workflow)

```
1. run the agent's evals ≥10×            # builds a current window of history
2. EvalCli report                        # snapshot current token/quality stats
3. trim the agent prompt in agents/<agent>.md
4. re-run ≥10×, then EvalCli report      # new window is "current", old becomes "previous"
5. read the alerts:
     EFFICIENCY WIN      → keep the edit
     QUALITY REGRESSION  → revert/iterate (even if it was cheaper)
     TOKEN SPIKE         → investigate
```

---

## 11. The `ClaudeSession`/`ControlProtocolTransport` layer

This is the reusable component the whole harness is built on — read
`claude_cli_control_protocol.md` (session memory) for the exact verified wire frames before
touching it. Summary:

- `ControlProtocolTransport` spawns `claude -p --input-format stream-json --output-format
  stream-json --verbose [--model ...] [--system-prompt ...] [--allowedTools ...] [--mcp-config ...]`,
  sends an `initialize` control_request declaring any registered hooks as the first stdin
  line, and runs a background thread parsing NDJSON frames from stdout, dispatching
  `hook_callback` and `mcp_message` control_requests to registered Java handlers.
- `ClaudeSession` wraps that behind a builder (`model`, `systemPrompt`, `allowedTools`,
  `registerPreToolUseHook`/`registerPostToolUseHook`, `inProcessMcpServer`) and a single
  `query(prompt) -> ChatResult` method. Query-only use (no hooks registered) is just the
  degenerate case — hooks are entirely optional.
- Token/cost capture reads the terminal `result` event: input tokens include cache read +
  cache creation tokens. The response **text** is the full accumulation of every assistant
  text block seen across the session (see `ClaudeSession.query()`'s comment for why this
  matters — the terminal event's own `result` field is only the *last* message's text,
  which silently drops earlier-phase content in any multi-turn response).
- This protocol is undocumented by Anthropic and may drift across `claude` CLI releases —
  treat the memory file as a snapshot, not a permanent spec.

---

## 12. Extending the system

**Add a scenario to an existing agent:**
1. Create `src/test/resources/fixtures/<agent>/scenario-NN-*.md` (the input).
2. Create `scenario-NN-*.golden.yaml`. Always include the baseline `forbidden` strings and
   `tools.forbidden` (`Write`, `Edit`, `Bash`) — the runner hard-asserts them (§6).
3. (Optional) add `scenario-NN-*-mcp.json` if the agent calls tools, and wire it in
   `scenarioOptions` via `InProcessMcpBridge`.
4. Add the scenario stem to the concrete `<Agent>EvalTest` class's scenario list.
5. Make sure `isPassed` handles it.

**Add a new agent:**
1. Create `<Agent>EvalTest extends AbstractEvalTest` with the agent's config.
2. Add it to `EvalCli.AGENT_TEST_CLASSES`.
3. Create `src/test/resources/fixtures/<agent>/` with at least one scenario pair.

**Mark a held-out case:** put `holdout` in the scenario name. Refine against non-holdout
cases; validate on holdout ones to avoid overfitting. The report tags them automatically.
