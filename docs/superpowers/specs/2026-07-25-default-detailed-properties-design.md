# Default Detailed Properties: Structural Warnings Summary — Design

## Background

`DetailedPropertiesPanel` (in `ImageInspectorUI.kt`, shared by both the image and video inspectors via `rightPanel`) currently shows a bare "Select a marker to view details" placeholder until the user clicks a box-tree node. This wastes the panel on first open and hides a signal the app already computes but never surfaces in aggregate: every `BoxNode` carries a `warnings: List<String>` field (e.g. `"Declared length extends past the end of the file"`, seen throughout the existing box decoders), currently only visible one node at a time, by manually clicking through the whole tree.

## Goal

Before any node or GOP frame is selected, `DetailedPropertiesPanel` shows every structural warning in the currently-open file's box tree in one place — file offset, box type, and the warning text — mirroring the "surface problems immediately" philosophy of forensic tools like JPEGsnoop, instead of requiring the user to hunt through the tree node-by-node. Clicking a warning jumps the box tree to that node (same selection mechanism a manual click already uses). When there are no warnings, a short positive confirmation replaces the list — no duplicate summary data, since the separate Media Summary panel already covers that.

## Non-Goals

- No change to the panel's behavior once something IS selected (box node or GOP frame) — those paths are unchanged.
- No new background computation or state field — the warning list is a pure, cheap, synchronous walk over `tab.root` (already fully parsed), recomputed via `remember(tab.root)` directly in the composable.
- No duplication of Media Summary content (file size, dimensions, format) in this default view.

## Design

### Warning collection (`ImageInspectorUI.kt`, new private function)

```kotlin
private data class WarningEntry(val node: BoxNode, val warning: String)

private fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}
```

Sorted by file offset, matching the natural reading order of the box tree and the hex view below it.

### `DetailedPropertiesPanel` (modified)

When `tab.selectedFrame == null && tab.selected == null` (the existing two early-exit conditions), instead of falling through to the current `Text("Select a marker to view details", ...)`, compute `val warnings = tab.root?.let { remember(it) { collectWarnings(it) } } ?: emptyList()` and render:

- `warnings.isNotEmpty()`: a header (`"⚠ ${warnings.size}개의 구조적 이상 징후"`) followed by a scrollable list; each row shows the box type, its offset (`"0x${node.offset.toString(16).uppercase()}"`, same hex format the panel already uses for a selected node's own Offset field), and the warning text, and is clickable — `onClick = { tab.selected = entry.node }` (reuses the exact same state field a manual tree click already sets, so the tree highlights and this panel then switches to that node's normal detail view on the next recomposition, no new selection mechanism needed).
- `warnings.isEmpty()`: `Text("✓ 구조적 이상 없음", color = AppColors.NeonGreen)`.

If `tab.root` is itself null (still loading, or parse failed), falls back to the current unselected-state text unchanged.

## Testing

No automated test for this Composable (established convention for this project's UI layer). `collectWarnings` itself is a small pure function operating on plain `BoxNode` data — worth a direct unit test (construct a small tree with warnings at different depths, assert the flattened, offset-sorted result) even though it lives in a UI file, since it has no Compose/UI dependency and is trivially testable in isolation.

Manual verification: open a file with a known structural warning (e.g. a truncated/edited test file already used elsewhere in this codebase's test fixtures, or any real file that already shows a warning in its box tree today) and confirm the panel shows it by default with no selection; click it and confirm the tree jumps to and highlights that node. Open a clean file with no warnings and confirm the "✓" message appears instead.
