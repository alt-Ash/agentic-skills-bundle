---
name: mui-best-practices
description: >
  Material UI best practices for design, theming, and component customization. Covers theme
  setup, palette, typography, sx prop, styled(), theme overrides, and component references.
  Use when building or customizing MUI-based UIs, implementing new components, or reviewing
  existing MUI usage in any React project.
version: "1.2.0"
category: frontend
---

# Material UI — Best Practices

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Provides authoritative patterns for building and customizing MUI-based React UIs: theme
architecture, palette configuration, the four customization strategies, TypeScript augmentation,
and per-component references. Does NOT cover MUI X (Data Grid, Date Pickers) or migration
between MUI major versions — use `mui-migration` for that.

## Do not use when

- The task is a version migration (use `mui-migration` instead)
- The project does not use MUI

---

## Phase 0 — Gather context

1. Read `package.json` to find the installed `@mui/material` version.
2. Find the theme file: search for `createTheme` in `src/`.
3. Check for TypeScript: `tsconfig.json` present?
4. Check for Storybook: `.storybook/` directory present?

Emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : mui-best-practices
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : React <version>
- UI library      : MUI <version>
- Build tool      : <Vite | CRA | webpack>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : —
- Target version  : —
- Migration hops  : —

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json
- <theme file path if found>

### Gaps / unknowns
- <Custom theme found: yes at <path> | no | unknown>
***END CONTEXT BLOCK***
```

---

## Design Principles

- **Theme first**: define all design tokens (colors, typography, spacing, shape) in a single `createTheme()` call at the app root. Never hardcode values that belong in the theme.
- **Hierarchy of customization** (narrowest → broadest):
  1. `sx` prop — one-off instance overrides
  2. `styled()` — reusable component variants
  3. `theme.components[Mui*].styleOverrides` — global component overrides
  4. `GlobalStyles` / `CssBaseline` overrides — raw HTML element resets
- **Prefer theme variables over magic values**: use `theme.spacing()`, `theme.palette.*`, `theme.typography.*` inside `styled()` and `styleOverrides`.
- **Avoid `ownerState` callbacks** — they are deprecated. Use the `variants` array in `styleOverrides` instead.
- **Theme is not tree-shakable**: keep it lean. For heavy one-off customizations, create new components with `styled()` rather than bloating the theme.

---

## Theming

### Setup

```jsx
import { createTheme, ThemeProvider } from '@mui/material/styles';

const theme = createTheme({ /* tokens */ });

function App() {
  return <ThemeProvider theme={theme}>{/* tree */}</ThemeProvider>;
}
```

### Two-step composition (when tokens depend on each other)

```js
let theme = createTheme({ palette: { primary: { main: '#0052cc' } } });
theme = createTheme(theme, {
  palette: { info: { main: theme.palette.primary.main } },
});
```

### CSS Variables (recommended for dark mode / SSR)

```jsx
const theme = createTheme({ cssVariables: true });
```

Generates `--mui-palette-primary-main: …` on `:root`. All components use `var()` references.

### Accessing the theme in components

```jsx
import { useTheme } from '@mui/material/styles';
const theme = useTheme(); // inside functional components
```

### Custom theme variables (TypeScript)

```ts
declare module '@mui/material/styles' {
  interface Theme { status: { danger: string } }
  interface ThemeOptions { status?: { danger?: string } }
}
```

---

## Palette

### Color tokens per palette entry

| Token | Description |
|---|---|
| `main` | Required. Primary shade. |
| `light` | Auto-calculated if omitted. |
| `dark` | Auto-calculated if omitted. |
| `contrastText` | Auto-calculated for contrast against `main`. |

### Override default palette

```js
// Only main is required — light/dark/contrastText are calculated
createTheme({ palette: { primary: { main: '#FF5733' } } });
```

### Color schemes (v6+ — preferred for dark mode)

```js
createTheme({
  colorSchemes: {
    light: { palette: { primary: { main: '#1976d2' } } },
    dark:  { palette: { primary: { main: '#90caf9' } } },
  },
});
```

> Do not mix `colorSchemes` and `palette` at the top level — `palette` overrides `colorSchemes`.

### Custom colors

```js
// augmentColor auto-calculates light/dark/contrastText
let theme = createTheme();
theme = createTheme(theme, {
  palette: { brand: theme.palette.augmentColor({ color: { main: '#FF5733' }, name: 'brand' }) },
});
```

### Accessibility

```js
createTheme({ palette: { contrastThreshold: 4.5 } }); // WCAG 2.1 AA
```

---

## Customization Strategies

### 1. `sx` prop — one-off

```jsx
<Slider sx={{ width: 300, color: 'success.main' }} />
// Target a slot:
<Slider sx={{ '& .MuiSlider-thumb': { borderRadius: '2px' } }} />
```

### 2. `styled()` — reusable

```jsx
import { styled } from '@mui/material/styles';

const BrandButton = styled(Button)(({ theme }) => ({
  backgroundColor: theme.palette.brand.main,
}));
```

Dynamic styles via `variants` array (preferred over prop callbacks):

```jsx
const StyledSlider = styled(Slider, {
  shouldForwardProp: (p) => p !== 'success',
})(({ theme }) => ({
  variants: [
    { props: { success: true }, style: { color: theme.palette.success.main } },
  ],
}));
```

### 3. Global theme overrides — `theme.components`

```js
createTheme({
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { textTransform: 'none' },
      },
    },
  },
});
```

Adding a new variant:

```js
MuiButton: {
  styleOverrides: {
    root: {
      variants: [{ props: { variant: 'dashed' }, style: { border: '2px dashed currentColor' } }],
    },
  },
},
// TypeScript:
declare module '@mui/material/Button' {
  interface ButtonPropsVariantOverrides { dashed: true }
}
```

### 4. State classes

| State | Global class |
|---|---|
| disabled | `.Mui-disabled` |
| focused | `.Mui-focused` |
| selected | `.Mui-selected` |
| error | `.Mui-error` |
| checked | `.Mui-checked` |
| expanded | `.Mui-expanded` |

Always combine with a component class:
```css
/* ✅ */ .MuiOutlinedInput-root.Mui-error { color: red; }
/* ❌ */ .Mui-error { color: red; }
```

---

## Done when

- All design tokens defined in the theme — no hardcoded colors, spacing, or typography values
- Customization strategy matches the scope (sx for one-off, styled for reusable, theme.components for global)
- TypeScript augmentations added for any custom palette colors or variants
- All available checks pass (see Handoff)

## References

Load only when needed:

- **Component docs and customization references**: [references/component-urls.md](references/component-urls.md)

---

## Handoff

After applying this skill, emit:

```
***HANDOFF BLOCK***
Skill/Agent : mui-best-practices
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- <Implemented | reviewed | customized>: <component or theme area>
- Patterns applied: <list of strategies used: sx | styled | theme.components | GlobalStyles>

### Artifacts produced
| File | Change |
|------|--------|
| <path> | created / modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <item or "—">

### For the next agent or step
MUI <version> project. Theme at <path>. TypeScript: <yes|no>. Customization approach used:
<sx | styled | theme.components>. Any TypeScript augmentations added are listed in the
artifacts above.
***END HANDOFF BLOCK***
```
