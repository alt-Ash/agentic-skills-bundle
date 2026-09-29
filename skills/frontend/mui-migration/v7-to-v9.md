# MUI v7 → v9 Migration Reference

Official docs: https://mui.com/material-ui/migration/upgrade-to-v9/

Note: There is no v8 — version jumped from v7 directly to v9.

## Install

```bash
npm install @mui/material@^9.0.0 @mui/icons-material@^9.0.0 @mui/system@^9.0.0 \
            @mui/material-nextjs@^9.0.0 @mui/styled-engine@^9.0.0 \
            @mui/styled-engine-sc@^9.0.0 @mui/utils@^9.0.0
# @mui/lab: install latest v9 beta
npm install @mui/lab@latest
```

Do NOT update MUI X packages during this upgrade.

## Minimum Versions

- Chrome 117, Firefox 121, Safari 17.0

## Codemods

Most breaking changes have codemods. Run per-component:

```bash
npx @mui/codemod@latest deprecations/<codemod-name> <path>
```

Key codemods:
```bash
npx @mui/codemod@latest deprecations/accordion-props <path>
npx @mui/codemod@latest deprecations/alert-classes <path>
npx @mui/codemod@latest deprecations/alert-props <path>
npx @mui/codemod@latest deprecations/autocomplete-props <path>
npx @mui/codemod@latest deprecations/avatar-props <path>
npx @mui/codemod@latest deprecations/avatar-group-props <path>
npx @mui/codemod@latest deprecations/backdrop-props <path>
npx @mui/codemod@latest deprecations/badge-props <path>
npx @mui/codemod@latest deprecations/button-classes <path>
npx @mui/codemod@latest deprecations/button-group-classes <path>
npx @mui/codemod@latest deprecations/card-header-props <path>
npx @mui/codemod@latest deprecations/checkbox-props <path>
npx @mui/codemod@latest deprecations/chip-classes <path>
npx @mui/codemod@latest deprecations/circular-progress-classes <path>
npx @mui/codemod@latest deprecations/dialog-classes <path>
npx @mui/codemod@latest deprecations/dialog-props <path>
npx @mui/codemod@latest deprecations/drawer-props <path>
npx @mui/codemod@latest deprecations/drawer-classes <path>
npx @mui/codemod@latest deprecations/divider-props <path>
```

## Breaking Changes

### GridLegacy Removed

`GridLegacy` is removed. Use `Grid` (Grid v2) instead:
```diff
-import Grid from '@mui/material/GridLegacy';
+import Grid from '@mui/material/Grid';

 <Grid container spacing={2}>
-  <Grid item xs={12} sm={6}>
+  <Grid size={{ xs: 12, sm: 6 }}>
```
See [grid-v2.md](grid-v2.md) for full Grid migration details.

`MuiGridLegacy` removed from theme `components` types.

### Dialog & Modal: disableEscapeKeyDown Removed

```diff
-<Dialog open={open} disableEscapeKeyDown onClose={handler}>
+<Dialog open={open} onClose={(event, reason) => { if (reason !== 'escapeKeyDown') setOpen(false); }}>
```
Same applies to `Modal`.

### Autocomplete

- Listbox no longer toggles on right click
- When `freeSolo=true`: `getOptionLabel` and `isOptionEqualToValue` accept `string` for option/value

### Backdrop

`aria-hidden="true"` no longer added to root slot by default.

### ButtonBase

- Enter/Space key click events now bubble to ancestors
- `onClick` receives `MouseEvent` (not `KeyboardEvent`) from keyboard triggers
- Event handlers no longer run on disabled non-native buttons
- New `nativeButton` prop required when `component` changes element type (e.g. `<div>` instead of `<button>`):
  ```jsx
  <Button component={CustomDivComponent} nativeButton={false}>OK</Button>
  ```
  Applies to: ButtonBase, Button, Fab, IconButton, ListItemButton, MenuItem, StepButton, Tab, ToggleButton, AccordionSummary, BottomNavigationAction, CardActionArea, TableSortLabel, PaginationItem.

### List

`ListItemIcon` default `min-width` changed from `56px` to `36px`.

### Material Icons

23 `*Outline` (without "d") exports removed — use `*Outlined` instead:
```diff
-import InfoOutlineIcon from '@mui/icons-material/InfoOutline';
+import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
```
Full list: AddCircleOutline, ChatBubbleOutline, CheckCircleOutline, DeleteOutline, DoneOutline,
DriveFileMoveOutline, ErrorOutline, HelpOutline, InfoOutline, LabelImportantOutline,
LightbulbOutline, LockOutline, MailOutline, ModeEditOutline, PauseCircleOutline,
PeopleOutline, PersonOutline, PieChartOutline, PlayCircleOutline, RemoveCircleOutline,
StarOutline, WorkOutline, WorkspacesOutline.

### Menu / MenuList

- `tabindex` updates on keyboard navigation for `variant="selectedMenu"`
- `MenuItem` throws error if rendered outside `Menu` or `MenuList`
- `ListSubheader` / `Divider` no longer need `muiSkipListHighlight`

### Slider

Uses pointer events instead of mouse events:
```diff
-onMouseDown={(event) => event.preventDefault()}
+onPointerDown={(event) => event.preventDefault()}
```

### Stepper / Step

- `Stepper` renders `<ol>` (was `<div>`)
- `Step` renders `<li>` (was `<div>`)
- Keyboard navigation added when using `StepButton` (roving tabindex)

### TablePagination

Numbers now formatted with `Intl.NumberFormat`. Opt out:
```jsx
<TablePagination
  labelDisplayedRows={({ from, to, count }) =>
    `${from}–${to} of ${count !== -1 ? count : `more than ${to}`}`
  }
/>
```

### Tabs

`tabindex` updates on Arrow Key / Home / End navigation. `Tab` outside `Tabs` now throws.

### TextField select

`<TextField select />` renders `<InputLabel>` as `<div>` instead of `<label>`.

### Theme

`MuiTouchRipple` removed from theme components types. Use global CSS via `MuiButtonBase`:
```diff
-MuiTouchRipple: { styleOverrides: { root: { color: 'red' } } }
+MuiButtonBase: { styleOverrides: { root: { '& .MuiTouchRipple-root': { color: 'red' } } } }
```

## Deprecated API Removals (Selected)

### components/componentsProps → slots/slotProps

Applies to: Accordion, Alert, Autocomplete, Avatar, AvatarGroup, Backdrop, Badge, CardHeader,
Checkbox, Dialog, Drawer, and many more. General pattern:

```diff
-<Component
-  components={{ Root: CustomRoot }}
-  componentsProps={{ root: { className: 'x' } }}
+<Component
+  slots={{ root: CustomRoot }}
+  slotProps={{ root: { className: 'x' } }}
```

### Accordion
```diff
-<Accordion TransitionComponent={C} TransitionProps={{ unmountOnExit: true }}>
+<Accordion slots={{ transition: C }} slotProps={{ transition: { unmountOnExit: true } }}>
```

### Autocomplete
- `ChipProps` → `slotProps.chip`
- `ListboxComponent` → `slots.listbox`
- `PaperComponent` → `slots.paper`
- `PopperComponent` → `slots.popper`
- `renderTags` → `renderValue`
- `getTagProps` (hook) → `getItemProps`
- `focusedTag` (hook) → `focusedItem`

### Alert CSS classes
`standardSuccess` → `.MuiAlert-standard.MuiAlert-colorSuccess` (etc.)

### Button CSS classes
`textPrimary` → `.MuiButton-text.MuiButton-colorPrimary` (etc.)
Use `variants` array in `styleOverrides.root` for theme overrides.

### Divider
```diff
-<Divider light />
+<Divider sx={{ opacity: 0.6 }} />
```
