# MUI v3 → v4 Migration Reference

## Install

```bash
npm install @material-ui/core@^4.0.0
# If using styles package:
npm install @material-ui/styles@^4.0.0
```

Minimum React version: `^16.8.0` (up from 16.3.0).

## Styles

- JSS v10 required — not backward compatible with v9. Remove `react-jss` if present.
- `withTheme()` no longer takes a first placeholder argument:
  ```diff
  -const DeepChild = withTheme()(DeepChildRaw);
  +const DeepChild = withTheme(DeepChildRaw);
  ```
- Rename `convertHexToRgb` → `hexToRgb`:
  ```diff
  -import { convertHexToRgb } from '@material-ui/core/styles/colorManipulator';
  +import { hexToRgb } from '@material-ui/core/styles';
  ```
- Keyframes must use `$` scoping:
  ```diff
  -animation: 'mui-ripple-enter 100ms ...'
  +animation: '$mui-ripple-enter 100ms ...'
  ```

## Theme

- `theme.palette.augmentColor()` no longer mutates input — use return value:
  ```diff
  -const bg = { main: color };
  -theme.palette.augmentColor(bg);
  +const bg = theme.palette.augmentColor({ main: color });
  ```
- Remove `typography: { useNextVariants: true }` — it is now the default.
- `theme.spacing.unit` is deprecated → use `theme.spacing(n)`:
  ```diff
  -paddingTop: theme.spacing.unit * 12,
  +paddingTop: theme.spacing(12),
  ```
  Codemod: `@mui/codemod theme-spacing-api`

## Layout

- **Grid spacing** values changed from pixel multiples (0,8,16...) to scale (0–10).
- **Container** moved from `@material-ui/lab` to `@material-ui/core/Container`.

## TypeScript

- `value` prop type normalized to `unknown` on: `InputBase`, `NativeSelect`, `OutlinedInput`, `Radio`, `RadioGroup`, `Select`, `Switch`, `TextArea`, `TextField`.

## Component Changes

### Button
```diff
-<Button variant="raised" />   → <Button variant="contained" />
-<Button variant="flat" />     → <Button variant="text" />
-<Button variant="fab" />      → <Fab />
-<Button variant="extendedFab" /> → <Fab variant="extended" />
```

### Card
- `CardActions`: `disableActionSpacing` → `disableSpacing`; CSS class `action` → `spacing`

### Dialog
- `DialogActions`: `disableActionSpacing` → `disableSpacing`; CSS class `action` → `spacing`
- `DialogContentText`: uses `body1` typography variant instead of `subtitle1`

### Divider
```diff
-<Divider inset />
+<Divider variant="inset" />
```

### ExpansionPanel
- `CollapseProps` → `TransitionProps`
- CSS specificity increased for `disabled` and `expanded` rules

### List
- `dense` no longer reduces top/bottom padding of `List` element
- `ListItemAvatar` required when using avatars
- `ListItemIcon` required when using left checkboxes

### Paper
```diff
-<Paper />
+<Paper elevation={2} />
```

### Slider
```diff
-import Slider from '@material-ui/lab/Slider'
+import Slider from '@material-ui/core/Slider'
```

### Switch
CSS class renames: `icon` → `thumb`, `bar` → `track`

### SvgIcon
```diff
-<AddIcon nativeColor="#fff" />
+<AddIcon htmlColor="#fff" />
```

### Tabs
```diff
-<Tabs fullWidth scrollable />
+<Tabs variant="scrollable" />
```

### Table
```diff
-<TableCell numeric>{row.calories}</TableCell>
+<TableCell align="right">{row.calories}</TableCell>

-<TableCell padding="dense" />
+<TableCell size="small" />
```

### TextField / InputLabel
- `FormLabelClasses` prop removed from `InputLabel` — use `classes` directly.
- `InputBase` now uses `box-sizing: border-box`.

### Typography
Variant renames:
```
display4 → h1      display3 → h2      display2 → h3     display1 → h4
headline → h5      title → h6         subheading → subtitle1
body2 → body1      body1 (default) → body2 (default)
```
- `headlineMapping` prop → `variantMapping`
- `color="default"` → `color="initial"`

### UMD
```diff
-window['material-ui']
+MaterialUI
```
