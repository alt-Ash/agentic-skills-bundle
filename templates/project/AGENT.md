# {{PROJECT_NAME}} — Agent Instructions

<!-- Generated stub — invoke the `project-initializer` agent to fill this in -->
<!-- Keep this file as the persistent instruction set for any agent working on this project -->

You are working on **{{PROJECT_NAME}}** ({{PROJECT_TYPE}} project).

Before starting any task, read:
- `ARCHITECTURE.md` — tech stack, project structure, entry points, data flow
- `MEMORY.md` — tech decisions, key files, known gotchas, configuration
{{DESIGN_MD_LINE}}

---

## Keeping docs in sync

These files are living documents. Update them as you discover or change things — do not defer to a follow-up task.

---

### When to update `MEMORY.md`

| Trigger | Section to update |
|---------|------------------|
| New tech decision made (library chosen, pattern adopted) | **Tech Decisions** |
| Non-obvious behavior, workaround, or foot-gun found | **Known Issues & Workarounds** |
| New significant file or module added | **Key Files** |
| New naming convention established | **Naming Conventions** |
| New env var or external service added | **Configuration** |
| Domain term introduced or clarified | **GLOSSARY.md** |

After any MEMORY.md update, append one row to the **Version History** table: `| YYYY-MM-DD | <one-line summary of what changed and why> |`

---

### When to update `GLOSSARY.md`

Update when you encounter ANY of:
- New domain term, acronym, or technical concept
- Alias or synonym for existing term
- Term definition clarified or corrected
- Term used in a new context (add a KOS category)
- URL fragment pointing to term in docs
- Vocabulary introduced via user message, code comment, or external doc

Format: add a new table row with term, aliases, definition, hint, KOS category, and URL fragment. Append a row to **Version History**: `| YYYY-MM-DD | <term name and what changed> |`

---

### When to update `ARCHITECTURE.md`

- Dependency added or removed (update Tech Stack table)
- Top-level directory added, moved, or renamed (update Project Structure + Directory Tree)
- Entry point added or removed
- Data flow pattern changed (new state manager, new API style, etc.)
- External service added or removed

---

### When to update `DESIGN.md` _(frontend projects only)_

| Trigger | Section | Also update |
|---------|---------|-------------|
| Color added or changed | **Colors** prose | YAML `colors` in frontmatter |
| Typography scale changed | **Typography** prose | YAML `typography` in frontmatter |
| Spacing convention established | **Layout** prose | YAML `spacing` in frontmatter |
| Border radius convention changed | **Shapes** prose | YAML `rounded` in frontmatter |
| New component variant or state | **Components** prose | YAML `components` in frontmatter |
| Design guardrail identified | **Do's and Don'ts** | — |

**How to update — examples:**

_Adding a color_ — update both prose and YAML:

```markdown
## Colors
- **Primary (#1A1C1E):** Deep ink for headlines and core text — maximum readability.
- **Accent (#B8422E):** Earthy red — use exclusively for primary actions and highlights.
```
```yaml
# frontmatter colors:
  primary: "#1A1C1E"
  accent: "#B8422E"
```

_Adding a component variant_ — update both prose and YAML:

```markdown
## Components
**Button — Primary**: solid bg-primary, white text, rounded-xl, 12px/24px padding.
Hover: bg-primary at 90% opacity.
```
```yaml
# frontmatter components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.xl}"
    padding: 12px 24px
  button-primary-hover:
    backgroundColor: "{colors.primary-dim}"
```

_Adding a guardrail:_

```markdown
## Do's and Don'ts
- Do use primary color only for the single most important action per screen
- Don't mix rounded-xl and sharp corners in the same view
```

---

## How to update

- Edit only the relevant section — do not rewrite unrelated content
- Keep entries concise: one bullet or one table row per fact
- No stale content: if something is no longer true, remove or correct it
- Never leave `{{` placeholders unfilled in any doc
