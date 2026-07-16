# Resizable Panels — Design

## Background

The main window's panel proportions are currently fixed: tree/detail-panel split 50/50 (both `weight(1f)`), and the top row/hex-dump split is roughly 77/23 (`weight(1f)`/`weight(0.3f)`). The user finds the hex dump area too small and wants to be able to resize it themselves, by dragging the divider lines added in a prior feature.

## Goal

Let the user drag both existing divider lines (tree ↔ detail panel, top row ↔ hex dump) to resize the adjacent panels, per open tab, for the current app session.

## Non-Goals

- No persistence across app restarts — each new session (and each newly opened file) starts from the same default ratios as today.
- No resize cursor icon, no double-click-to-reset, no keyboard-driven resize.
- No automatic ratio rebalancing on window resize — because panels are sized by fraction (`weight`), they already scale proportionally when the window resizes; no extra logic is needed for that case.
- No new tests — this project's UI layer has no automated tests by design (parser is unit-tested; UI is verified by manual/process-level checks). Verify by compiling and running.

## Design

### 1. Per-tab split state (`AppState.kt`)

`TabState` gains two new fields, both backed by `mutableStateOf` so Compose recomposes on change:

```kotlin
var verticalSplit: Float by mutableStateOf(0.5f)
var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
```

- `verticalSplit` is the tree column's share of the top row's width; the detail panel column gets `1f - verticalSplit`. Default `0.5f` matches today's 50/50 `weight(1f)`/`weight(1f)` split.
- `horizontalSplit` is the top row's share of the window's content height; the hex dump column gets `1f - horizontalSplit`. Default `1f / 1.3f` (≈0.769) matches today's `weight(1f)`/`weight(0.3f)` split exactly (since `1f : 0.3f` reduces to the same ratio as `(1f/1.3f) : (0.3f/1.3f)`).

Both fields live on `TabState`, so each open tab remembers its own ratios independently, and switching tabs restores that tab's last-dragged ratios. Neither field is written to disk or otherwise persisted — a fresh `TabState` (new file, or new app run) always starts at the defaults above.

### 2. Draggable divider composable (`Main.kt`)

Both existing static divider `Box`es (`Modifier.width(1.dp).fillMaxHeight().background(Color.DarkGray)` and `Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray)`) are replaced with a small helper that adds drag handling while keeping the same 1dp visual line:

- The draggable region is wider than the visible line — an outer `Box` sized 8dp (instead of 1dp) along the drag axis, so the line is easy to grab with the mouse, with the visible 1dp `Color.DarkGray` line centered inside it. This is a purely interactive hit-target change; the visible seam still reads as a thin 1dp line.
- The gesture is implemented with `Modifier.pointerInput(Unit) { detectDragGestures(onDrag = { change, dragAmount -> ... }) }`. Each drag callback receives the pointer's movement in pixels for this frame.
- To convert a pixel delta into a fraction delta, the parent container's total size in pixels is captured once via `Modifier.onGloballyPositioned { coordinates -> ... }` on the `Row` (for the vertical divider, capturing `coordinates.size.width`) and on the outer `Column` (for the horizontal divider, capturing `coordinates.size.height`), stored in a `remember { mutableStateOf(0) }` updated on layout.
- On each drag event: `newSplit = (currentSplit + dragAmount / containerSizePx).coerceIn(0.15f, 0.85f)`, written back to `currentTab.verticalSplit` (or `horizontalSplit`). The `0.15f..0.85f` clamp guarantees neither adjacent panel can shrink past 15% or grow past 85%, so dragging can never fully collapse a panel out of view.

### 3. Wiring into the layout (`Main.kt`)

The four `weight(...)` call sites in the `currentTab.root != null` branch change from hardcoded constants to the tab's live state:

- Tree column: `Modifier.weight(currentTab.verticalSplit).fillMaxWidth()`
- Detail-panel column: `Modifier.weight(1f - currentTab.verticalSplit).fillMaxWidth()`
- Top `Row`: `Modifier.weight(currentTab.horizontalSplit).fillMaxWidth()`
- Hex-dump column: `Modifier.weight(1f - currentTab.horizontalSplit).fillMaxWidth()`

No other structural change to the `Column`/`Row` nesting — the tree, detail panel, and hex dump composables themselves (`BoxTreeView`, `FieldPanel`/`TableView`, `HexView`) are unmodified; only the modifiers on their wrapping containers change from fixed to state-driven weights.

## Testing / Verification

Compile (`./gradlew compileKotlin`), then run (`./gradlew run`) and manually confirm:
- Dragging the vertical divider (between tree and detail panel) resizes both columns smoothly, and the visible 1dp line stays under the cursor throughout the drag.
- Dragging the horizontal divider (between the top row and the hex dump) resizes both regions smoothly.
- Neither drag can shrink a panel below a small usable sliver, nor grow one to fully cover its neighbor (both clamped at 15%/85%).
- Switching between two open tabs preserves each tab's own last-dragged ratios independently.
- Opening a brand-new file starts that tab at the default ratios (50/50 vertical, ~77/23 horizontal), unaffected by other tabs' adjustments.
- Resizing the app window preserves each panel's proportion (no extra work required beyond the existing `weight`-based layout).
