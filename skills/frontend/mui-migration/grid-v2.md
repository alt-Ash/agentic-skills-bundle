# MUI Grid v2 Migration Reference

Official docs: https://mui.com/material-ui/migration/upgrade-to-grid-v2/

Applies to: migrating from `Grid` (legacy/v1) or `Unstable_Grid2` → the stable `Grid` (v2).

## Why Upgrade

- Uses CSS variables — no CSS specificity issues from class selectors
- No `item` prop needed — all grids are items by default
- Offset feature for flexible positioning
- No depth limitation on nested grids
- No negative margin overflow

## Version History

| MUI version | New Grid import |
|---|---|
| v5 | `@mui/material/Unstable_Grid2` |
| v6 | `@mui/material/Grid2` (stabilized, no `Unstable_` prefix) |
| v7+ | `@mui/material/Grid` (`Grid2` moved to `Grid` namespace; old `Grid` → `GridLegacy`) |

## Step 1: Update Import

**For v7+:**
```diff
-import Grid from '@mui/material/GridLegacy';   // or @mui/material/Grid (old)
+import Grid from '@mui/material/Grid';
```

**For v6:**
```diff
-import { Unstable_Grid2 as Grid2 } from '@mui/material';
+import { Grid2 } from '@mui/material';
```

## Step 2: Remove Legacy Props

```diff
-<Grid item zeroMinWidth>
+<Grid>
```

## Step 3: Update Size Props (v6+)

Breakpoint-named props → `size` and `offset`:

```diff
-<Grid xs={12} sm={6} xsOffset={2} smOffset={3}>
+<Grid size={{ xs: 12, sm: 6 }} offset={{ xs: 2, sm: 3 }}>
```

Single value when the same across all breakpoints:
```diff
-<Grid xs={6}>
+<Grid size={6}>
```

`true` → `"grow"`:
```diff
-<Grid xs>
+<Grid size="grow">
```

### Codemod for size props

**v7:**
```bash
npx @mui/codemod@latest v7.0.0/grid-props <path/to/folder>
```

**v6:**
```bash
# Update imports first, then:
npx @mui/codemod@latest v6.0.0/grid-v2-props <path/to/folder>

# With custom breakpoints:
npx @mui/codemod@latest v6.0.0/grid-v2-props <path/to/folder> \
  --jscodeshift='--muiBreakpoints=mobile,desktop'
```

## Step 4: Negative Margins (v5 only)

If you need negative margins matching GridLegacy behavior on v5:
```js
createTheme({
  components: {
    MuiGrid2: { defaultProps: { disableEqualOverflow: true } },
  },
})
```

## Common Issues

### Column Direction

`direction="column"` or `direction="column-reverse"` is **not supported**. Rework vertical layouts without Grid or follow the [column direction docs](https://mui.com/material-ui/react-grid/#column-direction).

### Container Width

Grid no longer grows to full parent width by default:
```diff
-<Grid container>
+<Grid container sx={{ width: '100%' }}>
```
Or if parent is flex:
```diff
+<Grid container sx={{ flexGrow: 1 }}>
```

### Wrapped Components Not Covered by Codemod

```jsx
// These need manual migration:
const StyledGrid = styled(Grid)({ ... });
const WrappedGrid = (props) => <Grid {...props} />;
```

### Theme Component Key Changes

```diff
# v6: Grid2 stabilized
-MuiGrid2: { ... }
+MuiGrid2: { ... }   // still MuiGrid2 in v6

# v7: Grid2 → Grid
-MuiGrid2: { ... }
+MuiGrid: { ... }

# v7: GridLegacy
-MuiGrid: { ... }
+MuiGridLegacy: { ... }
```

### CSS Class Changes (v7)

```diff
-.MuiGrid2-root
+.MuiGrid-root

-.MuiGrid-root   (legacy)
+.MuiGridLegacy-root
```
