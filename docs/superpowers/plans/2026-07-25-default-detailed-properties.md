# Default Detailed Properties: Structural Warnings Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before any box-tree node or GOP frame is selected, `DetailedPropertiesPanel` shows every structural warning in the open file's box tree (offset, type, warning text, sorted by offset), replacing the current "Select a marker to view details" placeholder; clicking a warning selects that node in the tree.

**Architecture:** A new pure function walks `tab.root` (already fully parsed, no new I/O or background work) collecting every node's `warnings` into a flat, offset-sorted list. `DetailedPropertiesPanel` renders that list (via `remember(tab.root)`, recomputed only when the tree changes) instead of the placeholder text when nothing else is selected.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop.

## Global Constraints

- No new background computation or state field — pure, cheap, synchronous tree walk.
- No duplication of Media Summary content (file size, dimensions, format) in this default view.
- No change to the panel's behavior once something IS selected (box node or GOP frame).
- Spec: `docs/superpowers/specs/2026-07-25-default-detailed-properties-design.md`.

---

### Task 1: Warning collection + default panel view

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt` (new)

**Interfaces:**
- Consumes: `BoxNode` (`com.multiviewer.parser`, existing — `type: String`, `offset: Long`, `warnings: List<String>`, `children: List<BoxNode>`), `TabState.root`/`selected`/`selectedFrame` (existing, same package).
- Produces: `data class WarningEntry(val node: BoxNode, val warning: String)` and `fun collectWarnings(root: BoxNode): List<WarningEntry>` — not `private`, so the new test file (a different file in the same package) can call them directly; nothing later in this plan depends on them beyond this task.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageInspectorUITest {
    @Test
    fun `collectWarnings flattens warnings from every depth, sorted by offset`() {
        val deepChild = BoxNode(
            type = "DHT", offset = 100, headerSize = 4, size = 10,
            warnings = listOf("Huffman table truncated"),
        )
        val midChild = BoxNode(
            type = "APP1", offset = 20, headerSize = 4, size = 50,
            warnings = listOf("Declared length extends past the end of the file"),
            children = listOf(deepChild),
        )
        val cleanChild = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 200,
            children = listOf(cleanChild, midChild),
        )

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("APP1", warnings[0].node.type)
        assertEquals("Declared length extends past the end of the file", warnings[0].warning)
        assertEquals("DHT", warnings[1].node.type)
        assertEquals("Huffman table truncated", warnings[1].warning)
    }

    @Test
    fun `collectWarnings returns an empty list for a tree with no warnings anywhere`() {
        val child = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 2, children = listOf(child))

        assertEquals(emptyList(), collectWarnings(root))
    }

    @Test
    fun `collectWarnings includes a node with multiple warnings once per warning`() {
        val child = BoxNode(
            type = "DQT", offset = 5, headerSize = 4, size = 10,
            warnings = listOf("first issue", "second issue"),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 20, children = listOf(child))

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("first issue", warnings[0].warning)
        assertEquals("second issue", warnings[1].warning)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.ImageInspectorUITest" --console=plain`
Expected: FAIL to compile — `collectWarnings`/`WarningEntry` don't exist yet.

- [ ] **Step 3: Add `WarningEntry` and `collectWarnings`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, add the import:

```kotlin
import com.multiviewer.parser.BoxNode
```

alongside the existing `import com.multiviewer.parser.EmbeddedVideo` line. Then add, right before `fun DetailedPropertiesPanel(tab: TabState) {`:

```kotlin
data class WarningEntry(val node: BoxNode, val warning: String)

fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}

```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.ImageInspectorUITest" --console=plain`
Expected: PASS, all three tests green.

- [ ] **Step 5: Replace the default (nothing-selected) view in `DetailedPropertiesPanel`**

In the same file, replace:

```kotlin
        } else {
            Text("Select a marker to view details", style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary))
        }
    }
}
```

with:

```kotlin
        } else {
            val root = tab.root
            val warnings = if (root != null) remember(root) { collectWarnings(root) } else emptyList()
            if (warnings.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "⚠ ${warnings.size}개의 구조적 이상 징후",
                            style = AppTypography.labelLarge.copy(color = AppColors.NeonRed),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(warnings) { entry ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { tab.selected = entry.node },
                        ) {
                            Column {
                                Text(
                                    "${entry.node.type} — 0x${entry.node.offset.toString(16).uppercase()}",
                                    style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontSize = 11.sp),
                                )
                                Text(
                                    entry.warning,
                                    style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed, fontSize = 11.sp),
                                )
                            }
                        }
                    }
                }
            } else {
                Text("✓ 구조적 이상 없음", style = AppTypography.bodyLarge.copy(color = AppColors.NeonGreen))
            }
        }
    }
}
```

This needs `androidx.compose.foundation.clickable` — add the import alongside the existing `androidx.compose.foundation.background`/`androidx.compose.foundation.border` lines:

```kotlin
import androidx.compose.foundation.clickable
```

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 7: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: app window opens with no build errors.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt
git commit -m "Show structural warnings summary by default in Detailed Properties"
```

- [ ] **Step 9: Manually verify**

Open a file that has at least one structural warning somewhere in its box tree (any real file already showing a warning badge in the tree view today works) — confirm the right panel shows "⚠ N개의 구조적 이상 징후" with a list by default, with no node selected. Click a warning entry and confirm the left box tree jumps to and highlights that node, and the right panel switches to that node's normal detail view. Open a clean file with no warnings anywhere and confirm "✓ 구조적 이상 없음" shows instead. Click a GOP frame or a tree node manually and confirm those views still work exactly as before (unaffected by this change).
