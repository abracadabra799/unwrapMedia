# GIF Animation Playback (Full-Width Frame Filmstrip) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make animated `.gif` files actually animate: replace the current first-frame-only static view with a full-width, interactive frame filmstrip (real per-frame thumbnails, click/arrow-key navigation, play/pause with the GIF's own per-frame timing and loop count).

**Architecture:** A new pure-Kotlin decoder (`GifFrameDecoder.kt`) wraps Skia's existing `org.jetbrains.skia.Codec` multi-frame API to decode every frame (up to a 500-frame safety cap) plus each frame's delay and the animation's loop count. A new Compose UI file (`GifFilmstripPlayer.kt`) renders those frames as a scrollable, zoomable, keyboard-navigable strip with a play/pause control, following the exact interaction patterns already established by `GopAnalysisView.kt` (frame strip navigation) and `FfmpegVideoPlayer.kt` (play/pause visual style). `AppState.kt` and `ImageInspectorUI.kt` wire it into the existing per-tab background-decode pipeline and preview layout.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, Skia (`org.jetbrains.skia.Codec`, already transitively available -- no new dependency), `kotlin.test` (JUnit5 platform).

## Global Constraints

- Use `org.jetbrains.skia.Codec` for all multi-frame decoding -- it already handles GIF's disposal-method and transparency compositing internally. Do not hand-roll any GIF LZW/compositing logic.
- Cap eager frame decoding at `MAX_GIF_FRAMES = 500`; report `truncated = true` and the real `totalFrameCount` when a GIF has more frames than that.
- GIF playback starts **paused** on file open (first frame shown, user must press play) -- confirmed user decision, not auto-play.
- The filmstrip replaces the *entire* three-box preview row (EXIF thumbnail / primary image / motion-photo) with one full-width panel, but **only** when the open file is a `.gif` AND its animation decode succeeded. Every other case (any other image extension, or a GIF whose decode failed/is still in flight) must render today's unchanged three-box row -- this feature must never regress below current behavior.
- Selecting a frame in the filmstrip (click or arrow key) sets `tab.selected` to that frame's `ImageDescriptor` `BoxNode` -- reuse `DetailedPropertiesPanel` exactly as-is, do not modify it.
- A GIF that decodes to 1 frame (not actually animated) must render as a plain static image with no filmstrip chrome, no play button, no caption.
- Spec reference: `docs/superpowers/specs/2026-08-01-gif-animation-playback-design.md`.

---

## Task 1: GIF frame decoder (`GifFrameDecoder.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/GifFrameDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/GifFrameDecoderTest.kt`

**Interfaces:**
- Produces (used by Task 2, package `com.multiviewer.parser`):
  ```kotlin
  const val MAX_GIF_FRAMES = 500

  data class GifAnimationData(
      val frames: List<ImageBitmap>,
      val durationsMs: List<Int>,
      val loopCount: Int,
      val totalFrameCount: Int,
      val truncated: Boolean,
  )

  fun decodeGifAnimation(file: File, maxFrames: Int = MAX_GIF_FRAMES): GifAnimationData?
  ```

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/kotlin/com/multiviewer/parser/GifFrameDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun logicalScreenDescriptor(width: Int, height: Int, globalColorTableFlag: Boolean, globalColorTableSize: Int): ByteArray {
    val packed = (if (globalColorTableFlag) 0x80 else 0x00) or (globalColorTableSize and 0x07)
    return uint16LE(width) + uint16LE(height) + byteArrayOf(packed.toByte(), 0x00, 0x00)
}

private fun imageDescriptor(left: Int, top: Int, width: Int, height: Int, imageData: ByteArray): ByteArray =
    byteArrayOf(0x2C) + uint16LE(left) + uint16LE(top) + uint16LE(width) + uint16LE(height) +
        byteArrayOf(0x00) + byteArrayOf(0x02) + imageData

private fun subBlock(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

private val SUB_BLOCK_TERMINATOR = byteArrayOf(0x00)

private fun graphicControlExtension(delayTimeUnits: Int): ByteArray {
    val gceData = subBlock(
        byteArrayOf(0x00, (delayTimeUnits and 0xFF).toByte(), ((delayTimeUnits shr 8) and 0xFF).toByte(), 0x00),
    ) + SUB_BLOCK_TERMINATOR
    return byteArrayOf(0x21, 0xF9.toByte()) + gceData
}

// Builds a minimal, real, spec-valid 2-frame animated GIF file: a 1x1 canvas, frame 0 is solid red
// at 50ms (delayTimeUnits=5, GIF's delay field is in 1/100s units), frame 1 is solid blue at
// 100ms (delayTimeUnits=10). The LZW image data for each frame -- [0x44, 0x01] for pixel index 0,
// [0x4C, 0x01] for pixel index 1 -- is the exact byte-for-byte encoding of
// [ClearCode(4), <pixel index>, EndCode(5)] at minCodeSize=2 (3-bit codes, since imageDescriptor
// below hardcodes minCodeSize to 0x02), packed LSB-first per the GIF spec's bit-packing rule.
// Worked out by hand and cross-checked against the standard incremental bit-buffer packing
// algorithm (bitBuffer |= code shl bitCount; flush a byte whenever bitCount >= 8). This needs to
// be genuine, decodable LZW data -- unlike GifWalkerTest's structural-parser tests, which only
// exercise the box-tree walker and never need real pixel data -- because this test exercises
// Skia's actual GIF pixel decoder.
private fun writeTwoFrameGif(): File {
    val header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) // "GIF89a"
    val lsd = logicalScreenDescriptor(width = 1, height = 1, globalColorTableFlag = true, globalColorTableSize = 0)
    val globalColorTable = byteArrayOf(
        0xFF.toByte(), 0x00, 0x00, // color index 0 = red
        0x00, 0x00, 0xFF.toByte(), // color index 1 = blue
    )
    val frame0ImageData = subBlock(byteArrayOf(0x44, 0x01)) + SUB_BLOCK_TERMINATOR
    val frame0 = imageDescriptor(left = 0, top = 0, width = 1, height = 1, imageData = frame0ImageData)
    val frame1ImageData = subBlock(byteArrayOf(0x4C, 0x01)) + SUB_BLOCK_TERMINATOR
    val frame1 = imageDescriptor(left = 0, top = 0, width = 1, height = 1, imageData = frame1ImageData)
    val trailer = byteArrayOf(0x3B)

    val bytes = header + lsd + globalColorTable +
        graphicControlExtension(5) + frame0 +
        graphicControlExtension(10) + frame1 +
        trailer

    val file = File.createTempFile("gif-frame-decoder-test-", ".gif")
    file.deleteOnExit()
    file.writeBytes(bytes)
    return file
}

class GifFrameDecoderTest {
    @Test
    fun `decodes both frames of a two-frame animated GIF with correct durations`() {
        val animation = decodeGifAnimation(writeTwoFrameGif())
        assertNotNull(animation)
        assertEquals(2, animation.frames.size)
        assertEquals(listOf(50, 100), animation.durationsMs)
        assertEquals(2, animation.totalFrameCount)
        assertFalse(animation.truncated)
    }

    @Test
    fun `frames decode to genuinely distinct pixels, not the same frame repeated`() {
        val animation = decodeGifAnimation(writeTwoFrameGif())
        assertNotNull(animation)
        val frame0Argb = animation.frames[0].asSkiaBitmap().getColor(0, 0)
        val frame1Argb = animation.frames[1].asSkiaBitmap().getColor(0, 0)
        val frame0Red = (frame0Argb shr 16) and 0xFF
        val frame0Blue = frame0Argb and 0xFF
        val frame1Red = (frame1Argb shr 16) and 0xFF
        val frame1Blue = frame1Argb and 0xFF
        assertTrue(frame0Red > 200 && frame0Blue < 50, "frame 0 should be red, was argb=0x${frame0Argb.toString(16)}")
        assertTrue(frame1Blue > 200 && frame1Red < 50, "frame 1 should be blue, was argb=0x${frame1Argb.toString(16)}")
    }

    @Test
    fun `maxFrames caps decoding and reports truncation`() {
        val animation = decodeGifAnimation(writeTwoFrameGif(), maxFrames = 1)
        assertNotNull(animation)
        assertEquals(1, animation.frames.size)
        assertEquals(2, animation.totalFrameCount)
        assertTrue(animation.truncated)
    }

    @Test
    fun `returns null for a file that is not a valid GIF`() {
        val file = File.createTempFile("gif-frame-decoder-test-not-a-gif-", ".gif")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertEquals(null, decodeGifAnimation(file))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.GifFrameDecoderTest"`
Expected: compile failure -- `decodeGifAnimation`/`GifAnimationData` are unresolved references (the implementation file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/GifFrameDecoder.kt`:

```kotlin
package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import java.io.File

const val MAX_GIF_FRAMES = 500

data class GifAnimationData(
    val frames: List<ImageBitmap>,
    val durationsMs: List<Int>,
    val loopCount: Int,
    val totalFrameCount: Int,
    val truncated: Boolean,
)

// Decodes every frame of an animated GIF (or the single frame of a static one) via Skia's
// multi-frame Codec API, which -- unlike Image.makeFromEncoded's single-frame decode used
// elsewhere in this file for the static fallback path -- handles GIF's per-frame disposal method
// and transparency compositing internally, so frame N's pixels already reflect whatever the GIF's
// own compositing rules say frame N should look like. Each frame gets its own freshly allocated
// Bitmap (no buffer reuse across frames) so there's no risk of one frame's pixel storage being
// mutated out from under an already-captured ImageBitmap by a later loop iteration. Returns null
// if the file can't be decoded as an image at all (corrupt bytes, wrong format) -- callers should
// fall back to the existing single-frame decodePrimaryBitmapAndHistogram path in that case.
fun decodeGifAnimation(file: File, maxFrames: Int = MAX_GIF_FRAMES): GifAnimationData? {
    return try {
        val codec = Codec.makeFromData(Data.makeFromBytes(file.readBytes()))
        val totalFrameCount = codec.frameCount
        val framesInfo = codec.framesInfo
        val decodedCount = minOf(totalFrameCount, maxFrames).coerceAtLeast(1)

        val frames = mutableListOf<ImageBitmap>()
        val durationsMs = mutableListOf<Int>()
        for (index in 0 until decodedCount) {
            val bitmap = Bitmap()
            bitmap.allocPixels(codec.imageInfo)
            codec.readPixels(bitmap, index)
            frames.add(Image.makeFromBitmap(bitmap).toComposeImageBitmap())
            durationsMs.add(if (index < framesInfo.size) framesInfo[index].duration else 0)
        }

        GifAnimationData(
            frames = frames,
            durationsMs = durationsMs,
            loopCount = codec.repetitionCount,
            totalFrameCount = totalFrameCount,
            truncated = totalFrameCount > decodedCount,
        )
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.GifFrameDecoderTest"`
Expected: all 4 tests PASS. If `frames decode to genuinely distinct pixels...` fails with both frames showing the same color, double check the loop allocates a **new** `Bitmap()` per iteration (not one shared/reused across iterations) -- that's the specific bug this test exists to catch.

- [ ] **Step 5: Run the full existing test suite to confirm no regressions**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all previously-passing tests (including `GifWalkerTest`) still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/GifFrameDecoder.kt app/src/test/kotlin/com/multiviewer/parser/GifFrameDecoderTest.kt
git commit -m "feat: add GifFrameDecoder for multi-frame GIF animation decoding"
```

---

## Task 2: Filmstrip UI + wiring into the open-file pipeline and preview layout

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/GifFilmstripPlayer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add `TabState` fields ~line 120-134; add background decode hook ~line 399-425)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (branch the top preview `Row`, ~lines 60-156)

**Interfaces:**
- Consumes (from Task 1, package `com.multiviewer.parser`): `GifAnimationData`, `decodeGifAnimation(file, maxFrames)`.
- Consumes (existing, package `com.multiviewer.ui`): `TabState`, `BoxNode` (`com.multiviewer.parser`), `PreviewCaption(text, modifier)` (`Components.kt`), `PixelInspectorPreview(bitmap, modifier)`, `AppColors.NeonGreen`, `runInBackground(block)` (`BackgroundTask.kt`).
- Produces: `@Composable fun GifFilmstripPlayer(tab: TabState, animation: GifAnimationData, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Add GIF playback fields to `TabState`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, inside the `TabState` class, immediately after the existing `motionPhotoPreview` field (currently `var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)`), add:

```kotlin
    // Decoded GIF animation frames (see GifFrameDecoder.kt) -- null until the background decode
    // in openFile finishes (or forever, for non-GIF files, which never trigger it). A non-null
    // value with frames.size <= 1 means "decoded successfully but not actually animated" -- see
    // GifFilmstripPlayer, which falls back to a plain static view in that case.
    var gifAnimation: GifAnimationData? by mutableStateOf(null)
    var gifFrameIndex: Int by mutableStateOf(0)
    var gifIsPlaying: Boolean by mutableStateOf(false)
```

- [ ] **Step 2: Trigger the background GIF decode from `openFile`**

In the same file, inside `openFile`'s background `Thread { ... }` block, find the existing primary-decode `runInBackground` call (currently reads, starting around line 399):

```kotlin
                        runInBackground {
                            val (bitmap, histogram) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)
                            if (bitmap != null) {
                                EventQueue.invokeLater {
                                    val current = tab.imageForensic ?: finalImageForensic
                                    tab.imageForensic = current.copy(bitmap = bitmap, histogram = histogram, isDecodingFallback = false)
                                }
                            } else {
                                // Skia has no HEIF/HEVC decoder — fall back to ffmpeg (already async
                                // and posts back via EventQueue.invokeLater itself).
                                FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { fallbackBitmap ->
                                    val current = tab.imageForensic ?: finalImageForensic
                                    // Do NOT substitute the full-resolution primary decode as the
                                    // "thumbnail" when the real embedded thumbnail item couldn't be
                                    // extracted (e.g. an HEVC-coded HEIC "thmb" item -- this parser
                                    // only decodes JPEG-coded thumbnail items) -- that previously
                                    // showed a full-size duplicate of the primary image mislabeled
                                    // as the thumbnail. Leaving it null lets the UI correctly say
                                    // the thumbnail couldn't be decoded instead of showing wrong
                                    // pixels at the wrong size.
                                    tab.imageForensic = current.copy(
                                        bitmap = fallbackBitmap,
                                        isDecodingFallback = false,
                                    )
                                }
                            }
                        }
```

Immediately after this `runInBackground { ... }` block (still inside the same `if (finalImageForensic.embeddedThumbnail == null && ...)`-sibling scope, i.e. still inside `if (type == MediaType.IMAGE && finalImageForensic != null) { ... }`), add a second, independent background decode gated on the file extension:

```kotlin

                        if (extension == "gif") {
                            // Runs alongside the primary static decode above, not instead of it --
                            // the static decode still feeds the (now-hidden-for-GIF) thumbnail/
                            // primary boxes as a fallback for as long as this hasn't finished, and
                            // GifFilmstripPlayer itself falls back to a plain static frame when this
                            // decode fails outright (see GifFrameDecoder.kt).
                            runInBackground {
                                val animation = decodeGifAnimation(file)
                                EventQueue.invokeLater { tab.gifAnimation = animation }
                            }
                        }
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL (this only adds fields and a background call using already-imported symbols -- `AppState.kt` already has `import com.multiviewer.parser.*`, which covers `GifAnimationData`/`decodeGifAnimation`).

- [ ] **Step 4: Create `GifFilmstripPlayer.kt`**

Create `app/src/main/kotlin/com/multiviewer/ui/GifFilmstripPlayer.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GifAnimationData
import kotlinx.coroutines.delay

private const val GIF_CELL_MIN_WIDTH_DP = 60f
private const val GIF_CELL_MAX_WIDTH_DP = 600f
private const val GIF_CELL_DEFAULT_WIDTH_DP = 200f
private const val GIF_CELL_ZOOM_STEP_DP = 20f
// GIF delay times of 0 are common in the wild (authoring tools often omit a real value) and would
// otherwise busy-loop the play coroutine; browsers commonly clamp to something in this range too.
private const val GIF_MIN_FRAME_DELAY_MS = 20L

// Full-width replacement for ImageInspectorUI's usual three-box preview row when the open file is
// an animated GIF -- see docs/superpowers/specs/2026-08-01-gif-animation-playback-design.md. Each
// filmstrip cell renders a real decoded frame at panel height (not a small thumbnail), which is
// why there's no separate "now playing" preview elsewhere in the panel -- the current cell IS the
// view. Caller is responsible for only rendering this when animation.frames is non-empty.
@Composable
fun GifFilmstripPlayer(tab: TabState, animation: GifAnimationData, modifier: Modifier = Modifier) {
    val gifFrameNodes = remember(tab.root) {
        val nodes = mutableListOf<BoxNode>()
        fun walk(node: BoxNode) {
            if (node.type == "ImageDescriptor") nodes.add(node)
            node.children.forEach { walk(it) }
        }
        tab.root?.let { walk(it) }
        nodes
    }

    if (animation.frames.size <= 1) {
        PixelInspectorPreview(animation.frames.first(), modifier = modifier.fillMaxSize())
        return
    }

    var cellWidthDp by remember { mutableStateOf(GIF_CELL_DEFAULT_WIDTH_DP) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun selectFrame(index: Int) {
        tab.gifFrameIndex = index.coerceIn(0, animation.frames.size - 1)
        tab.gifIsPlaying = false
        tab.selected = gifFrameNodes.getOrNull(tab.gifFrameIndex)
        tab.selectedFrame = null
        focusRequester.requestFocus()
    }

    LaunchedEffect(tab.gifIsPlaying, animation) {
        if (!tab.gifIsPlaying) return@LaunchedEffect
        var repeatsCompleted = 0
        while (tab.gifIsPlaying) {
            delay(animation.durationsMs[tab.gifFrameIndex].toLong().coerceAtLeast(GIF_MIN_FRAME_DELAY_MS))
            if (!tab.gifIsPlaying) break
            val next = tab.gifFrameIndex + 1
            if (next < animation.frames.size) {
                tab.gifFrameIndex = next
            } else if (animation.loopCount in 0..repeatsCompleted) {
                // loopCount counts REPEATS after the first playthrough (0 = play once, never
                // repeat) -- stop once we've completed that many wraps back to frame 0.
                tab.gifIsPlaying = false
            } else {
                repeatsCompleted++
                tab.gifFrameIndex = 0
            }
        }
    }

    LaunchedEffect(tab.gifFrameIndex) {
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == tab.gifFrameIndex }
        if (!isVisible) {
            listState.animateScrollToItem(tab.gifFrameIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                    cellWidthDp = (cellWidthDp - scrollDeltaY * GIF_CELL_ZOOM_STEP_DP)
                        .coerceIn(GIF_CELL_MIN_WIDTH_DP, GIF_CELL_MAX_WIDTH_DP)
                    event.changes.forEach { it.consume() }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { selectFrame(tab.gifFrameIndex - 1); true }
                        Key.DirectionRight -> { selectFrame(tab.gifFrameIndex + 1); true }
                        else -> false
                    }
                },
        ) {
            itemsIndexed(animation.frames) { index, frameBitmap ->
                val isCurrent = index == tab.gifFrameIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(cellWidthDp.dp)
                        .padding(2.dp)
                        .let { if (isCurrent) it.border(2.dp, AppColors.NeonGreen) else it }
                        .clickable { selectFrame(index) },
                ) {
                    PixelInspectorPreview(frameBitmap, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (!tab.gifIsPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { tab.gifIsPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        if (animation.truncated) {
            PreviewCaption(
                "First ${animation.frames.size} of ${animation.totalFrameCount} frames shown",
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }

        PreviewCaption(
            "Frame ${tab.gifFrameIndex + 1}/${animation.frames.size} · ${animation.durationsMs[tab.gifFrameIndex]}ms",
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )
    }
}
```

- [ ] **Step 5: Wire it into `ImageInspectorUI.kt`'s top preview row**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, find the current top `Row` (starts with the comment `// Top: Dual Preview (50/50 Split)`, currently):

```kotlin
                // Top: Dual Preview (50/50 Split)
                Row(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                ) {
                    // Left Panel: Embedded EXIF Thumbnail
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.embeddedThumbnail?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback && forensic.hasThumbnailReference) {
                            DecodingIndicator("썸네일 로딩 중...")
                        } else if (forensic.hasThumbnailReference) {
                            Text("Embedded Thumbnail Codec Not Supported", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            Text("No Embedded Thumbnail", color = Color.Gray, fontSize = 13.sp)
                        }

                        Text("EMBEDDED EXIF THUMBNAIL",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonBlue)
                        )

                        forensic.embeddedThumbnail?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }
                    
                    // Middle Panel: Primary Image View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback) {
                            DecodingIndicator("이미지 디코딩 중...")
                        } else {
                            Text("Primary Image Decoding Failed", color = AppColors.NeonRed, fontSize = 13.sp)
                        }

                        Text("PRIMARY IMAGE VIEW",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )

                        forensic.bitmap?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }

                    // Right Panel: Motion Photo Video (only when the file has an embedded motion video)
                    val embeddedVideo = tab.embeddedVideo
                    if (embeddedVideo != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, AppColors.Border)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            MotionPhotoVideoPreview(tab, embeddedVideo)

                            Text("MOTION PHOTO VIDEO",
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonPurple)
                            )
                        }
                    }
                }
```

Replace it with the same block wrapped in a GIF/non-GIF branch:

```kotlin
                // Top: Dual Preview (50/50 Split) -- or, for an animated GIF whose frames decoded
                // successfully, a full-width frame filmstrip instead (see
                // docs/superpowers/specs/2026-08-01-gif-animation-playback-design.md). Any other
                // case -- non-GIF file, or a GIF whose animation decode hasn't finished/failed --
                // falls through to the unchanged three-box row below.
                val gifAnimation = tab.gifAnimation
                if (tab.file.extension.lowercase() == "gif" && gifAnimation != null) {
                    GifFilmstripPlayer(
                        tab = tab,
                        animation = gifAnimation,
                        modifier = Modifier.weight(verticalSplit).fillMaxWidth(),
                    )
                } else {
                Row(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                ) {
                    // Left Panel: Embedded EXIF Thumbnail
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.embeddedThumbnail?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback && forensic.hasThumbnailReference) {
                            DecodingIndicator("썸네일 로딩 중...")
                        } else if (forensic.hasThumbnailReference) {
                            Text("Embedded Thumbnail Codec Not Supported", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            Text("No Embedded Thumbnail", color = Color.Gray, fontSize = 13.sp)
                        }

                        Text("EMBEDDED EXIF THUMBNAIL",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonBlue)
                        )

                        forensic.embeddedThumbnail?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }
                    
                    // Middle Panel: Primary Image View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback) {
                            DecodingIndicator("이미지 디코딩 중...")
                        } else {
                            Text("Primary Image Decoding Failed", color = AppColors.NeonRed, fontSize = 13.sp)
                        }

                        Text("PRIMARY IMAGE VIEW",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )

                        forensic.bitmap?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }

                    // Right Panel: Motion Photo Video (only when the file has an embedded motion video)
                    val embeddedVideo = tab.embeddedVideo
                    if (embeddedVideo != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, AppColors.Border)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            MotionPhotoVideoPreview(tab, embeddedVideo)

                            Text("MOTION PHOTO VIDEO",
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonPurple)
                            )
                        }
                    }
                }
                }
```

(Only the added `val gifAnimation = ...` / `if (...) { GifFilmstripPlayer(...) } else { ... }` wrapper and its matching closing brace are new -- the `Row { ... }` body inside the `else` branch is copied verbatim, unindented content unchanged, so the diff is easy to review.)

- [ ] **Step 6: Compile-check**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. If `GifFilmstripPlayer` is unresolved, confirm Step 4's file was saved under `app/src/main/kotlin/com/multiviewer/ui/` (same package as `ImageInspectorUI.kt`, so no import is needed).

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL -- this task adds no new automated tests of its own (UI behavior is verified manually in Task 3), but must not break Task 1's or any pre-existing test.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/GifFilmstripPlayer.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "feat: render animated GIFs as an interactive full-width frame filmstrip"
```

---

## Task 3: Controller-performed manual verification

This task has no subagent dispatch -- run it directly in the controlling session, the same way this project's other plans (e.g. `docs/superpowers/plans/2026-08-02-ffmpeg-shared-build.md` Task 2) reserve real runtime/manual verification for the controller rather than a sandboxed implementer.

- [ ] **Step 1: Obtain a real animated GIF to test with**

If no animated GIF is on hand, generate one with ffmpeg (already a project dependency, and its GIF muxer is spec-correct):

```bash
ffmpeg -f lavfi -i "testsrc=duration=3:size=320x240:rate=10" -y /tmp/test-animation.gif
```

This produces a ~30-frame, 3-second test-pattern animation.

- [ ] **Step 2: Run the app and open the test GIF**

```bash
./gradlew :app:run
```

Open `/tmp/test-animation.gif` via the app's file picker.

- [ ] **Step 3: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- The top preview area shows a full-width frame filmstrip (not the old three-box row) once loading finishes.
- It starts **paused** on the first frame -- no auto-play.
- Clicking the play button starts playback; frames advance at roughly the source video's real timing (a 10fps source should visibly advance about 10 times/sec, not instantly or in slow motion) and the strip auto-scrolls to keep the current frame in view.
- Clicking a specific frame in the strip jumps to it and pauses playback.
- Left/Right arrow keys step one frame at a time (click a frame first to give the strip focus, or use Tab/click into the panel).
- Mouse-wheel over the strip zooms frame cell size in/out.
- Clicking a frame updates the right-hand "Detailed Properties" panel to show that frame's `ImageDescriptor` fields (e.g. `left`, `top`, `width`, `height`) -- confirms the `tab.selected` wiring to `DetailedPropertiesPanel` works without any changes to that panel.
- Open a non-animated (single-frame, e.g. `.png` or a static `.gif`) image file in another tab: confirm the original three-box thumbnail/primary/motion-photo layout still renders exactly as before -- no regression for non-GIF or non-animated files.

- [ ] **Step 4: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1/2 commit ranges and the outcome of this manual verification (pass, or any issues found and how they were resolved).
