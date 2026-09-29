# MUI v5 → v6 Migration Reference

Official docs: https://mui.com/material-ui/migration/upgrade-to-v6/

## Install

```bash
npm install @mui/material@^6.0.0 @mui/icons-material@^6.0.0 @mui/system@^6.0.0 \
            @mui/lab@^6.0.0 @mui/material-nextjs@^6.0.0 @mui/utils@^6.0.0
```

Do NOT update MUI X packages (`@mui/x-*`) during this upgrade.

## Minimum Versions

- Node.js 14 (up from 12)
- TypeScript 4.7 (up from 3.5)
- React 17.0.0 (unchanged)
- Chrome 109, Edge 121, Firefox 115, Safari 15.4

### React 18 and below — fix react-is

```bash
npm install react-is@18.3.1   # match your react version
```
Add to `package.json`:
```json
{ "overrides": { "react-is": "^18.3.1" } }
```

## Codemods

```bash
# Migrate theme.palette.mode checks → theme.applyStyles()
npx @mui/codemod@latest v6.0.0/styled <path/to/folder>
npx @mui/codemod@latest v6.0.0/sx-prop <path/to/folder>
npx @mui/codemod@latest v6.0.0/theme-v6 <path/to/theme-file>

# Grid2 size/offset props rename (run after updating imports)
npx @mui/codemod@latest v6.0.0/grid-v2-props <path/to/folder>

# ListItem → ListItemButton
npx @mui/codemod@latest v6.0.0/list-item-button-prop <path/to/folder>
```

## Breaking Changes

### UMD Bundle Removed
Use ESM CDN (e.g. esm.sh) instead of UMD script tags. Package size -2.5MB (~25%).

### Accordion
- `AccordionSummary` is now wrapped in a `<h3>` heading by default.
  Override with: `<Accordion slotProps={{ heading: { component: 'h4' } }}>`
- From v6.3.0: `AccordionSummary` root is now a `<button>`, content is `<span>`.
  Replace `<Typography>` inside with `<Typography component="span">`.

### Autocomplete
New `reason` values in `onInputChange`: `"blur"`, `"selectOption"`, `"removeOption"` (previously all were `"reset"`).

### Chip
`Esc` key no longer blurs Chip. Add custom `onKeyUp` if previous behavior is needed.

### Divider (vertical)
Renders `<div>` with ARIA attributes instead of `<hr>` when vertical.
```diff
-'& hr': { marginTop: '16px' }
+[`& .${dividerClasses.root}`]: { marginTop: '16px' }
```

### Grid2 (stabilized)

Import without `Unstable_` prefix:
```diff
-import { Unstable_Grid2 as Grid2 } from '@mui/material';
+import { Grid2 } from '@mui/material';
```

Size/offset props renamed (breakpoint-named → `size`/`offset`):
```diff
-<Grid xs={12} sm={6} xsOffset={2} />
+<Grid size={{ xs: 12, sm: 6 }} offset={{ xs: 2 }} />
```
`true` → `"grow"`:
```diff
-<Grid xs>
+<Grid size="grow">
```

`disableEqualOverflow` removed — Grid no longer overflows parent.

Container width: Grid no longer grows to full width by default:
```diff
+<Grid container sx={{ width: '100%' }}>
```

### ListItem
Props `autoFocus`, `button`, `disabled`, `selected` removed. Use `ListItemButton` instead:
```diff
-<ListItem button />
+<ListItemButton />
```

### LoadingButton (v6.4.0+)
```diff
-import { LoadingButton } from '@mui/lab';
+import { Button } from '@mui/material';
```

### Typography
`color` prop no longer acts as a system prop. Use `sx`:
```diff
-<Typography color={(theme) => theme.palette.primary.main}>
+<Typography sx={{ color: (theme) => theme.palette.primary.main }}>
```

### useMediaQuery Types Removed
- `MuiMediaQueryList` → use `MediaQueryList`
- `MuiMediaQueryListEvent` → use `MediaQueryListEvent`
- `MuiMediaQueryListListener` → use `(event: MediaQueryListEvent) => void`

## Stabilized APIs

```diff
-import { experimental_extendTheme as extendTheme, Experimental_CssVarsProvider as CssVarsProvider } from '@mui/material/styles';
+import { extendTheme, CssVarsProvider } from '@mui/material/styles';
```

## New: theme.applyStyles()

Replaces `theme.palette.mode` checks for light/dark styles:
```diff
-borderColor: theme.palette.mode === 'dark' ? '#fff' : '#000',
+borderColor: '#000',
+...theme.applyStyles('dark', { borderColor: '#fff' })
```

## Pigment CSS (optional)

After upgrading to v6, you can optionally migrate to Pigment CSS for RSC support and smaller bundle. See: https://mui.com/material-ui/migration/migrating-to-pigment-css/

## Test Changes

Components with ripple effect need `act` + `await` in tests:
```diff
-fireEvent.click(button);
+await act(async () => fireEvent.mouseDown(button));
```
Affected: all buttons, Checkbox, Chip, Radio, Switch, Tabs.
