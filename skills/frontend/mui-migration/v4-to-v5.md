# MUI v4 → v5 Migration Reference

Official docs: https://mui.com/material-ui/migration/migration-v4/

## Step-by-step

1. Update React to `^17.0.0` and TypeScript to `^3.5`
2. Ensure `ThemeProvider` wraps the app root before migrating
3. Install new packages
4. Run codemods
5. Address remaining breaking changes
6. Optionally migrate JSS → Emotion

## Install

```bash
npm install @mui/material @mui/styles @emotion/react @emotion/styled
npm install @mui/lab @mui/icons-material   # if used
npm uninstall @material-ui/core @material-ui/styles @material-ui/lab @material-ui/icons
```

## Package Renames

```
@material-ui/core        → @mui/material
@material-ui/unstyled    → @mui/base
@material-ui/icons       → @mui/icons-material
@material-ui/styles      → @mui/styles
@material-ui/system      → @mui/system
@material-ui/lab         → @mui/lab
```

## Codemods

Run in order — apply once per folder:

```bash
# Handles most breaking changes automatically
npx @mui/codemod@latest v5.0.0/preset-safe <path>

# Only if you want to keep variant="standard" on TextField/Select/FormControl
npx @mui/codemod@latest v5.0.0/variant-prop <path>

# Only if you want to keep underline="hover" on Link
npx @mui/codemod@latest v5.0.0/link-underline-hover <path>
```

## Styling Engine Change: JSS → Emotion

The default styling solution changed from JSS to Emotion. `makeStyles` / `withStyles` still work via `@mui/styles` (deprecated) during the transition. To fully migrate away from JSS see the [JSS migration guide](https://mui.com/material-ui/migration/migrating-from-jss/).

## Theme API Changes

- `createMuiTheme` → `createTheme`
- `theme.palette.type` → `theme.palette.mode`
- `theme.spacing` still works; `theme.spacing.unit` is removed
- Component overrides moved under `theme.components`:
  ```diff
  -overrides: { MuiButton: { root: { ... } } }
  +components: { MuiButton: { styleOverrides: { root: { ... } } } }
  ```
- Default props moved under `theme.components`:
  ```diff
  -props: { MuiButton: { disableElevation: true } }
  +components: { MuiButton: { defaultProps: { disableElevation: true } } }
  ```

## TextField / Select / FormControl

Default `variant` changed from `"standard"` to `"outlined"`. Either run the `variant-prop` codemod or set `variant="outlined"` as default in theme:
```js
createTheme({
  components: {
    MuiTextField: { defaultProps: { variant: 'outlined' } },
  },
})
```

## CSS Specificity

If you apply styles via imported CSS files, increase specificity:
```css
/* Required in v5 */
.MuiChip-root .green { color: green; }
```

## Date Pickers

Moved out of `@mui/lab` → `@mui/x-date-pickers`. See separate migration guide.

## Supported Browsers

- Node 12+, Chrome 90+, Edge 91+, Firefox 78+, Safari 14+
- IE 11 not supported (use legacy bundle from v5 docs if needed)
