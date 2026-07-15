# Panel Dividers and Hierarchical Tree View — Design

## Background

The main window currently has no visible boundary between the box tree (left) and the hex view (right), or between the top row (tree + hex) and the bottom panel (field panel / table view). Additionally, the box tree shows parent/child relationships only through text indentation — there is no expand/collapse indicator, so the hierarchy is hard to read at a glance. The user referenced Android Studio's Project view (file tree sidebar) as the target look: clear disclosure arrows and a visually legible hierarchy.

## Goal

1. Add a visible divider between the tree view and hex view, and between the top row and the bottom panel.
2. Give each tree row an explicit expand/collapse indicator (▶ collapsed / ▼ expanded) for nodes with children, with a vertical guide line per depth level connecting parent to children — matching the visual pattern of a standard file-tree sidebar.

## Non-Goals

- No new interaction model — clicking a row still both selects it and toggles expansion, exactly as today. Only the visual presentation changes.
- No resizable/draggable dividers — they are static visual separators only, not drag handles.
- No per-node icons (file-type icons, box-type icons) — only the expand/collapse arrow and the guide line are added.
- No new tests — this project's UI layer has no automated tests by design (parser is unit-tested; UI is verified by manual/process-level checks, per the original design spec and the ASCII-column feature's spec). This change follows the same approach: compile + manual run-through.

## Design

### 1. Dividers (`Main.kt`)

Two 1dp-thick static divider lines, colored `Color.DarkGray`, added via a `Box`/`Spacer`-style modifier (`Modifier.width(1.dp).fillMaxHeight().background(Color.DarkGray)` for the vertical one, `Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray)` for the horizontal one):

- **Vertical divider:** inserted between the tree `Column` and the hex `Column` inside the existing `Row(modifier = Modifier.weight(1f).fillMaxWidth())`.
- **Horizontal divider:** inserted between that `Row` and the bottom `Column(modifier = Modifier.weight(0.3f)...)` in the outer `Column`.

No other layout changes — existing `weight()` proportions on the tree/hex columns and the top/bottom split are preserved.

### 2. Hierarchical Tree View (`BoxTreeView.kt`)

**Expand/collapse indicator:** each row gets a fixed-width leading area (before the existing text label) showing:
- `▼` if the node has children and is expanded
- `▶` if the node has children and is collapsed
- blank space of the same width if the node has no children (leaf), so all labels across all rows align on the same starting column regardless of whether the arrow is present

**Depth guide lines:** for each depth level greater than 0, render a thin vertical line (1dp wide, light gray, using the same per-depth indent width already used today, i.e. 16dp per level) spanning the row's height, positioned at each ancestor's indent column. This is drawn using a `Row` of fixed-width `Box`es (one per depth level) each either containing a centered vertical divider line or being empty, followed by the arrow and label — matching the classic file-tree "rail" look (e.g. Android Studio, VS Code).

**Implementation approach:** Replace the current single `Text(...)` per row with a `Row` per row:
1. `row.depth` fixed-width `Box`es (16dp each), each drawing a 1dp-wide vertical line down the middle if that ancestor level is not the last child at that depth... — simplified for this project: draw the guide line unconditionally for every depth box (no "last child" corner-cases, no horizontal elbow connectors). This keeps the implementation simple while still delivering the core visual win (clear vertical lineage), consistent with the project's YAGNI principle. If this looks too busy in practice, it can be refined later.
2. The arrow glyph (`▼`/`▶`/blank), fixed width (e.g. 16dp), clickable together with the rest of the row (no separate hit-target — same click behavior as today, applied to the whole `Row`).
3. The existing label `Text` (from `buildLabel(node)`), unchanged.

The existing `Modifier.background(if (isSelected) ...)` and `.clickable { ... }` move from the `Text` to the wrapping `Row`, so the whole row (guide rail + arrow + label) is one clickable unit, same as today.

**Data flow:** unchanged. `FlatRow`, `flatten()`, `expanded` state, and `buildLabel()` are all reused as-is.

## Testing / Verification

Compile (`./gradlew compileKotlin`), then run (`./gradlew run`) and manually confirm:
- A visible 1dp divider line separates the tree from the hex view, and separates the top row from the bottom panel.
- Each tree node with children shows `▶` when collapsed and `▼` when expanded; leaf nodes show no arrow but their label still aligns with sibling arrows' label start position.
- Vertical guide lines are visible connecting each depth level, similar to a standard file-tree sidebar.
- Clicking anywhere on a row still selects it and (if it has children) toggles expand/collapse, exactly as before.
- Selected-row highlight (light gray background) still applies to the full row.
