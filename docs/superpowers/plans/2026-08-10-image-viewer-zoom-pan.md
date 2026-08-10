# Image Viewer Zoom/Pan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mouse-wheel/trackpad cursor-anchored zoom and drag-to-pan (with double-click reset) to `PixelInspectorPreview` — the single composable behind both the embedded EXIF thumbnail box and the primary image view box in the image inspector.

**Architecture:** Two pure top-level functions (`zoomTowardPoint`, `clampPanOffset`) hold all the numeric logic and are unit-tested directly. `PixelInspectorPreview` wires them to `onPointerEvent(Scroll)` (zoom), a drag gesture (pan), a double-tap gesture (reset), and a `graphicsLayer` transform on the existing `Image` composable.

**Tech Stack:** Kotlin, Compose Desktop (`graphicsLayer`, `pointerInput`, `onPointerEvent`).

## Global Constraints

- Zoom range: `1f` (original fit view) to `MAX_ZOOM_SCALE = 8f`.
- Zoom is cursor-anchored (the content point under the cursor stays under the cursor as scale changes), not center-anchored.
- `ZOOM_STEP_FACTOR = 0.08f`, matching `FfmpegAudioPlayer.kt`'s existing zoom step for a consistent scroll-to-zoom feel across the app.
- Pan is clamped so scaled content can never be dragged far enough to show empty space beyond its own edge.
- Double-click resets to `scale = 1f`, `offset = Offset.Zero`.
- Zoom/pan state resets automatically per `bitmap` (the embedded thumbnail and primary image are separate `PixelInspectorPreview` call sites with separate `ImageBitmap`s, so this happens for free via `remember(bitmap)`).
- Video player (`FfmpegVideoPlayer`) is explicitly out of scope — do not touch it.

---

### Task 1: Zoom and pan math (`zoomTowardPoint`, `clampPanOffset`)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/PixelInspectorPreviewTest.kt` (new)

**Interfaces:**
- Produces: `const val MAX_ZOOM_SCALE = 8f`, `const val ZOOM_STEP_FACTOR = 0.08f`, `fun zoomTowardPoint(scale: Float, offset: Offset, cursorPosition: Offset, scrollDeltaY: Float): Pair<Float, Offset>`, `fun clampPanOffset(offset: Offset, boxSize: Size, scale: Float): Offset` — Task 2 wires all four into the `PixelInspectorPreview` composable in the same file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/PixelInspectorPreviewTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixelInspectorPreviewTest {
    @Test
    fun `zoomTowardPoint increases scale on scroll-up (negative delta) and keeps the cursor point fixed`() {
        val (newScale, newOffset) = zoomTowardPoint(
            scale = 1f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = -1f,
        )
        assertTrue(newScale > 1f, "Expected scale to increase on scroll-up, got $newScale")

        // Cursor-anchored zoom: the content point under the cursor before the zoom --
        // (cursorPosition - offset) / scale -- must be the same content point after the zoom.
        val contentPointBeforeX = (100f - 0f) / 1f
        val contentPointBeforeY = (50f - 0f) / 1f
        val contentPointAfterX = (100f - newOffset.x) / newScale
        val contentPointAfterY = (50f - newOffset.y) / newScale
        assertTrue(kotlin.math.abs(contentPointBeforeX - contentPointAfterX) < 0.01f)
        assertTrue(kotlin.math.abs(contentPointBeforeY - contentPointAfterY) < 0.01f)
    }

    @Test
    fun `zoomTowardPoint decreases scale on scroll-down (positive delta)`() {
        val (newScale, _) = zoomTowardPoint(
            scale = 2f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = 1f,
        )
        assertTrue(newScale < 2f, "Expected scale to decrease on scroll-down, got $newScale")
    }

    @Test
    fun `zoomTowardPoint never scales below 1x`() {
        val (newScale, _) = zoomTowardPoint(
            scale = 1f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = 100f,
        )
        assertEquals(1f, newScale)
    }

    @Test
    fun `zoomTowardPoint never scales above MAX_ZOOM_SCALE`() {
        val (newScale, _) = zoomTowardPoint(
            scale = MAX_ZOOM_SCALE, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = -100f,
        )
        assertEquals(MAX_ZOOM_SCALE, newScale)
    }

    @Test
    fun `clampPanOffset is always zero at scale 1`() {
        val clamped = clampPanOffset(Offset(500f, 500f), Size(400f, 300f), scale = 1f)
        assertEquals(Offset.Zero, clamped)
    }

    @Test
    fun `clampPanOffset bounds pan to half the overhanging scaled size`() {
        // At scale 2, a 400x300 box's content is 800x600 -- it overhangs the box by 400x300,
        // so the content can be dragged at most 200 (x) / 150 (y) in either direction before
        // its own edge would reveal empty space.
        val clamped = clampPanOffset(Offset(9999f, 9999f), Size(400f, 300f), scale = 2f)
        assertEquals(200f, clamped.x)
        assertEquals(150f, clamped.y)
    }

    @Test
    fun `clampPanOffset leaves an in-bounds offset unchanged`() {
        val clamped = clampPanOffset(Offset(50f, 30f), Size(400f, 300f), scale = 2f)
        assertEquals(Offset(50f, 30f), clamped)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelInspectorPreviewTest"`
Expected: FAIL to compile — `zoomTowardPoint`, `clampPanOffset`, `MAX_ZOOM_SCALE` are unresolved references (they don't exist yet).

- [ ] **Step 3: Add the pure functions**

In `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`, add these imports and top-level declarations (keep the existing `PixelInspectorPreview` composable below them for now — Task 2 rewrites its body):

```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
```

```kotlin
const val MAX_ZOOM_SCALE = 8f
private const val ZOOM_STEP_FACTOR = 0.08f

// Cursor-anchored zoom: re-derives offset so the content point currently under the cursor stays
// under the cursor after the scale change, instead of zooming around the box's center (which
// would make whatever the user is actually looking at drift away as they zoom in). Matches the
// scroll-up-zooms-in sign convention already established by FfmpegAudioPlayer.kt's own zoom step.
fun zoomTowardPoint(scale: Float, offset: Offset, cursorPosition: Offset, scrollDeltaY: Float): Pair<Float, Offset> {
    val newScale = (scale * (1f - scrollDeltaY * ZOOM_STEP_FACTOR)).coerceIn(1f, MAX_ZOOM_SCALE)
    val newOffset = Offset(
        cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
        cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
    )
    return newScale to newOffset
}

// Bounds pan so the scaled content can never be dragged far enough to reveal empty space beyond
// its own edge -- half of however much the scaled content now overhangs the box's own bounds on
// that axis. At scale == 1 this collapses to exactly 0f..0f (no pan possible), so callers don't
// need a separate "only pan when zoomed in" branch anywhere.
fun clampPanOffset(offset: Offset, boxSize: Size, scale: Float): Offset {
    val maxX = ((boxSize.width * scale) - boxSize.width) / 2f
    val maxY = ((boxSize.height * scale) - boxSize.height) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelInspectorPreviewTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt app/src/test/kotlin/com/multiviewer/ui/PixelInspectorPreviewTest.kt
git commit -m "Add zoomTowardPoint and clampPanOffset for image viewer zoom/pan"
```

---

### Task 2: Wire zoom/pan into `PixelInspectorPreview`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`

**Interfaces:**
- Consumes: `MAX_ZOOM_SCALE`, `zoomTowardPoint`, `clampPanOffset` (Task 1, same file, no import needed).
- Produces: nothing new — `PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier)`'s public signature is unchanged, so both existing call sites (`ImageInspectorUI.kt`'s embedded-thumbnail box and primary-image box) need no changes at all.

- [ ] **Step 1: Replace the composable body**

Replace the entire contents of `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt` with:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize

const val MAX_ZOOM_SCALE = 8f
private const val ZOOM_STEP_FACTOR = 0.08f

// Cursor-anchored zoom: re-derives offset so the content point currently under the cursor stays
// under the cursor after the scale change, instead of zooming around the box's center (which
// would make whatever the user is actually looking at drift away as they zoom in). Matches the
// scroll-up-zooms-in sign convention already established by FfmpegAudioPlayer.kt's own zoom step.
fun zoomTowardPoint(scale: Float, offset: Offset, cursorPosition: Offset, scrollDeltaY: Float): Pair<Float, Offset> {
    val newScale = (scale * (1f - scrollDeltaY * ZOOM_STEP_FACTOR)).coerceIn(1f, MAX_ZOOM_SCALE)
    val newOffset = Offset(
        cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
        cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
    )
    return newScale to newOffset
}

// Bounds pan so the scaled content can never be dragged far enough to reveal empty space beyond
// its own edge -- half of however much the scaled content now overhangs the box's own bounds on
// that axis. At scale == 1 this collapses to exactly 0f..0f (no pan possible), so callers don't
// need a separate "only pan when zoomed in" branch anywhere.
fun clampPanOffset(offset: Offset, boxSize: Size, scale: Float): Offset {
    val maxX = ((boxSize.width * scale) - boxSize.width) / 2f
    val maxY = ((boxSize.height * scale) - boxSize.height) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(bitmap) { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { boxSize = it.size.toSize() }
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                scale = newScale
                offset = clampPanOffset(rawOffset, boxSize, newScale)
                event.changes.forEach { it.consume() }
            }
            .pointerInput(bitmap) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset = clampPanOffset(offset + dragAmount, boxSize, scale)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            contentScale = ContentScale.Fit,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `graphicsLayer` fails to resolve from `androidx.compose.ui.graphics.graphicsLayer`, search this project's Compose Multiplatform dependency version for the correct package (it may be `androidx.compose.ui.draw.graphicsLayer` in some versions) and fix the import — this is the one import in this file most likely to need adjustment for the exact Compose version pinned in `gradle/libs.versions.toml`.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass (Task 1's 7 new tests plus the existing baseline), no regressions.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt
git commit -m "Wire zoom (scroll), pan (drag), and reset (double-click) into PixelInspectorPreview"
```

---

### Task 3: Manual verification

**Files:** None (no code changes).

- [ ] **Step 1: Run the app and open a real image**

```bash
./gradlew :app:run
```

Open any JPEG/PNG/HEIC file with both a thumbnail and a decodable primary image. Confirm, in **both** boxes independently:

- Scrolling up over a specific part of the image zooms in with that part staying under the cursor (not drifting toward the box's center).
- Scrolling down zooms back out, capped at the original fit view (can't zoom out past 1x).
- Scrolling up repeatedly caps out at a reasonable maximum zoom (8x) rather than growing unbounded.
- Once zoomed in, dragging pans the image, and stops (doesn't reveal black beyond the image's own edge) once you've panned as far as the current zoom level allows.
- Double-clicking resets instantly to the original fit view.
- Switching to a different file resets both boxes back to the fit view (no zoom state carried over from the previous file).

- [ ] **Step 2: Report**

Note in the progress ledger (`.git/sdd/progress.md`) what was actually confirmed. If GUI interaction isn't reliable in the current environment (this sandbox has a documented history of the app window closing on its own shortly after launch), say so explicitly rather than claiming a full interactive pass; Task 1/2's automated tests, compile, and full-suite passes stand as code-level confirmation regardless.
