# Few-shot examples

Four pairs, generalized from real PBIs (identifying details replaced — e.g. a
specific client/domain becomes "document review system", a named role becomes
"Reviewer"). Kept deliberately small: this repo ships to more than one consumer,
so resist growing this file with more project-specific examples over time. If a
project accumulates its own recurring noise patterns, they belong in that
project's own docs, not here.

The first two are real structural patterns (color-styled asides, `To be
elaborated:` brackets) seen in production PBIs — only the domain wording is
genericized. The fourth is synthetic: real Azure DevOps comment threads aren't
fetchable until the `issue-tickets` comment-fetching change ships. **Revisit this
file with a real anonymized comment thread once that lands.**

---

## 1 — Author-flagged aside (color-styled text) → `flagged`, not noise, not signal

Input:

```xml
<ticket_discussion>
  <description>
    AS A Reviewer
    I WANT to replace and restore document versions
    SO THAT changes are retained without losing document history

    Notes:
    - Uploading a replacement creates a new version of the existing document.
    - Retain every previous version for audit.
    - Preserve the document's linked records (source, related reports).
      NOTE (author-flagged, color-styled): This statement is true, but belies
      the complexity here. For linked records it must be clear WHICH version
      they were created against BUT ALSO not make them entirely invalid. There
      is more to discuss here at a conceptual level — but the main meaning of
      this line is "when a version changes, it should not entirely invalidate
      historical references to it."
    - Make the newly uploaded version the current version by default.
  </description>
</ticket_discussion>
```

Output:

```xml
<scope_analysis>
  <signal source="description:bullet-1">Uploading a replacement creates a new version; every previous version is retained for audit.</signal>
  <signal source="description:bullet-2">The newly uploaded version becomes current by default.</signal>
  <flagged source="description:bullet-3-note">Author explicitly flagged unresolved complexity around how version changes affect linked records — real requirement exists ("don't invalidate historical references") but the exact mechanism is still being thought through.</flagged>
</scope_analysis>
```

**Why `flagged`, not `noise`:** the author marked this themselves (a real
requirement — historical references must survive a version change — wrapped in
an aside about *how* to implement it). Classifying it as noise would silently
drop a genuine constraint. Classifying it as ordinary `signal` would smuggle
unresolved design complexity into the acceptance criteria as if it were settled.
`flagged` keeps the constraint (references must survive) while flagging that the
mechanism is still open — the calling agent surfaces both.

---

## 2 — `To be elaborated:` bracket → `open`, never silently resolved

Input:

```xml
<ticket_discussion>
  <description>
    AS A Reviewer
    I WANT relevant clauses extracted from a document and its addenda
    SO THAT I can prepare a clause set for validation

    Notes:
    - Extract relevant clauses from the current document version and its addenda.
    - Trace each clause to its source document and version.
    - To be elaborated: [The exact clause properties and the rules applied to each property.]
    - To be elaborated: [Whether extraction starts automatically after upload or is started by an authorised user.]
  </description>
</ticket_discussion>
```

Output:

```xml
<scope_analysis>
  <signal source="description:bullet-1">Extract relevant clauses from the current version and its addenda.</signal>
  <signal source="description:bullet-2">Trace each clause to its source document and version.</signal>
  <open source="description:open-1">Exact clause properties and per-property rules are not yet defined.</open>
  <open source="description:open-2">Whether extraction is automatic-on-upload or user-initiated is not yet decided.</open>
</scope_analysis>
```

**Why this matters:** two `open` items directly affect what "done" means (a data
model decision and a UX/trigger decision). The calling agent must turn these into
clarifying questions before drafting Acceptance Criteria — guessing either way
and writing it into the issue as if it were decided would misrepresent the
ticket.

---

## 3 — Negative control: an all-signal ticket, nothing gets filtered

Input:

```xml
<ticket_discussion>
  <description>
    Dev outage (503): version migration fails to compile as a single SQL batch.

    The migration crash-loops the Dev environment on every start: 'Invalid column
    name current_version_id'. Root cause: the database binds column names when it
    compiles a batch, before executing any of it. The migration adds a column and
    references it in the same batch, so all three statements fail to compile.

    Fix: wrap the three offending statements so each compiles only when execution
    reaches it, rather than upfront with the rest of the batch.
  </description>
</ticket_discussion>
```

Output:

```xml
<scope_analysis>
  <signal source="description">Root cause and fix are both fully specified — deferred compilation of the three offending statements resolves the crash loop.</signal>
</scope_analysis>
```

**Why this matters:** this skill must not over-trigger. A dense, well-specified
bug report with zero asides, zero unresolved markers, and zero off-topic content
should produce a single `signal` entry and nothing else — do not manufacture
`flagged`/`open`/`noise` categories where none exist.

---

## 4 — Synthetic comment thread: a "question" that is actually signal

Input:

```xml
<ticket_discussion>
  <description>Add bulk CSV export to the reports screen.</description>
  <comment index="1" author="Sam" date="2026-08-03">Should this also support XLSX, or just CSV for now?</comment>
  <comment index="2" author="Priya" date="2026-08-04">CSV only for this ticket — XLSX would need a separate formatting pass, let's not scope-creep it in.</comment>
  <comment index="3" author="Sam" date="2026-08-04">makes sense, xlsx can be its own ticket later. anyway happy Friday all</comment>
</ticket_discussion>
```

Output:

```xml
<scope_analysis>
  <signal source="comment:2">Explicit scope decision: CSV only, XLSX excluded from this ticket.</signal>
  <noise source="comment:1" type="question">The question itself isn't signal — it's comment:2's answer that is. Do not classify comment:1 as noise-and-discard; it's the context that makes comment:2 legible, keep it attached in the audit trail.</noise>
  <noise source="comment:3" type="color-text">Off-topic sign-off, no bearing on requirements.</noise>
</scope_analysis>
```

**Why this matters:** the question (`comment:1`) is not itself a requirement, but
discarding it entirely would make the resolving answer (`comment:2`) read as
signal from nowhere. Keep a question in the `filtered_noise` audit trail with a
note on what it resolved into, rather than deleting it outright — "noise" means
"not scope," not "not worth keeping a record of."
