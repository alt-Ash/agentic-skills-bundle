---
name: mui-best-practices
description: >
  Material UI best practices for design, theming, and component customization. Covers theme
  setup, palette, typography, sx prop, styled(), theme overrides, and component references.
  Use when building or customizing MUI-based UIs, implementing new components, or reviewing
  existing MUI usage in any React project.
---

# Material UI Best Practices

## Token Discipline

Load [references/full-guide.md](references/full-guide.md) only when detailed examples are needed. Load [references/component-urls.md](references/component-urls.md) only for a specific component's API/customization docs.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Read `package.json` for `@mui/material` version.
2. Locate theme file by searching for `createTheme`.
3. Check TypeScript and Storybook only if relevant to task.
4. Emit compact context: `Context: mui-best-practices; React+MUI; pkg=<manager>; ts=<yes|no>; versions=MUI <v>, React <v>; files=<read>; gaps=<items>`.

## Customization Router

| Scope | Preferred tool | Use when |
|---|---|---|
| One component instance | `sx` | Single-use visual tweak |
| Reusable custom component | `styled()` | Same styling reused across app |
| App-wide component behavior | `theme.components[Mui*]` | Global defaults/variants/overrides |
| Raw element reset | `CssBaseline` / `GlobalStyles` | HTML/body/global reset only |

## Core Rules

- Theme first: design tokens belong in `createTheme`, not scattered literals.
- Prefer `theme.spacing`, `theme.palette`, `theme.typography`, and theme vars over magic values.
- Keep theme lean; theme is not tree-shakable.
- Use MUI `variants` for prop-based styling; avoid deprecated `ownerState` callback patterns.
- For v6+ dark mode/SSR, prefer `colorSchemes`/CSS variables when project already uses that approach.
- Do not mix top-level `palette` with `colorSchemes` without checking override behavior.
- Add TypeScript module augmentation for custom palette colors or variants.
- Scope state-class overrides with component classes, not global `.Mui-error`-style selectors alone.

## Reference Router

| Need | Load |
|---|---|
| Detailed theme/palette/examples | `references/full-guide.md` |
| Specific component slots/classes/API | `references/component-urls.md` |
| Version migration | `mui-migration` skill, not this skill |
| MUI X Data Grid/Date Pickers | MUI X docs/skill, not this skill |

## Done When

- Customization strategy matches scope.
- Theme tokens are used where appropriate.
- TypeScript augmentations exist for custom palette/variants.
- Available checks pass or blockers are reported.

Compact handoff:

```text
Handoff: mui-best-practices; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=MUI <version>, strategy=<sx|styled|theme.components|global>
```
