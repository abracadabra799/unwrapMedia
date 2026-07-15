# Layout Swap and Compact Typography — Design

## Background

The main window currently arranges the tree view and hex dump side by side in the top row, with the detail panel (field panel / table view) as a full-width strip at the bottom. The user wants the hex dump moved to the bottom (full width) and the detail panel moved to the right, next to the tree — closer to how a hex editor typically prioritizes screen space (tree + detail summary up top, raw bytes below). Separately, the app currently renders every piece of text (tree labels, hex dump, field panel, table view, buttons, tabs) at Compose Material3's default type scale, which the user finds too large for a data-dense viewer.

## Goal

1. Swap the screen positions of the hex dump and the detail panel (field panel / table view), keeping the tree view's position and the existing divider lines unchanged.
2. Reduce the app's default font size globally, from one place, without touching individual `Text()` call sites.

## Non-Goals

- No change to the tree view's position, width, or the divider styling introduced by the prior feature (`docs/superpowers/specs/2026-07-15-tree-hierarchy-and-divider-design.md`).
- No per-component font size overrides (e.g. making the hex dump a different size than the field panel) — this is a single global reduction.
- No change to `HexView`, `BoxTreeView`, `FieldPanel`, or `TableView` internals — only their position in `Main.kt` changes, and their text shrinks as a side effect of the global typography change, not a per-file edit.
- No new tests — this project's UI layer has no automated tests by design. Verify by compiling and running.

## Design

### 1. Layout swap (`Main.kt`)

In the `currentTab.root != null` branch, the top `Row`'s second `Column` (currently `HexView`) and the bottom `Column` (currently `FieldPanel`/`TableView`) swap contents:

- Top `Row` (`weight(1f)`): first `Column` (`weight(1f)`) unchanged — still `BoxTreeView`. Vertical divider unchanged. Second `Column` (`weight(1f)`) now renders the detail panel (`TableView` if `selectedNode?.table != null`, else `FieldPanel`).
- Horizontal divider unchanged, still between the top `Row` and the bottom `Column`.
- Bottom `Column` (`weight(0.3f)`, `fillMaxWidth()`) now renders `HexView` instead of the detail panel.

Weight values are unchanged (`1f`/`1f` for the top row's two columns, `0.3f` for the bottom) — this is a pure content swap, not a proportion change.

### 2. Compact typography (`Main.kt`)

None of the app's `Text()` calls specify an explicit `style`, `fontSize`, or `FontFamily` (confirmed by search — zero matches for `fontSize`/`FontFamily`/`Typography` outside this change). Every plain `Text()` renders via Material3's ambient `LocalTextStyle`, which `MaterialTheme` sets to `typography.bodyLarge` (default 16sp); every `Button` label and `Tab` label renders via `typography.labelLarge` (default 14sp) internally.

`Main.kt` currently wraps its content in a bare `MaterialTheme { ... }`. This changes to `MaterialTheme(typography = compactTypography) { ... }`, where `compactTypography` is built once, at file scope, from Material3's default `Typography()` with only `bodyLarge` and `labelLarge` overridden to a smaller `fontSize` (13.sp for both), copying every other property (line height, letter spacing, weight) from the default so spacing doesn't break:

```kotlin
private val compactTypography = Typography().let { defaults ->
    defaults.copy(
        bodyLarge = defaults.bodyLarge.copy(fontSize = 13.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 13.sp),
    )
}
```

This is a single, file-scoped constant referenced once at the `MaterialTheme` call site — no other file changes, and every screen (tree, hex dump, field panel, table view, buttons, tabs) shrinks uniformly since they all route through these two ambient styles today.

## Testing / Verification

Compile (`./gradlew compileKotlin`), then run (`./gradlew run`) and manually confirm:
- The detail panel (field panel or table view, depending on selection) now appears to the right of the tree, in the top row.
- The hex dump now appears as a full-width panel at the bottom.
- Both existing divider lines (vertical between tree/detail, horizontal between top row/bottom panel) are still present and in the same relative position (between the same two regions, just with swapped content).
- All text across the app (tree labels, hex bytes/ASCII, field panel rows, table view rows, "Open File" button, tab labels) renders visibly smaller than before this change.
- Selecting a tree node still updates the detail panel (right) and highlights the corresponding bytes in the hex dump (bottom), exactly as before.
