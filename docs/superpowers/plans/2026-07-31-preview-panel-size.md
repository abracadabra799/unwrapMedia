# Preview Panel Default Size Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the preview panel (thumbnail/image and video playback + GOP) bigger by default, by changing its starting share of the center column's height from 50% to 70%.

**Architecture:** Change one `remember { mutableStateOf(0.5f) }` initial value to `0.7f` in each of two files. No structural change -- the existing user-draggable `DraggableDivider` mechanism is untouched.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- Only the initial `verticalSplit` value changes, from `0.5f` to `0.7f`, in both `ImageInspectorUI.kt` and `VideoInspectorUI.kt` -- no other state, layout, or behavior changes (per spec's Design section).
- `videoGopSplit` in `VideoInspectorUI.kt` (the split between the player and the GOP graph within the top region) is untouched -- both grow together, keeping their existing internal proportions.
- `DashboardLayout`'s left/right panels are untouched.

---

### Task 1: Increase default preview panel size in both inspectors

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:46`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:35`

**Interfaces:**
- Consumes: nothing new -- both files already declare `var verticalSplit by remember { mutableStateOf(0.5f) }` and pass `verticalSplit`/`1f - verticalSplit` as `Modifier.weight(...)` to their top (preview) and bottom (summary) regions.
- Produces: nothing new -- this is a value-only change, no new function or state.

- [ ] **Step 1: Confirm current values before editing**

Run: `grep -n "var verticalSplit by remember" app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected output:
```
app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:46:    var verticalSplit by remember { mutableStateOf(0.5f) }
app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:35:    var verticalSplit by remember { mutableStateOf(0.5f) }
```

If either line differs from this (e.g. the file has changed since this plan was written), stop and read the full surrounding function before editing -- do not guess at the edit.

- [ ] **Step 2: Edit ImageInspectorUI.kt**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, find:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.5f) }
```

Replace with:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.7f) }
```

- [ ] **Step 3: Edit VideoInspectorUI.kt**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, find:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.5f) }
```

Replace with:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.7f) }
```

Do NOT touch the next line (`var videoGopSplit by remember { mutableStateOf(0.65f) }`) -- that ratio is unrelated to this task and must stay exactly as-is.

- [ ] **Step 4: Verify both edits landed correctly**

Run: `grep -n "var verticalSplit by remember" app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected output:
```
app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:46:    var verticalSplit by remember { mutableStateOf(0.7f) }
app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:35:    var verticalSplit by remember { mutableStateOf(0.7f) }
```

Also run: `grep -n "var videoGopSplit by remember" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` and confirm it still reads `mutableStateOf(0.65f)`, unchanged.

- [ ] **Step 5: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (both edits are literal float value changes with no new symbols, no import changes needed).

- [ ] **Step 6: Run the full test suite (regression check)**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, same pass count as before this change (this task does not add, remove, or affect any test -- both edits are inside `@Composable` functions with no automated coverage, consistent with the spec's Testing section).

- [ ] **Step 7: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`). Open an image file and confirm the thumbnail+image preview area now visibly occupies more vertical space than the summary dashboard below it (roughly 70/30 instead of 50/50). Open a video file and confirm the same for the player+GOP region vs. the summary dashboard below it. Confirm the draggable divider between them still works (drag it and see the ratio change).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Increase default preview panel size from 50% to 70% of center column height"
```

---
