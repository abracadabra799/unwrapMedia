# Tree View Category Badges — Design

## Background

The left "Media Structure" tree (`BoxTreeView.kt`) currently renders each node as plain text: an optional `▶`/`▼` expand arrow, then `type — summary`, with a flat-color background fill when selected. The user wants a "cuter" look, referencing two mockup screenshots whose tree views show small colored icon badges per node type. `material-icons-core` (the only icon library currently on the classpath) doesn't include the needed icon set (`Folder`, `Image`, `TableChart`, etc. are absent — confirmed by inspecting the jar's contents), and `material-icons-extended` was ruled out to avoid a new dependency. Emoji were also ruled out: while safe on macOS, they render inconsistently on Windows (complex/ZWJ sequences) and are frequently missing entirely on Linux (no bundled color-emoji font on many distros) — a real risk for this app, which is packaged as DMG/MSI/DEB for all three platforms.

Confirmed with the user: category-based badges (not one icon per exact box type — there are 50+ distinct types, an exhaustive mapping is impractical), rendered as plain-ASCII-letter badges in colored circles (font-independent, zero new dependency, safe on every platform).

## Goal

Each row in the tree gets a small (16dp) colored circle badge, immediately before the label, indicating which of four categories the node falls into — derived automatically from data already on `BoxNode`, so newly-added box types fall into a sensible category with no code change needed. Nodes with warnings get a red ring around their badge (in addition to the existing `⚠` text prefix, unchanged). Everything else about the tree (expand arrows, selection background, indentation guides) is unchanged — the user explicitly scoped this to the badges only.

## Non-Goals

- No rounded/pill selection highlight, no chevron redesign, no spacing changes — out of scope per the user.
- No per-exact-type icon mapping (e.g. a distinct badge for `ftyp` vs `moov` vs `mdat`) — category-based only.
- No new dependency (`material-icons-extended` or otherwise) and no emoji.

## Design

### 1. New pastel badge colors (`Theme.kt`)

The existing `Neon*` palette is deliberately saturated/harsh (dark hacker theme) — wrong tone for "cute" badges. Add a small set of softer colors to `AppColors`:

```kotlin
val BadgeAmber = Color(0xFFFFB74D)   // Container
val BadgeTeal = Color(0xFF4DD0C4)    // Table data
val BadgeLavender = Color(0xFFCE93D8) // Image/grid data
val BadgeSky = Color(0xFF64B5F6)     // Metadata / leaf (catch-all)
```

### 2. Categorization + badge rendering (`BoxTreeView.kt`)

```kotlin
private enum class NodeCategory(val color: Color, val letter: String) {
    Container(AppColors.BadgeAmber, "F"),
    Table(AppColors.BadgeTeal, "T"),
    Image(AppColors.BadgeLavender, "I"),
    Metadata(AppColors.BadgeSky, "M"),
}

private fun categorize(node: BoxNode): NodeCategory = when {
    node.table != null -> NodeCategory.Table
    node.grid != null || node.type == "ThumbnailImage" -> NodeCategory.Image
    node.children.isNotEmpty() -> NodeCategory.Container
    else -> NodeCategory.Metadata
}
```

Priority order matters: a container node that also happens to carry `.table`/`.grid` data (none currently do, but the check order future-proofs it) is categorized by its data shape first, "has children" last — matching the intent that `.table`/`.grid` presence is a stronger, more specific signal than the generic "is a container" fact.

Rendered as a small `Box` (16dp, `CircleShape`, `category.color` background) with the single-letter `Text` centered, inserted into the existing `Row` right before the label `Text`, after the expand-arrow `Box`. When `node.warnings.isNotEmpty()`, the badge additionally gets a 1.5dp `AppColors.NeonRed` border (`Modifier.border(1.5.dp, AppColors.NeonRed, CircleShape)`) — layered on top of the category color, not replacing it, so both signals (what kind of node, and whether it has a warning) are visible at once.

## Testing

- No automated test: `BoxTreeView.kt` has no existing Compose UI test coverage, matching this project's established convention for Compose-layer files.
- Manual: open a file with a rich structure (e.g. a Motion Photo HEIC with `iloc`/`iinf`/tables/thumbnails) and confirm: container boxes (meta, iinf, iloc, moov, trak, etc.) show the amber "F" badge; `stsz`/`stsc`/`stco`/`stts` (table-backed) show the teal "T" badge; any `ThumbnailImage` or grid-backed node shows the lavender "I" badge; plain leaf/EXIF-tag nodes show the sky-blue "M" badge; any node with a warning (e.g. a truncated box) shows a red ring around its badge in addition to its category color and the existing `⚠` text prefix.
