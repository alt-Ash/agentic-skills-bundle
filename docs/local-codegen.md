# local-codegen

`local-codegen` is a local MCP server plus the `local-slice-worker` agent. It offloads mechanical new-file code generation (DTOs, entities, mappers, repositories, boilerplate, tests from a precise spec) to a local OpenAI-compatible model (llama.cpp first, Ollama as fallback), so the main agent spends its effort on planning and review. The `parallel-feature-build` skill routes eligible slices to the worker.

## Design property

The MCP never writes into your project and never runs gates. `generate_slice` writes accepted files to `~/.cache/local-codegen/<run_id>/` (override with `LOCAL_CODEGEN_OUT`). The worker agent copies them into its worktree and runs the project's validation gates itself. The orchestrator re-runs the cheapest gate before accepting a slice.

## Tools

| Tool | Purpose |
|---|---|
| `health` | Which backends (llama.cpp on :8090, then Ollama on :11434) are reachable and what each serves. Also runs the capability probe: `viable` (false with a `reason`, plus a `start_command` when nothing is up), the detected `hardware`, and the `benchmark`. |
| `list_models` | Models served by the reachable backends. |
| `generate_slice` | Takes `slice_spec`, `files_allowed`, `project_root` and optional `context_files`, `model`, `max_tokens`, `timeout_seconds`. Asks the model for whole new files, rejects any path outside `files_allowed` and artifacts such as stray line-number markers (`#L1`), and returns `complete`, `rejected`, `missing`, `output_dir`, timing and token counts. |

## Environment variables

| Variable | Meaning |
|---|---|
| `LOCAL_CODEGEN_BACKENDS` | JSON list of `{name, base_url, model}`, tried in order. Overrides the default llama.cpp then Ollama pair. |
| `LOCAL_CODEGEN_API_KEY` | Optional bearer key sent to the backend. |
| `LOCAL_CODEGEN_OUT` | Output directory for generated files (default `~/.cache/local-codegen`). |
| `LOCAL_CODEGEN_KEEP_DAYS` | Run directories older than this are pruned on each generation (default 7). |
| `LOCAL_CODEGEN_BENCH_MAX_SECONDS` | Slowest acceptable benchmark slice (default 20). |
| `LOCAL_CODEGEN_PROBE` | `off` skips the benchmark; `force` re-runs it instead of using the cached result. |

## Runtime recipe (dev machine)

Measured setup: llama.cpp with CUDA, model Qwen2.5-Coder-14B-Instruct Q4_K_M (2 GGUF shards).

```bash
llama serve --host 127.0.0.1 --port 8090 -m <first-shard>.gguf -np 2 -c 16384 --fit on
```

Gotchas:

- Pass an explicit `--port 8090`. The default 8080 failed to bind once.
- Pass the path of the **first** GGUF shard.
- Resolve that path with `find`, not `ls`. Under an alias that prints `name -> target`, `ls` produced a bad path and llama failed with "No such file or directory".
- The Ollama fallback is weaker: its general `qwen2.5:14b` once emitted stray `#L1` markers that failed to compile (now rejected by `generate_slice`). Ollama's model store is root-owned, so llama.cpp cannot reuse its blobs.

## Capability probe

`health` times one fixed small slice (a two-member Java record) on the first reachable backend and caches the result for 24 hours in `<LOCAL_CODEGEN_OUT>/probe.json`. `viable` is true only when a backend is up and that slice finishes within the limit. The hardware report (accelerator, total and free VRAM and RAM, CPU cores, free disk, `suggested_tier`) is advisory: the tier uses total VRAM, since a loaded model occupies the free VRAM. The benchmark, not the hardware, is the gate.

Measured here (RTX 4080, 14B Q4_K_M): the benchmark slice took 1.9 to 2.3 s at 54 to 65 tokens/s, so the 20 s default leaves wide margin on this machine. The default and the tier thresholds (14B Q4 at 15 GB total VRAM or more, 7B Q4 at 7.5 GB) are proposals: no slower machine has been measured.

The installer runs a read-only check (`nvidia-smi` or `rocm-smi`, Apple Silicon) and only warns when no capable GPU is detected. It still installs; at runtime `viable: false` makes the worker return `blocked` and the skill reassigns the slice.

## When the local model cannot deliver

The worker returns `Status: blocked` and does not improvise code. This happens when no backend is up or `viable` is false, when an allowed file already exists, when the slice needs a `pom.xml` change or an edit to an existing file, when the spec is under-specified, or when generation still fails after one regeneration with the compiler error appended. It deletes only the files it copied in and reports the raw `output_dir`. The `parallel-feature-build` skill then reassigns the slice to a specialist agent with the same gate brief.

## Weaker hardware

Only the dev machine (RTX 4080 16 GB, 30 GB RAM, Fedora) was measured: the 14B Q4_K_M model is about 9 GB on disk and about 12.5 GB VRAM at `-np 2 -c 16384`, and a slice took 2.7 to 9 s (150 to 600 tokens). **Everything else is untested**, including CPU-only, AMD, Apple Silicon, Windows and smaller models. Do not assume it works acceptably elsewhere; if `health` reports `viable: false`, work proceeds without it.

Concerns to check on a work laptop:

- Corporate policy on running local servers and downloading multi-GB models.
- Disk space.
- Thermal and battery impact.
- A GPU shared with other applications.
- Apple or Windows differences.
- No root access.
- VPN or proxy blocking model downloads.
- The licence of the chosen model.
- Quality of smaller models drops sharply for anything beyond mechanical slices.

## Token savings: no (one run)

Does routing a slice through `local-slice-worker` use fewer Claude tokens than giving it to the specialist? **No.** One paired run, same four-file spec (entity, repository, DTO, mapper), same gate, both compiled: the local worker plus the plan-vs-diff reviewer used more Claude tokens than the specialist alone. The worker by itself was cheaper; the review step is what made the local path cost more.

Scope: one slice, one machine, one run, counting only the harness's per-agent Claude token usage. It says nothing about larger slices and was not repeated, so do not read it as a measured saving or a measured loss beyond that run. Because of that, the plan-vs-diff review is optional in `parallel-feature-build`: by default the orchestrator reads the generated files against the spec itself, and `pr-reviewer` runs only when the user asks or the spec has null, empty or boundary rules a read could miss (the reviewer is what catches a spec-violating `requireNonNull`, which a compile gate does not). Treat the feature as an opt-in for large mechanical slices, not as a saving.
