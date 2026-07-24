# Tree View Category Badges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small colored letter badge to each row in the left "Media Structure" tree, indicating one of four categories (Container / Table / Image / Metadata) derived from data already on `BoxNode`, plus a red ring on badges for nodes with warnings.

**Architecture:** A new `NodeCategory` enum (color + single-letter label) and a pure `categorize(node: BoxNode): NodeCategory` function in `BoxTreeView.kt`, using existing `BoxNode` fields (`table`, `grid`, `children`, `type`) — no parser changes needed. Rendered as a small `CircleShape` `Box` inserted into the existing per-row `Row` in `BoxTreeView`, right before the label `Text`. Four new pastel colors added to `AppColors` in `Theme.kt`.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop. No new dependency — plain ASCII letters in colored circles (not icons/emoji), matching the outcome of ruling out `material-icons-extended` (icon set gap) and emoji (cross-platform rendering risk on Windows/Linux) during design.

## Global Constraints

- Category badge letters are plain ASCII (`F`, `T`, `I`, `M`) — no emoji, no icon font, no new Gradle dependency.
- Categorization priority: `table != null` → Table, else `grid != null || type == "ThumbnailImage"` → Image, else `children.isNotEmpty()` → Container, else → Metadata (catch-all). This exact order, so a node with both `.table` and children is still categorized as Table (more specific signal wins).
- Warning ring (`1.5.dp` `AppColors.NeonRed` border) is additive to the category color, not a replacement — both signals visible together. Does not change the existing `⚠` text prefix in `buildLabel`.
- Only `BoxTreeView.kt` and `Theme.kt` change — no change to selection background, expand arrows, indentation guides, or any other file (explicitly out of scope per the user).
- Spec: `docs/superpowers/specs/2026-07-24-tree-category-badges-design.md`.

---

### Task 1: Add category badges to the tree view

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/Theme.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt`

**Interfaces:**
- Consumes: `BoxNode.table: TableData?`, `BoxNode.grid: GridData?`, `BoxNode.children: List<BoxNode>`, `BoxNode.type: String`, `BoxNode.warnings: List<String>` (all existing, `BoxNode.kt`, unchanged).
- Produces: nothing consumed by other tasks — this is the only task in this plan.

No automated test: `BoxTreeView.kt` has no existing Compose UI test coverage, matching this project's convention for Compose-layer files.

- [ ] **Step 1: Add the four pastel badge colors**

In `app/src/main/kotlin/com/multiviewer/ui/Theme.kt`, replace:

```kotlin
    val NeonGreen = Color(0xFF39FF14)
    val NeonBlue = Color(0xFF00F3FF)
    val NeonPurple = Color(0xFFBC13FE)
    val NeonRed = Color(0xFFFF3131)
    val NeonYellow = Color(0xFFFFF01F)
    
    val TextPrimary = Color(0xFFC9D1D9)
```

with:

```kotlin
    val NeonGreen = Color(0xFF39FF14)
    val NeonBlue = Color(0xFF00F3FF)
    val NeonPurple = Color(0xFFBC13FE)
    val NeonRed = Color(0xFFFF3131)
    val NeonYellow = Color(0xFFFFF01F)

    val BadgeAmber = Color(0xFFFFB74D)    // Container
    val BadgeTeal = Color(0xFF4DD0C4)     // Table data
    val BadgeLavender = Color(0xFFCE93D8) // Image/grid data
    val BadgeSky = Color(0xFF64B5F6)      // Metadata / leaf (catch-all)
    
    val TextPrimary = Color(0xFFC9D1D9)
```

- [ ] **Step 2: Add the new imports to `BoxTreeView.kt`**

Replace:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode
```

with:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
```

- [ ] **Step 3: Add the `BADGE_SIZE_DP` constant**

Replace:

```kotlin
private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 16
```

with:

```kotlin
private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 16
private const val BADGE_SIZE_DP = 16
```

- [ ] **Step 4: Insert the badge into the row, right before the label**

Replace:

```kotlin
                Box(
                    modifier = Modifier.width(ARROW_WIDTH_DP.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.node.children.isNotEmpty()) {
                        Text(if (isExpanded) "▼" else "▶", color = AppColors.TextPrimary)
                    }
                }
                Text(text = buildLabel(row.node), color = if (isSelected) Color.White else AppColors.TextPrimary)
```

with:

```kotlin
                Box(
                    modifier = Modifier.width(ARROW_WIDTH_DP.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.node.children.isNotEmpty()) {
                        Text(if (isExpanded) "▼" else "▶", color = AppColors.TextPrimary)
                    }
                }
                val category = categorize(row.node)
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(BADGE_SIZE_DP.dp)
                        .align(Alignment.CenterVertically)
                        .background(category.color, CircleShape)
                        .then(
                            if (row.node.warnings.isNotEmpty()) {
                                Modifier.border(1.5.dp, AppColors.NeonRed, CircleShape)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(category.letter, color = Color.Black.copy(alpha = 0.7f), fontSize = 9.sp)
                }
                Text(text = buildLabel(row.node), color = if (isSelected) Color.White else AppColors.TextPrimary)
```

- [ ] **Step 5: Add the `NodeCategory` enum and `categorize` function**

In the same file, add this immediately after the `buildLabel` function at the end of the file:

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

- [ ] **Step 6: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (confirms both files compile cleanly).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/Theme.kt app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt
git commit -m "Add category badges to the Media Structure tree view"
```

- [ ] **Step 8: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors.

- [ ] **Step 9: Manually verify against a file with a rich structure**

Open a Motion Photo HEIC with `iloc`/`iinf`/thumbnails (e.g. `/Users/dong.kim/Downloads/20260715_223835.heic`).

Expected:
- Container boxes (`meta`, `iinf`, `iloc`, `iprp`, etc.) show an amber circle with "F".
- Table-backed boxes (`stsz`, `stsc`, `stco`, `stts`, if present in the tree for any embedded/related video structure) show a teal circle with "T".
- Any `ThumbnailImage` or grid-backed node shows a lavender circle with "I".
- Plain leaf/EXIF-tag nodes (e.g. under `IFD0`/`Exif`/`GPS`) show a sky-blue circle with "M".
- Any node with a warning (visible via the existing `⚠` text prefix) also shows a red ring around its badge.
- Selection, expand arrows, and indentation guides look exactly as before — only the new badges are visually different.
