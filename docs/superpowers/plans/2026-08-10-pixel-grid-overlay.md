# Pixel Grid Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional native-pixel-boundary grid overlay to the image viewer (thumbnail + primary image) and the video player, toggled by a single global "픽셀 그리드" checkbox in the `보기` menu.

**Architecture:** A new `PixelGridOverlay` composable (with a pure, unit-tested `shouldDrawPixelGrid` visibility function) draws fit-scale grid lines; callers that zoom (the image viewer) apply their own zoom `graphicsLayer` to this composable too, so the grid tracks the zoomed image with no zoom-aware drawing logic of its own. A new `LocalShowPixelGrid` CompositionLocal (mirroring the existing `LocalThemePalette` pattern) and a new `PixelGridPreference.kt` (mirroring `ThemePreference.kt`) carry the global toggle.

**Tech Stack:** Kotlin, Compose Desktop (`Canvas`, `staticCompositionLocalOf`, `graphicsLayer`), `java.util.prefs.Preferences`.

## Global Constraints

- Grid lines hide automatically when on-screen spacing would be below `MIN_SCREEN_PX_PER_GRID_LINE = 8f` (screen pixels per source pixel, after accounting for the caller's own zoom `scale`).
- Grid line color: `Color.White.copy(alpha = 0.25f)`, fixed (not configurable).
- One global toggle controls all three preview boxes (thumbnail, primary image, video) at once — no per-panel toggle.
- Toggle persists across app launches (`java.util.prefs`), default `false`.
- Menu label: `"픽셀 그리드"`, a `CheckboxItem` in the existing `Menu("보기")`, alongside the existing theme items.
- `FfmpegVideoPlayer` gets no zoom capability from this plan — its grid always draws at `scale = 1f`.

---

### Task 1: `PixelGridOverlay` composable and `shouldDrawPixelGrid`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/PixelGridOverlay.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/PixelGridOverlayTest.kt` (new)

**Interfaces:**
- Produces: `val LocalShowPixelGrid: ProvidableCompositionLocal<Boolean>` (default `false`), `fun shouldDrawPixelGrid(nativeSize: Size, boxSize: Size, scale: Float): Boolean`, `@Composable fun PixelGridOverlay(nativeSize: Size, scale: Float, modifier: Modifier = Modifier)` — Task 3 provides `LocalShowPixelGrid`; Tasks 4 and 5 read it and call `PixelGridOverlay`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/PixelGridOverlayTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelGridOverlayTest {
    @Test
    fun `hides the grid when fit-scale spacing is below the threshold`() {
        // A 4000px-wide native image fit into a 400px box is 0.1 screen px per source px --
        // nowhere near the 8px-per-line minimum.
        assertFalse(shouldDrawPixelGrid(nativeSize = Size(4000f, 3000f), boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `shows the grid once zoom pushes the effective spacing above the threshold`() {
        // Same image/box as above (0.1 screen px per source px at scale 1), but zoomed in 100x:
        // 0.1 * 100 = 10 screen px per source px, above the 8px minimum.
        assertTrue(shouldDrawPixelGrid(nativeSize = Size(4000f, 3000f), boxSize = Size(400f, 300f), scale = 100f))
    }

    @Test
    fun `shows the grid directly at fit-scale for a low-resolution image`() {
        // A 40x30 native image fit into a 400x300 box is 10 screen px per source px -- already
        // above the threshold with no zoom needed, matching the video-player use case (scale
        // always 1f) for small/raw test footage.
        assertTrue(shouldDrawPixelGrid(nativeSize = Size(40f, 30f), boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `hides the grid for a degenerate zero-size nativeSize`() {
        assertFalse(shouldDrawPixelGrid(nativeSize = Size.Zero, boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `hides the grid for a degenerate zero-size boxSize`() {
        assertFalse(shouldDrawPixelGrid(nativeSize = Size(400f, 300f), boxSize = Size.Zero, scale = 1f))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelGridOverlayTest"`
Expected: FAIL to compile — `shouldDrawPixelGrid` is an unresolved reference (it doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/PixelGridOverlay.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

val LocalShowPixelGrid = staticCompositionLocalOf { false }

private const val MIN_SCREEN_PX_PER_GRID_LINE = 8f
private val GRID_LINE_COLOR = Color.White.copy(alpha = 0.25f)

// Whether native-pixel-boundary grid lines would actually be legible: the on-screen spacing
// between adjacent lines (the content's own ContentScale.Fit scale, times the caller's zoom
// factor if any) must be at least MIN_SCREEN_PX_PER_GRID_LINE. Pure and unit-tested so the
// threshold behavior doesn't depend on a real Compose layout pass.
fun shouldDrawPixelGrid(nativeSize: Size, boxSize: Size, scale: Float): Boolean {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return false
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    return (fitScale * scale) >= MIN_SCREEN_PX_PER_GRID_LINE
}

// Draws native-pixel-boundary grid lines within this Canvas's own bounds, for content of
// nativeSize shown at ContentScale.Fit -- exactly the lines Photoshop's "Pixel Grid" would draw.
// `scale` is the CALLER's own zoom factor (1f if the caller has no zoom, like FfmpegVideoPlayer)
// -- used only for the shouldDrawPixelGrid visibility check; the lines themselves are always
// drawn at plain fit-scale, in this composable's own untransformed coordinate space. A caller
// that zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this
// composable as it applies to its own Image, so the grid tracks the zoomed image with no
// zoom-aware drawing logic here at all.
@Composable
fun PixelGridOverlay(nativeSize: Size, scale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (!shouldDrawPixelGrid(nativeSize, size, scale)) return@Canvas
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        var x = 0
        while (x <= nativeSize.width.toInt()) {
            val screenX = left + x * fitScale
            drawLine(GRID_LINE_COLOR, Offset(screenX, top), Offset(screenX, top + fittedHeight))
            x++
        }
        var y = 0
        while (y <= nativeSize.height.toInt()) {
            val screenY = top + y * fitScale
            drawLine(GRID_LINE_COLOR, Offset(left, screenY), Offset(left + fittedWidth, screenY))
            y++
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelGridOverlayTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelGridOverlay.kt app/src/test/kotlin/com/multiviewer/ui/PixelGridOverlayTest.kt
git commit -m "Add PixelGridOverlay and shouldDrawPixelGrid"
```

---

### Task 2: `PixelGridPreference` (persisted toggle)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/PixelGridPreference.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/PixelGridPreferenceTest.kt` (new)

**Interfaces:**
- Produces: `fun loadShowPixelGrid(): Boolean`, `fun saveShowPixelGrid(show: Boolean)` — Task 3 (Main.kt) calls both.

This mirrors the existing `ThemePreference.kt` / `ThemePreferenceTest.kt` exactly (same file, same `Preferences.userNodeForPackage(AppColors::class.java)` node, same round-trip test shape), just for a `Boolean` key instead of a `ThemeMode` enum.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/PixelGridPreferenceTest.kt`:

```kotlin
package com.multiviewer.ui

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelGridPreferenceTest {
    // Same Preferences node ThemePreferenceTest.kt already reaches into -- both this key and
    // "themeMode" live side by side under AppColors's package node.
    private val prefs = Preferences.userNodeForPackage(AppColors::class.java)

    @Test
    fun `defaults to false when nothing has been saved`() {
        prefs.remove("showPixelGrid")
        assertFalse(loadShowPixelGrid())
    }

    @Test
    fun `round-trips true`() {
        saveShowPixelGrid(true)
        assertTrue(loadShowPixelGrid())
    }

    @Test
    fun `round-trips false`() {
        saveShowPixelGrid(false)
        assertFalse(loadShowPixelGrid())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelGridPreferenceTest"`
Expected: FAIL to compile — `loadShowPixelGrid`/`saveShowPixelGrid` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/PixelGridPreference.kt`:

```kotlin
package com.multiviewer.ui

import java.util.prefs.Preferences

private val pixelGridPreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)
private const val SHOW_PIXEL_GRID_KEY = "showPixelGrid"

fun loadShowPixelGrid(): Boolean = pixelGridPreferences.getBoolean(SHOW_PIXEL_GRID_KEY, false)

fun saveShowPixelGrid(show: Boolean) {
    pixelGridPreferences.putBoolean(SHOW_PIXEL_GRID_KEY, show)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelGridPreferenceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelGridPreference.kt app/src/test/kotlin/com/multiviewer/ui/PixelGridPreferenceTest.kt
git commit -m "Add PixelGridPreference for persisting the pixel grid toggle"
```

---

### Task 3: Wire the toggle into `AppTheme` and the `보기` menu

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/Theme.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `LocalShowPixelGrid` (Task 1), `loadShowPixelGrid`/`saveShowPixelGrid` (Task 2) -- both same package as `Theme.kt`, no import needed there; `Main.kt` already has a wildcard `import com.multiviewer.ui.*`, so no import changes needed there either.
- Produces: `AppTheme`'s new `showPixelGrid: Boolean` parameter -- Tasks 4 and 5 don't call `AppTheme` directly, they read `LocalShowPixelGrid.current` instead, so this is purely internal plumbing between this task's own two files.

- [ ] **Step 1: Give `AppTheme` a `showPixelGrid` parameter**

In `app/src/main/kotlin/com/multiviewer/ui/Theme.kt`, find:

```kotlin
@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val palette = if (mode == ThemeMode.LIGHT) LightPalette else DarkPalette
    CompositionLocalProvider(LocalThemePalette provides palette) {
```

Replace with:

```kotlin
@Composable
fun AppTheme(mode: ThemeMode, showPixelGrid: Boolean, content: @Composable () -> Unit) {
    val palette = if (mode == ThemeMode.LIGHT) LightPalette else DarkPalette
    CompositionLocalProvider(LocalThemePalette provides palette, LocalShowPixelGrid provides showPixelGrid) {
```

- [ ] **Step 2: Add the `showPixelGrid` state and the menu item**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
```

Replace with:

```kotlin
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var showPixelGrid by remember { mutableStateOf(loadShowPixelGrid()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
```

Then find the `Menu("보기")` block:

```kotlin
            Menu("보기") {
                CheckboxItem(
                    "다크 테마",
                    checked = themeMode == ThemeMode.DARK,
                    onCheckedChange = {
                        themeMode = ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
                CheckboxItem(
                    "라이트 테마",
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = {
                        themeMode = ThemeMode.LIGHT
                        saveThemeMode(themeMode)
                    },
                )
            }
```

Replace with:

```kotlin
            Menu("보기") {
                CheckboxItem(
                    "다크 테마",
                    checked = themeMode == ThemeMode.DARK,
                    onCheckedChange = {
                        themeMode = ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
                CheckboxItem(
                    "라이트 테마",
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = {
                        themeMode = ThemeMode.LIGHT
                        saveThemeMode(themeMode)
                    },
                )
                CheckboxItem(
                    "픽셀 그리드",
                    checked = showPixelGrid,
                    onCheckedChange = {
                        showPixelGrid = it
                        saveShowPixelGrid(it)
                    },
                )
            }
```

- [ ] **Step 3: Pass `showPixelGrid` into the `AppTheme` call**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
        AppTheme(themeMode) {
```

Replace with:

```kotlin
        AppTheme(themeMode, showPixelGrid) {
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/Theme.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Wire the pixel grid toggle into AppTheme and the 보기 menu"
```

---

### Task 4: Draw the grid in `PixelInspectorPreview`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`

**Interfaces:**
- Consumes: `LocalShowPixelGrid`, `PixelGridOverlay` (Task 1, same package, no import needed).

- [ ] **Step 1: Add the grid overlay after the `Image`**

In `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`, find the `Image(...)` call inside `PixelInspectorPreview` (the whole `Box`'s only child today):

```kotlin
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

Replace with:

```kotlin
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
        if (LocalShowPixelGrid.current) {
            PixelGridOverlay(
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                scale = scale,
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt
git commit -m "Draw the pixel grid overlay in PixelInspectorPreview"
```

---

### Task 5: Draw the grid in `FfmpegVideoPlayer`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

**Interfaces:**
- Consumes: `LocalShowPixelGrid`, `PixelGridOverlay` (Task 1, same package, no import needed).

- [ ] **Step 1: Add the `Size` import**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`, add this import alongside the file's existing `androidx.compose.ui.*` imports:

```kotlin
import androidx.compose.ui.geometry.Size
```

- [ ] **Step 2: Add the grid overlay after the frame `Image`**

Find:

```kotlin
        } else if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
```

Replace with:

```kotlin
        } else if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (LocalShowPixelGrid.current) {
                PixelGridOverlay(nativeSize = Size(info.width.toFloat(), info.height.toFloat()), scale = 1f)
            }
        } else {
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "Draw the pixel grid overlay in FfmpegVideoPlayer"
```

---

### Task 6: Manual verification

**Files:** None (no code changes).

- [ ] **Step 1: Run the app**

```bash
./gradlew :app:run
```

- [ ] **Step 2: Verify the toggle and its persistence**

- `보기` menu shows "픽셀 그리드" alongside the two theme items, unchecked by default.
- Check it: grid lines should NOT appear yet on a normal-resolution photo at the default fit view (spacing too dense) -- confirms the auto-hide threshold.
- Zoom into the image (scroll to zoom, per the image-viewer-zoom-pan feature) far enough and the grid should appear, aligned to the image's real pixel boundaries, and pan/zoom in lockstep with the image as you continue zooming/dragging.
- Uncheck it: grid disappears immediately on all open preview boxes.
- Restart the app: the last-set checked/unchecked state should be remembered (matches the theme toggle's own persistence).

- [ ] **Step 3: Verify on a low-resolution file if available**

If a small/raw test image or video (e.g. under ~100px on its longer side) is available, open it with the grid enabled -- it should show immediately at the default fit view, with no zoom needed (demonstrates the video-player code path, since the video player still has no zoom of its own).

- [ ] **Step 4: Report**

Note in the progress ledger (`.git/sdd/progress.md`) what was actually confirmed. If GUI interaction isn't reliable in the current environment (this sandbox has a documented history of the app window closing on its own shortly after launch, and no way to simulate real mouse scroll/drag/click input), say so explicitly rather than claiming a full interactive pass; Tasks 1-5's automated tests, compiles, and full-suite passes stand as code-level confirmation regardless.
