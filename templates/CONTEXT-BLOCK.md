# CONTEXT BLOCK — Reference Template

The **CONTEXT BLOCK** is the normalized output emitted at the end of every context-gathering phase
(Phase 0) in skills and agents. It creates a typed, predictable handoff so that:

- Each subsequent phase reads the same structure regardless of which skill/agent produced it.
- Downstream agents (e.g. `spring-boot-backend-engineer` consuming `security-auditor` output) never
  have to re-discover context that was already collected.
- Gaps and unknowns are surfaced explicitly instead of silently defaulting.

---

## How to emit it

At the end of Phase 0 (or whichever phase collects context), emit the block **verbatim in the
conversation** using the exact fences shown below. Fill in every field. Use `unknown` only when
genuinely undeterminable, and list it under **Gaps**.

---

## Template

```
***CONTEXT BLOCK***
Skill/Agent : <skill-name or agent-name>
Timestamp   : <ISO-8601 date>

### Project
- Type            : [Spring Boot API | Quarkus | Micronaut | monorepo | unknown]
- Build tool       : [Maven | Gradle | unknown]
- Monorepo        : [yes — tool: <nx/turborepo/lerna/…> | no]

### Runtime & tooling versions
- JDK version     : <version or "unknown">
- Spring Boot version : <version or "—">
- Framework       : <name + version, e.g. "Spring Boot 3.2" or "—">
- Test runner     : <JUnit 5 | TestNG | none | unknown>
- Linter          : <checkstyle | spotless | none | unknown>

### Migration context (fill only when the skill is a migrator)
- Current version : <e.g. "JDK 17" or "—">
- Target version  : <e.g. "JDK 21" or "—">
- Migration hops  : <e.g. "17 → 21 (single hop)" or "—">

### Infrastructure
- CI/CD           : [GitHub Actions | Azure Pipelines | GitLab CI | CircleCI | none | unknown]
- Docker          : [yes | no | unknown]

### Files read
<!-- List every file actually opened during this phase — not assumptions -->
- <relative/path/to/file>

### Gaps / unknowns
<!-- Anything that could not be determined. Each gap MUST be resolved (ask user or flag as assumption) before Phase 1 starts. -->
- <description of gap or "none">
***END CONTEXT BLOCK***
```

---

## Rules

- **Emit before Phase 1 starts.** No phase work begins until the CONTEXT BLOCK is in the
  conversation.
- **Fill every field.** Use `—` for fields that genuinely do not apply to this skill (e.g.
  "Migration context" fields in a non-migrator skill). Never leave a field blank.
- **List only files actually read**, not files you assume exist.
- **Every gap must be resolved.** If a gap would block Phase 1, ask the user before emitting
  the block. If it is non-blocking, note it and proceed.
- **Do not re-emit the block** in later phases unless context materially changes (e.g. after
  the user answers a gap question).
- **Downstream agents**: if you receive a CONTEXT BLOCK from a previous agent, inherit its
  values for your own Phase 0. Only re-read files that are relevant to your phase.
