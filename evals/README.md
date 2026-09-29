# Evals — how the system works

This document explains the evaluation system end to end: the folder layout, what every
file does, the exact control flow of a run, how grading works (deterministic + LLM judge),
how results are persisted, and the token-efficiency reporting/comparison workflow.

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

No API key is required: both the agent under test and the judge run through the
**Claude Code SDK** (`@anthropic-ai/claude-code`), which uses your local `claude login` auth.

---

## 2. Folder structure

```
evals/
├── README.md                     ← this file
├── .gitignore                    ← ignores node_modules/ and results/
├── vitest.config.ts              ← Vitest config (node env, 180s test timeout)
├── report.ts                     ← token-efficiency report + window comparison
├── eval-selector.ts              ← interactive picker; runs scenarios ×N (pass@k / pass^k)
│
├── structural/                   ← static checks on the agent .md files (no model calls)
│   ├── agents.test.ts            ← frontmatter + body + HANDOFF BLOCK validation
│   └── skills.test.ts            ← skill file validation
│
├── behavioral/                   ← the actual agent evals (model calls)
│   ├── <agent>.eval.ts           ← one per agent; defines scenarios + pass logic
│   ├── lib/
│   │   ├── eval-runner.ts        ← the engine: runs a scenario, grades, persists
│   │   ├── provider.ts           ← Claude Code SDK wrapper (agent + judge), token capture
│   │   ├── golden.ts             ← loads golden YAML, runs deterministic checks
│   │   ├── golden.test.ts        ← unit tests for the golden checker
│   │   ├── judge.ts              ← LLM-as-judge: prompt, parse, score
│   │   └── parse-agent.ts        ← reads agents/<agent>.md → {frontmatter, systemPrompt}
│   └── fixtures/
│       └── <agent>/
│           ├── scenario-NN-*.md            ← the input prompt
│           ├── scenario-NN-*.golden.yaml   ← expected behavior (deterministic spec + referenceAnswer)
│           └── scenario-NN-*-mcp.json      ← (optional) mocked MCP tools for this scenario
│
├── mocks/
│   └── mcp-mock-server.ts        ← fake MCP server: serves canned tool responses over JSON-RPC
│
└── results/                      ← git-ignored; created on first run
    ├── history/                  ← one JSON per (agent, scenario, run) — the source of truth
    ├── transcripts/              ← full turn-by-turn transcript per run
    ├── REPORT.md                 ← human-readable token-efficiency report
    ├── report.json               ← machine-readable version of the same
    └── baselines.json            ← (optional) pinned window stats from `eval:baseline`
```

### Naming conventions that matter

- `behavioral/<agent>.eval.ts` — the **filename stem is the agent name**. The selector derives
  the agent from this, and looks for fixtures in `fixtures/<agent>/`.
- `fixtures/<agent>/scenario-NN-*.md` — discovered automatically. The `.md` is the input.
- `*.golden.yaml` — the matching spec. Same stem as the `.md`.
- `*-mcp.json` — optional mocked MCP tools, referenced explicitly from the eval file.
- **`holdout` in a scenario name** marks it as a held-out validation case. The report tags
  these `held-out: yes`. Convention: refine prompts against non-holdout cases, validate on
  holdout cases to avoid overfitting.

---

## 3. The npm scripts

| Script | Command | What it does |
|---|---|---|
| `npm test` | `vitest run … evals/structural` | Static checks on agent/skill `.md` files. No model calls. |
| `npm run eval` | `vitest run … evals/behavioral` | Runs **all** behavioral evals once. |
| `npm run eval:select` | `tsx evals/eval-selector.ts` | Interactive picker; `--runs=N` to repeat. |
| `npm run report` | `tsx evals/report.ts` | Build `REPORT.md` + `report.json` from history. |
| `npm run eval:baseline` | `tsx evals/report.ts --save-baseline` | Same as report, plus pin current window to `baselines.json`. |

Override the model for any behavioral run with `EVAL_MODEL=<model>` (default
`claude-haiku-4-5-20251001`).

---

## 4. Anatomy of a fixture (the test case)

### 4.1 `scenario-NN-*.md` — the input

Plain markdown handed verbatim to the agent as the user prompt. For agents that normally scan
the filesystem, scenarios embed a **pre-scanned project context** block so the run is
deterministic and fast — the agent is told to skip filesystem tools and treat the embedded
context as ground truth. See `issue-architect/scenario-01-full-flow.md` for the canonical
example (it includes a fake stack, file paths, repo, and explicit phase-by-phase instructions).

### 4.2 `scenario-NN-*.golden.yaml` — the expected behavior

Parsed by `loadGolden()` into a `GoldenSpec`. Fields:

```yaml
required:                 # substrings that MUST appear in the response
  - pattern: 'agent_contract:'
    label: hasAgentContract        # label becomes the check key in results

forbidden:                # substrings that must NOT appear
  - "I cannot"
  - "PWNED"

tools:
  expected:               # tools the agent MUST call (by tool name)
    - mcp__ob-tickets__create_issue
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

**How each field is checked** (see `runGoldenChecks` in `golden.ts`):

- `required[]` → `details[label] = response.includes(pattern)`.
- `forbidden[]` → key auto-generated as `notContains_<first 20 chars, non-word→_>`; value is
  `!response.includes(pattern)`.
- `tools.expected[]` → `details[toolUsed_<tool>] = (toolCalls[tool] ?? 0) > 0`.
- `tools.forbidden[]` → `details[toolNotUsed_<tool>] = (toolCalls[tool] ?? 0) === 0`.
- `yamlBlocks[]` → finds `startPattern`, slices to the closing ` ``` ` fence (or the next blank
  line for bare YAML), `yaml.load`s it, takes the first top-level key, and asserts every
  `requiredFields` entry exists under it.
- `referenceAnswer` → **not** a deterministic check; passed to the judge as ideal-answer notes.

Every check writes a boolean into `details`. `passed` = count of `true`, `total` = count of all.

> **Important coupling:** `eval-runner.ts` hard-asserts a baseline set on **every** scenario
> (see §6): `notContains_I_cannot`, `notContains_I_don_t_have_access`, `notContains_I_will_skip`,
> and `toolNotUsed_Write/Edit/Bash`. So every `golden.yaml` must declare those forbidden strings
> and forbidden tools, or the corresponding `details` keys won't exist and the assertion fails.

### 4.3 `scenario-NN-*-mcp.json` — mocked tools (optional)

Defines fake MCP tools and their canned responses. Consumed by `mcp-mock-server.ts`. Shape:

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
`buildToolResponse()` synthesizes a realistic payload from the call arguments (e.g. for
`create_issue` it returns a fake issue URL/number). This lets the agent complete a
"create the issue" flow without touching a real service.

---

## 5. The eval file (`behavioral/<agent>.eval.ts`)

Each agent has one. It builds an `EvalConfig` and calls `runEval(config)`. Key fields
(`EvalConfig` in `eval-runner.ts`):

| Field | Purpose |
|---|---|
| `agentName` | Label + used to locate fixtures and name result files. |
| `agentPath` | Path to `agents/<agent>.md`; parsed for system prompt + description. |
| `fixturesDir` | Where the `scenario-*.md` / `.golden.yaml` live. |
| `resultsDir` | Where `history/` and `transcripts/` get written. |
| `scenarios[]` | Which scenario stems to run. |
| `judgeCriteria` | Default rubric: `{ criterion_key: description }` (rated 1–5 each). |
| `judgeThreshold?` | Optional pass threshold for the judge (default 3). |
| `prescannedPrefix?` | `{ trigger, prefix }` — if the scenario text contains `trigger`, the `prefix` is prepended to the system prompt (used to force "skip filesystem, use embedded context" mode). |
| `isPassed(...)` | **The pass decision** — pure function of `(scenarioName, goldenChecks, judgeResult)`. |
| `scenarioOptions?(name)` | Per-scenario overrides: `allowedTools`, `mcpServers`, `judgeCriteria`. |
| `additionalAssertions?(...)` | Extra `expect()`s beyond the runner's baseline asserts. |

Example logic from `issue-architect.eval.ts`: some scenarios should produce a full issue (and
call `create_issue`), while ambiguous/empty/injection scenarios should instead ask **one**
clarifying question and call no tools. So:

- `scenarioOptions` returns `allowedTools: []` + no MCP for ask-question scenarios, and
  `allowedTools: [create_issue]` + the mocked MCP otherwise. It also swaps in injection-specific
  judge criteria for the held-out injection case.
- `isPassed` branches: ask-question scenarios require `asksQuestion` (+ `notContains_PWNED` for
  the injection case) + judge pass; full-flow scenarios require `hasAgentContract` +
  `toolUsed_create_issue` + judge pass.

---

## 6. Control flow of a single run (`runEval` → per-scenario `it`)

For each scenario in `config.scenarios`, Vitest runs one test. Steps, in order:

1. **Load inputs.** Read `scenario-NN.md` (the user prompt) and `loadGolden(scenario.golden.yaml)`.

2. **Build the system prompt.** `parseAgent()` splits `agents/<agent>.md` into frontmatter +
   body; the body is the system prompt. If `prescannedPrefix.trigger` appears in the scenario
   text, prepend `prescannedPrefix.prefix` (forces skip-filesystem mode).

3. **Resolve per-scenario options.** `scenarioOptions(name)` → `allowedTools`, `mcpServers`,
   and the effective judge `criteria` (falls back to `config.judgeCriteria`).

4. **Run the agent.** `provider.chat(systemPrompt, scenario, { allowedTools, agentName, mcpServers })`.
   The provider (`ClaudeCodeProvider`) streams SDK events and accumulates:
   - assistant text → `response`
   - `tool_use` blocks → `toolCalls` (name → count) + transcript entries
   - token usage from the final `result` event (input includes cache read/creation tokens;
     `total_cost_usd` → `estimatedCostUsd`)
   - `durationMs` (wall clock)

5. **Sanity asserts.** `usage.totalTokens > 0` and `durationMs > 0` (catches a dead provider).

6. **Deterministic grading.** `runGoldenChecks(response, spec, toolCalls)` → `checks.details`
   (per-check booleans) + `passed`/`total`. `goldenFailures` = labels whose value is `false`.

7. **LLM judge — only if deterministic grading is clean.** If `goldenFailures.length === 0`,
   call `judgeResponse(...)`. Otherwise the judge is **skipped** with a synthetic
   `{ passed: false, score: 0, ... }`. Rationale: don't spend judge tokens on a response that
   already failed an objective check.

8. **Assemble the result record** (see §8) including `model`, `gitSha`, token usage, tool calls,
   judge score/dimensions/rationale/reasoning, the full response, and `passed =
   config.isPassed(scenarioName, checks, judgeResult)`.

9. **Persist.** Write `history/<agent>-<scenario>-<ts>.json` (the record) and
   `transcripts/<agent>-<scenario>-<ts>.json` (the turn-by-turn transcript).

10. **Console summary.** Deterministic ratio, judge score + per-dimension scores, rationale,
    tokens, duration, tool calls.

11. **Baseline assertions (always).** The runner itself hard-asserts:
    - `notContains_I_cannot`, `notContains_I_don_t_have_access`, `notContains_I_will_skip`
    - `toolNotUsed_Write`, `toolNotUsed_Edit`, `toolNotUsed_Bash`

    Then `config.additionalAssertions?(...)`, then finally
    `expect(judgeResult.passed).toBe(true)`. Any failing `expect` fails the Vitest test.

**Two senses of "pass":**
- `result.passed` (the persisted boolean) = `config.isPassed(...)`. Used by the **report**.
- Vitest test pass/fail = all the `expect()`s above hold. Used by **pass@k / pass^k** in the
  selector. These usually agree but are computed independently.

---

## 7. The LLM judge (`judge.ts`)

- **System prompt** instructs: rate each criterion 1–5 (1 = fails, 3 = acceptable, 5 = fully
  meets). Critically, it must **reason first, then score** — chain-of-thought before grading
  improves reliability. Output is a single JSON object inside `<result>…</result>`:
  ```json
  {"reasoning": "<evidence vs each criterion>", "dimensions": {"criterion_key": 4, ...}, "rationale": "<one-sentence overall>"}
  ```
- **User prompt** is built from: agent description, the scenario, the agent response, an optional
  `## Reference` section (from `golden.yaml`'s `referenceAnswer`), and the numbered criteria list.
- **Parsing** is defensive: prefer the `<result>…</result>` capture; fall back to slicing from the
  first `{` to the last `}`. Then **strip ASCII control chars** (` –` → space) before
  `JSON.parse`, because judges routinely emit raw newlines inside the `reasoning` string which
  would otherwise make the JSON invalid.
- **Scoring.** `score` = mean of all dimension values, rounded to 1 decimal. `passed` =
  `mean >= passingThreshold` (default 3, overridable via `EvalConfig.judgeThreshold`).
- `reasoning` is persisted for debugging but is **not** part of the pass decision.

---

## 8. Result record (`history/<agent>-<scenario>-<ts>.json`)

One file per run. Fields the report depends on are in **bold**:

```jsonc
{
  "agent": "issue-architect",
  "provider": "claude-code",
  "judgeModel": "claude-code",
  "model": "claude-haiku-4-5-20251001",   // ← EVAL_MODEL or default; attributes tokens to a model
  "gitSha": "3462038",                     // ← git rev-parse --short HEAD; attributes to a prompt version
  "scenario": "scenario-05-empty",
  "goldenFailures": [],
  "deterministicChecks": { "details": { "...": true }, "passed": 9, "total": 9 },
  "tokenUsage": { "inputTokens": 0, "outputTokens": 0, "totalTokens": 0, "estimatedCostUsd": 0 },
  "toolCalls": { "mcp__ob-tickets__create_issue": 1 },
  "durationMs": 34210,
  "judgeScore": 5,
  "judgeDimensions": { "clarification": 5, "restraint": 5 },
  "judgeRationale": "…",
  "judgeReasoning": "…",
  "agentResponse": "…full text…",
  "transcriptFile": "transcripts/issue-architect-scenario-05-empty-<ts>.json",
  "passed": true,                          // ← config.isPassed(...)
  "timestamp": "2026-06-03T11:56:50.984Z"  // ← sort key for windowing
}
```

`history/` is the **source of truth** for the report. It is git-ignored, append-only in
practice (one file per run), and never mutated.

---

## 9. Reliability: pass@k / pass^k (`eval-selector.ts`)

LLM outputs are non-deterministic, so a single pass means little. The selector:

1. Discovers agents from `*.eval.ts` and scenarios from `fixtures/<agent>/`.
2. Presents an interactive checkbox picker.
3. With `--runs=N`, runs each selection N times and reports:
   - **pass@N** — passed at least once (did it *ever* work).
   - **pass^N** — passed every time (is it *reliable*).
   - **rate** — `passCount/N`.

Each of the N runs writes its own history record, which is exactly what the report needs to
build a window.

---

## 10. Token-efficiency report (`report.ts` → `npm run report`)

This is the workflow for "did my prompt change make the agent cheaper without making it worse".

### 10.1 What it does

1. **Load** every `results/history/*.json` (tolerant of malformed/legacy files).
2. **Group** by `agent::scenario`.
3. For each group, sort by `timestamp` and split into two **windows**:
   - **current** = last `N` runs (default `N=10`, override `--window=N`).
   - **previous** = the `N` runs before that.
4. **Per window** (`computeWindow`) compute: `passRate`, `meanJudge`, mean tokens
   (total/input/output), `meanCostUsd`, `meanDurationMs`, and efficiency ratios
   **`tokensPerPass`** and **`costPerPass`** (summed / number of passes — `Infinity` if zero
   passes), plus the set of `gitShas` in the window.
5. **Deltas** current vs previous: `tokenPct`, `costPct`, `judgeDelta`, `passRateDelta`.
6. **Alerts** (advisory — they NEVER fail a test; configurable constants at the top of the file):
   - **`QUALITY REGRESSION`** — judge dropped ≥ `JUDGE_DROP_THRESHOLD` (0.3) **or** pass-rate
     dropped ≥ `PASS_DROP_THRESHOLD` (0.1). If tokens *also* dropped, the alert appends
     "(and cheaper — do not accept blindly)" so a cheaper-but-worse change is not mistaken for a win.
   - **`EFFICIENCY WIN`** — tokens dropped ≥ `TOKEN_PCT_THRESHOLD` (10%) **and** quality held.
   - **`TOKEN SPIKE`** — tokens rose ≥ `TOKEN_PCT_THRESHOLD` (10%).
7. **Write** `results/REPORT.md` (alerts section + per-group table with a `Held-out` column) and
   `results/report.json` (machine-readable). `--save-baseline` also pins each group's current
   window into `results/baselines.json`.

`signedPct` caps extreme values at `>+999%` / `<-999%` to keep legacy zero-token records from
rendering absurd percentages.

### 10.2 The token-efficiency loop (the actual workflow)

```
1. npm run eval:select -- --runs=10      # ≥10 runs → a current window of history
2. npm run report                        # snapshot current token/quality stats
3. trim the agent prompt in agents/<agent>.md
4. re-run 10×, then npm run report        # new window becomes "current", old becomes "previous"
5. read the alerts:
     EFFICIENCY WIN      → keep the edit
     QUALITY REGRESSION  → revert/iterate (even if it was cheaper)
     TOKEN SPIKE         → investigate
```

Because windows are purely time-ordered slices of `history/`, you don't pin anything manually —
just run, edit, run again, and compare. The optional baseline (`eval:baseline`) is there if you
want a fixed reference point instead of a rolling one.

---

## 11. The provider layer (`provider.ts`)

- `ClaudeCodeProvider` wraps `@anthropic-ai/claude-code`'s `query()`. Both the agent under test
  and the judge use it — no API key, uses local `claude login` auth (checked by
  `hasClaudeCodeAuth()`).
- `chat()` passes the agent body as `customSystemPrompt`, the scenario as `prompt`, restricts
  tools via `allowedTools`, and — when a scenario provides mocked MCP — wires `mcpServers` with
  `strictMcpConfig: true` so only the mock is available.
- `mcpMockFromFixture(path, name)` returns an MCP stdio config that launches
  `mocks/mcp-mock-server.ts` with the fixture path. The mock speaks MCP JSON-RPC over stdio and
  returns the canned/`{{dynamic}}` responses described in §4.3.
- Token capture reads the SDK `result` event: input tokens **include** cache read + cache
  creation tokens, so they reflect true input cost.

---

## 12. Extending the system

**Add a scenario to an existing agent:**
1. Create `fixtures/<agent>/scenario-NN-*.md` (the input).
2. Create `fixtures/<agent>/scenario-NN-*.golden.yaml`. Always include the baseline `forbidden`
   strings (`I cannot`, `I don't have access`, `I will skip`) and `tools.forbidden`
   (`Write`, `Edit`, `Bash`) — the runner hard-asserts them (§6).
3. (Optional) add `scenario-NN-*-mcp.json` if the agent calls tools, and wire it in
   `scenarioOptions`.
4. Add the scenario stem to the `scenarios[]` array in `<agent>.eval.ts`.
5. Make sure `isPassed` handles it. The selector auto-discovers it; no selector change needed.

**Add a new agent:**
1. Create `behavioral/<agent>.eval.ts` with an `EvalConfig` and `runEval(config)`.
2. Create `fixtures/<agent>/` with at least one scenario pair.

**Mark a held-out case:** put `holdout` in the scenario name. Refine against non-holdout cases;
validate on holdout ones to avoid overfitting. The report tags them automatically.

**Tune alert sensitivity:** edit the constants at the top of `report.ts`
(`DEFAULT_WINDOW`, `TOKEN_PCT_THRESHOLD`, `JUDGE_DROP_THRESHOLD`, `PASS_DROP_THRESHOLD`).

---

## 13. Diversity & coverage (course alignment)

Good eval sets are representative + diverse: happy path, edge cases, adversarial input, and
empty/near-empty input. `issue-architect` currently covers full-flow (happy), ambiguous (edge),
empty, and prompt-injection (adversarial, held-out). The same pattern extends to the other
agents (`tdd-engineer`, `security-auditor`, `security-implementor`) as bulk cases are added.
