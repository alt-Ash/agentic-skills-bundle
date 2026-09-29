# MUI v6 → v7 Migration Reference

Official docs: https://mui.com/material-ui/migration/upgrade-to-v7/

## Install

```bash
npm install @mui/material@^7.0.0 @mui/icons-material@^7.0.0 @mui/system@^7.0.0 \
            @mui/lab@^7.0.0 @mui/material-nextjs@^7.0.0 @mui/styled-engine@^7.0.0 \
            @mui/styled-engine-sc@^7.0.0 @mui/utils@^7.0.0
```

Do NOT update MUI X packages during this upgrade.

## Minimum Versions

- TypeScript 4.9 (up from 4.7)
- React 17 (unchanged)

### React 18 and below — fix react-is

```bash
npm install react-is@18.3.1   # match your react version
```
```json
{ "overrides": { "react-is": "^18.3.1" } }
```

## Codemods

```bash
# Grid props migration (Grid2 → Grid rename)
npx @mui/codemod@latest v7.0.0/grid-props <path/to/folder>

# InputLabel size="normal" → size="medium"
npx @mui/codemod@latest v7.0.0/input-label-size-normal-medium <path/to/folder>

# Lab components moved to @mui/material
npx @mui/codemod@latest v7.0.0/lab-removed-components <path/to/folder>
```

## Breaking Changes

### Package Layout (deep imports removed)

Deep imports beyond one level are now blocked:
```diff
-import createTheme from '@mui/material/styles/createTheme';
+import { createTheme } from '@mui/material/styles';
```

Remove modern bundle aliases if configured in bundler:
```diff
-'@mui/material': '@mui/material/modern',
-'@mui/system': '@mui/system/modern',
```

Remove Vite ESM alias for icons (no longer needed):
```diff
-{ find: /^@mui\/icons-material\/(.*)/, replacement: "@mui/icons-material/esm/$1" }
```

TypeScript theme augmentation path change:
```diff
-declare module '@mui/material/styles/createTypography' {
+declare module '@mui/material/styles' {
-  interface TypographyOptions {
+  interface TypographyVariantsOptions {
```

### Grid and Grid2 Renamed

**Option 1 — Upgrade to new Grid (recommended):**
```bash
npx @mui/codemod@latest v7.0.0/grid-props <path/to/folder>
```

**Option 2 — Keep legacy Grid:**
```diff
-import Grid from '@mui/material/Grid';
+import Grid from '@mui/material/GridLegacy';

-import { Grid } from '@mui/material';
+import { GridLegacy as Grid } from '@mui/material';

# In theme:
-MuiGrid: { ... }
+MuiGridLegacy: { ... }

# CSS classes:
-.MuiGrid-root
+.MuiGridLegacy-root
```

**Option 3 — If already on Grid2:**
```diff
-import Grid from '@mui/material/Grid2';
+import Grid from '@mui/material/Grid';

-import { Grid2 as Grid } from '@mui/material';
+import { Grid } from '@mui/material';

# In theme:
-MuiGrid2: { ... }
+MuiGrid: { ... }
```

### InputLabel size Prop

```diff
-<InputLabel size="normal">
+<InputLabel size="medium">
```
Note: `MuiInputLabel-sizeMedium` class is no longer added — update any selectors relying on it.

### SvgIcon data-testid

`data-testid` removed from production bundles of `@mui/icons-material` icons. Update tests to not rely on this default attribute.

### TablePaginationActions Import

```diff
-import type { TablePaginationActionsProps } from '@mui/material/TablePagination/TablePaginationActions';
+import type { TablePaginationActionsProps } from '@mui/material/TablePaginationActions';
```

### Theme: CSS Variables Mode Change

When `cssVariables` + color schemes are enabled, the `theme` object no longer re-renders on mode change. Use `theme.vars.*` in styles:
```js
color: theme.vars.palette.text.primary,  // CSS variable reference
```
Or opt out: `<ThemeProvider forceThemeRerender />`

### Removed Deprecated APIs

```diff
# createMuiTheme
-import { createMuiTheme } from '@mui/material/styles';
+import { createTheme } from '@mui/material/styles';

# experimentalStyled
-import { experimentalStyled as styled } from '@mui/material/styles';
+import { styled } from '@mui/material/styles';

# StyledEngineProvider import
-import { StyledEngineProvider } from '@mui/material';
+import { StyledEngineProvider } from '@mui/material/styles';

# Dialog/Modal onBackdropClick prop
-<Dialog onBackdropClick={handler} />
# Use onClose with reason check instead:
+<Dialog onClose={(event, reason) => { if (reason === 'backdropClick') ... }} />

# Hidden component
-<Hidden implementation="css" xlUp><Paper /></Hidden>
+<Paper sx={{ display: { xl: 'none', xs: 'block' } }} />

# Rating readOnly CSS class
-.MuiRating-readOnly
+.Mui-readOnly
```

### Lab Components Moved to @mui/material

The following are no longer in `@mui/lab` — import from `@mui/material`:

Alert, AlertTitle, Autocomplete, AvatarGroup, Pagination, PaginationItem, Rating,
Skeleton, SpeedDial, SpeedDialAction, SpeedDialIcon, ToggleButton, ToggleButtonGroup, usePagination

```bash
npx @mui/codemod@latest v7.0.0/lab-removed-components <path/to/folder>
```
