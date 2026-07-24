# HEIC/HEVC Primary Image Decoding via VLC Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Skia's `Image.makeFromEncoded` can't decode a file (HEIC and other HEIF-family stills it has no codec for), fall back to decoding the primary image via libVLC — reusing the render-callback pattern `VlcVideoPlayer.kt` already uses for video — so the center-top panel's right ("PRIMARY IMAGE VIEW") and, when applicable, left ("EMBEDDED EXIF THUMBNAIL") boxes show a real image instead of failure text.

**Architecture:** A new headless, one-shot `VlcImageSnapshotDecoder` object spins up its own `MediaPlayerFactory`/`MediaPlayer` per file, captures the first rendered frame via `CallbackVideoSurface`/`RenderCallback` (same wiring as `VlcVideoPlayer`), and releases everything immediately after. `AppState.openFile()` calls it asynchronously — only for `MediaType.IMAGE` tabs, only when `ImageAnalyzer.analyze()`'s `bitmap` came back `null` — and updates `TabState.imageForensic` when the result arrives, with a transient "decoding" state shown in between. `ImageAnalyzer` gains a structural `hasThumbnailReference` flag (does the file's `iref` box reference a `thmb` item at all, independent of whether that item's bytes could be decoded) so the left panel can reuse the VLC-decoded primary image as a stand-in when appropriate.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, `vlcj:4.12.1` (existing dependency, no new one added), `kotlin.test` (`kotlin("test-junit5")`, JUnit Platform).

## Global Constraints

- No new dependencies — reuse the existing `uk.co.caprica:vlcj:4.12.1` and `org.jetbrains.skia` (Skiko) already on the classpath.
- VLC fallback triggers on `bitmap == null` after Skia's attempt, regardless of file extension — generic, not HEIC-specific, so `.avif` and other HEIF-family stills benefit too (confirmed with user).
- VLC fallback is gated to `MediaType.IMAGE` tabs only. `MediaType.VIDEO` tabs' `ImageAnalyzer.analyze()` call is untouched — its `imageForensic` output is never read by `VideoInspectorUI.kt` (confirmed by search), so adding VLC there would be wasted work that could also contend with the real video player already decoding the same file.
- One `MediaPlayerFactory`/`MediaPlayer` per fallback decode, released immediately after capturing a frame or timing out — no shared/pooled VLC instance (confirmed with user, acceptable given `MAX_OPEN_FILES = 2`).
- Fallback decode timeout: 5000 ms (`TIMEOUT_MS`). Past this, deliver `null` and release resources — never hang indefinitely.
- `onResult` fires exactly once, always on the AWT event thread (`EventQueue.invokeLater`), matching `VlcVideoPlayer.kt`'s existing thread-handover pattern for Compose state safety.
- Left panel ("EMBEDDED EXIF THUMBNAIL") reuses the same decoded `ImageBitmap` object as the right panel when `hasThumbnailReference == true` — no separate downscaled bitmap is generated (confirmed with user: `ContentScale.Fit` already handles sizing).
- Spec: `docs/superpowers/specs/2026-07-24-heic-vlc-fallback-decoding-design.md`.

---

### Task 1: Expose `hasThumbnailReference` from `ImageAnalyzer` and add new `ImageForensicData` fields

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:24-31`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerTest.kt` (new file)

**Interfaces:**
- Consumes: `findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode?` (existing, in `MediaSummaryBuilder.kt`, same package — unchanged).
- Produces: `ImageAnalyzer.analyze(file: File, root: BoxNode): ImageForensicData` (existing public signature, unchanged) — `ImageForensicData` now carries `hasThumbnailReference: Boolean` and `isDecodingFallback: Boolean` (default `false`), which Task 3 reads and Task 4 renders.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzerTest {
    @Test
    fun `hasThumbnailReference is true when iref has a thmb entry, even if that item's bytes are not JPEG`() {
        // File content is all zero bytes — the "thumbnail item" payload (at offset 40, length 150)
        // is non-JPEG (no 0xFF 0xD8 anywhere), so Strategy 1's magic-byte check will reject it,
        // and Strategies 2/3 have nothing to find either. hasThumbnailReference must still be true
        // because it reflects the iref/thmb *structure*, not decode success.
        val file = File.createTempFile("image-analyzer-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val thmb = BoxNode(
            type = "thmb", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("from_item_ID", "1", 0, 0), BoxField("to_item_ID[0]", "99", 0, 0)),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(thmb))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf, iref))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertTrue(forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }

    @Test
    fun `hasThumbnailReference is false when there is no iref box`() {
        val file = File.createTempFile("image-analyzer-no-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals(false, forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.ImageAnalyzerTest" --console=plain`
Expected: Compile error — `ImageForensicData` has no member `hasThumbnailReference` yet.

- [ ] **Step 3: Add the new fields to `ImageForensicData`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
data class ImageForensicData(
    val bitmap: ImageBitmap? = null,
    val embeddedThumbnail: ImageBitmap? = null,
    val histogram: HistogramData? = null,
    val dqtQuality: Int = 0,
    val software: String? = null,
    val isModified: Boolean = false
)
```

with:

```kotlin
data class ImageForensicData(
    val bitmap: ImageBitmap? = null,
    val embeddedThumbnail: ImageBitmap? = null,
    val histogram: HistogramData? = null,
    val dqtQuality: Int = 0,
    val software: String? = null,
    val isModified: Boolean = false,
    val hasThumbnailReference: Boolean = false,
    val isDecodingFallback: Boolean = false,
)
```

- [ ] **Step 4: Restructure `tryExtractEmbeddedJpeg` to also return `hasThumbnailReference`**

In `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt`, add this private data class right after the `import` block (after line 7, before `object ImageAnalyzer {`):

```kotlin
private data class ThumbnailExtractionResult(val image: Image?, val hasThumbnailReference: Boolean)
```

Replace the whole `tryExtractEmbeddedJpeg` function (current lines 66-149) with:

```kotlin
    private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): ThumbnailExtractionResult {
        val meta = findFirst(root) { it.type == "meta" }
        val iloc = if (meta != null) findFirst(meta) { it.type == "iloc" } else null
        val iinf = if (meta != null) findFirst(meta) { it.type == "iinf" } else null
        val iref = if (meta != null) findFirst(meta) { it.type == "iref" } else null
        val pitm = if (meta != null) findFirst(meta) { it.type == "pitm" } else null
        val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()

        // Identify thumbnail item IDs via iref — this is a structural fact about the file
        // (used for hasThumbnailReference) independent of whether we can decode those items' bytes.
        val thumbIds = mutableSetOf<Long>()
        if (primaryId != null && iref != null) {
            for (ref in iref.children) {
                if (ref.type == "thmb") {
                    val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
                    val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
                    if (toIds.contains(primaryId) && fromId != null) thumbIds.add(fromId)
                }
            }
        }
        val hasThumbnailReference = thumbIds.isNotEmpty()

        val image = ByteReader.open(file).use { reader ->
            // --- Strategy 1: ISOBMFF Metadata (HEIC/AVIF/MP4) ---
            if (iloc != null) {
                // Map item IDs to types
                val itemTypes = mutableMapOf<Long, String>()
                iinf?.children?.forEach { infe ->
                    if (infe.type == "infe") {
                        val id = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull()
                        val type = infe.fields.find { it.name == "item_type" }?.value
                        if (id != null && type != null) itemTypes[id] = type
                    }
                }

                val idat = findFirst(root) { it.type == "idat" }
                val idatBase = if (idat != null) idat.offset + idat.headerSize else 0L

                // Try identified thumbnails first
                for (id in thumbIds) {
                    val img = extractItemById(reader, iloc, id, idatBase)
                    if (img != null) return@use img
                }

                // Try any JPEG items found in iinf
                for ((id, type) in itemTypes) {
                    if (id in thumbIds) continue
                    if (type.lowercase() == "jpeg" || type.lowercase() == "jpg") {
                        val img = extractItemById(reader, iloc, id, idatBase)
                        if (img != null) return@use img
                    }
                }
            }

            // --- Strategy 2: EXIF Scanning (Standard JPEG/TIFF) ---
            val exifNode = findFirst(root) { it.type == "Exif" || it.type == "APP1" }
            if (exifNode != null) {
                // Search for JPEG magic bytes in the EXIF/APP1 payload
                val limit = exifNode.offset + exifNode.size
                var scanPos = exifNode.offset
                while (scanPos < limit - 4) {
                    if (reader.readUInt8(scanPos) == 0xFF && reader.readUInt8(scanPos + 1) == 0xD8) {
                        try {
                            val possibleImg = Image.makeFromEncoded(reader.readBytes(scanPos, (limit - scanPos).toInt().coerceAtMost(1_000_000)))
                            if (possibleImg != null && possibleImg.width > 10) return@use possibleImg
                        } catch (e: Exception) {}
                    }
                    scanPos++
                }
            }

            // --- Strategy 3: Brute Force Magic Byte Scan (Last Ditch) ---
            val scanLimit = minOf(reader.length, 4_000_000L)
            var pos = 0L
            while (pos < scanLimit - 4) {
                if (reader.readUInt8(pos) == 0xFF && reader.readUInt8(pos + 1) == 0xD8) {
                    try {
                        val possibleImg = Image.makeFromEncoded(reader.readBytes(pos, (reader.length - pos).toInt().coerceAtMost(1_000_000)))
                        if (possibleImg != null && possibleImg.width > 10) return@use possibleImg
                    } catch (e: Exception) {}
                }
                pos++
            }
            null
        }

        return ThumbnailExtractionResult(image, hasThumbnailReference)
    }
```

Note the `return@use img` / `return@use possibleImg` labels (instead of the original bare `return img`) — `tryExtractEmbeddedJpeg` no longer returns `Image?` directly, so a bare `return` would now try to return an `Image` from a function typed `ThumbnailExtractionResult`, which won't compile. `return@use` returns from the `use { ... }` lambda only, becoming the value assigned to `image`.

- [ ] **Step 5: Update `analyze()` to consume the new return type**

In the same file, replace:

```kotlin
        // 2. Extract Embedded Thumbnail (Regardless of primary success)
        val thumbnail = tryExtractEmbeddedJpeg(file, root)

        val primaryBitmap = primaryImage?.toComposeImageBitmap()
        val thumbBitmap = thumbnail?.toComposeImageBitmap()
```

with:

```kotlin
        // 2. Extract Embedded Thumbnail (Regardless of primary success)
        val thumbnailResult = tryExtractEmbeddedJpeg(file, root)

        val primaryBitmap = primaryImage?.toComposeImageBitmap()
        val thumbBitmap = thumbnailResult.image?.toComposeImageBitmap()
```

And replace the `return ImageForensicData(...)` at the end of `analyze()`:

```kotlin
        return ImageForensicData(
            bitmap = primaryBitmap,
            embeddedThumbnail = thumbBitmap,
            histogram = histogram,
            dqtQuality = quality,
            software = software,
            isModified = isModified
        )
```

with:

```kotlin
        return ImageForensicData(
            bitmap = primaryBitmap,
            embeddedThumbnail = thumbBitmap,
            histogram = histogram,
            dqtQuality = quality,
            software = software,
            isModified = isModified,
            hasThumbnailReference = thumbnailResult.hasThumbnailReference,
        )
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.ImageAnalyzerTest" --console=plain`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 7: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (no regressions in existing `ImageAnalyzer`-adjacent behavior).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerTest.kt
git commit -m "Expose hasThumbnailReference from ImageAnalyzer for VLC fallback"
```

---

### Task 2: Headless one-shot VLC frame snapshot decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoderTest.kt` (new file)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `VlcImageSnapshotDecoder.decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit): Unit` — Task 3 calls this. Contract: `onResult` fires exactly once, on the AWT event thread, within ~5 seconds of the call, with either a decoded `ImageBitmap` or `null` (VLC unavailable, decode failure, or timeout).

This test exercises real `libvlc` (via `NativeDiscovery`), matching how `VlcVideoPlayer.kt` is already used elsewhere in this codebase without mocking — it requires VLC to be installed locally (same requirement as running the app itself). The garbage-input test below waits out the full internal timeout (~5s) since this design has no VLC error-event listener (kept out of scope per the approved spec — timeout alone is sufficient), so this test is slow but deterministic.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VlcImageSnapshotDecoderTest {
    @Test
    fun `decodeFirstFrameAsync delivers null for a file VLC cannot decode, within the timeout window`() {
        val file = File.createTempFile("vlc-snapshot-garbage-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // not a real media file — VLC will never produce a frame

        val latch = CountDownLatch(1)
        var result: ImageBitmap? = ImageBitmap(1, 1) // sentinel, overwritten by onResult

        VlcImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
            result = bitmap
            latch.countDown()
        }

        val delivered = latch.await(7, TimeUnit.SECONDS)
        assertTrue(delivered, "onResult was not called within 7 seconds")
        assertEquals(null, result)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.VlcImageSnapshotDecoderTest" --console=plain`
Expected: Compile error — `VlcImageSnapshotDecoder` does not exist yet.

- [ ] **Step 3: Implement `VlcImageSnapshotDecoder`**

Create `app/src/main/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoder.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.*
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.EventQueue
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless, one-shot fallback for images Skia's Image.makeFromEncoded can't decode (HEIC/HEVC and
 * other HEIF-family stills). Opens the file in libVLC exactly like a video, captures the first
 * rendered frame, then tears everything down — no visible window, no continuous playback.
 */
object VlcImageSnapshotDecoder {
    private const val TIMEOUT_MS = 5000L

    fun decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            NativeDiscovery().discover()
            val vlcArgs = arrayOf("--no-video-title-show", "--no-osd", "--quiet", "--avcodec-hw=none", "--no-audio")
            val resultLatch = CountDownLatch(1)
            val delivered = AtomicBoolean(false)
            var result: ImageBitmap? = null

            fun deliver(bitmap: ImageBitmap?) {
                if (delivered.compareAndSet(false, true)) {
                    result = bitmap
                    resultLatch.countDown()
                }
            }

            val factory = try {
                MediaPlayerFactory(*vlcArgs)
            } catch (e: Throwable) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            val mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

            var skiaBitmap = Bitmap()
            var pixelBuffer: ByteArray? = null
            var w = 0

            val bufferFormatCallback = object : BufferFormatCallbackAdapter() {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    w = sourceWidth
                    pixelBuffer = ByteArray(sourceWidth * sourceHeight * 4)
                    skiaBitmap = Bitmap().apply {
                        allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), sourceWidth, sourceHeight))
                    }
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
            }

            val renderCallback = object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer?) {}
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                    val byteBuffer = nativeBuffers[0]
                    val currentBuffer = pixelBuffer ?: return
                    if (byteBuffer.remaining() >= currentBuffer.size) {
                        byteBuffer.get(currentBuffer)
                        byteBuffer.rewind()
                        try {
                            skiaBitmap.installPixels(skiaBitmap.imageInfo, currentBuffer, w * 4)
                            deliver(Image.makeFromBitmap(skiaBitmap).toComposeImageBitmap())
                        } catch (e: Exception) {
                            deliver(null)
                        }
                    }
                }
                override fun unlock(mediaPlayer: MediaPlayer?) {}
            }

            mediaPlayer.videoSurface().set(CallbackVideoSurface(bufferFormatCallback, renderCallback, true, object : VideoSurfaceAdapter {
                override fun attach(mediaPlayer: MediaPlayer?, videoSurfaceHandle: Long) {}
            }))

            try {
                mediaPlayer.media().play(file.absolutePath)
            } catch (e: Throwable) {
                deliver(null)
            }

            // Single teardown point: blocks this background thread (never the caller) until the
            // render callback delivers a frame or the timeout elapses, then releases VLC resources
            // exactly once before posting the final result.
            resultLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            deliver(null) // no-op via compareAndSet if display() already delivered a result
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            factory.release()
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.VlcImageSnapshotDecoderTest" --console=plain`
Expected: BUILD SUCCESSFUL, 1 test passed (takes ~5 seconds due to the internal timeout).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoder.kt app/src/test/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoderTest.kt
git commit -m "Add headless one-shot VLC frame snapshot decoder"
```

---

### Task 3: Wire the VLC fallback into `AppState.openFile()`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:104-113`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`

**Interfaces:**
- Consumes: `ImageForensicData.hasThumbnailReference: Boolean`, `ImageForensicData.isDecodingFallback: Boolean` (Task 1); `VlcImageSnapshotDecoder.decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit)` (Task 2).
- Produces: `TabState.imageForensic` now transitions through an intermediate `isDecodingFallback = true` state (set synchronously, before `openFile()` returns) for `MediaType.IMAGE` tabs whose Skia decode failed. Task 4 reads this flag to render a loading state.

- [ ] **Step 1: Write the failing tests**

Add these two tests to the end of the `AppStateTest` class in `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `openFile on an undecodable IMAGE-type file synchronously sets isDecodingFallback before the VLC callback resolves`() {
        val file = File.createTempFile("appstate-heic-fallback-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will return null

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(MediaType.IMAGE, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(true, tab.imageForensic?.isDecodingFallback)
    }

    @Test
    fun `openFile on a VIDEO-type file never sets isDecodingFallback, even though its Skia decode also fails`() {
        val file = File.createTempFile("appstate-video-no-fallback-test", ".mp4")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will also return null here

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(MediaType.VIDEO, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(false, tab.imageForensic?.isDecodingFallback)
    }
```

- [ ] **Step 2: Run the tests to verify the first one fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.AppStateTest" --console=plain`
Expected: `openFile on an undecodable IMAGE-type file...` FAILs (`isDecodingFallback` is `false` — nothing sets it yet). The VIDEO test already passes today (nothing changed for that path yet) — that's expected; it exists to catch a regression once Step 3 changes the `IMAGE` branch.

- [ ] **Step 3: Wire the fallback into the `IMAGE` branch**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
            // Trigger analysis based on type
            when (tab.type) {
                MediaType.IMAGE -> tab.imageForensic = ImageAnalyzer.analyze(file, root)
                MediaType.VIDEO -> {
                    tab.videoAnalysis = VideoAnalyzer.analyze(file, root)
                    // Attempt to extract thumbnail for video files too
                    tab.imageForensic = ImageAnalyzer.analyze(file, root)
                }
                else -> {}
            }
```

with:

```kotlin
            // Trigger analysis based on type
            when (tab.type) {
                MediaType.IMAGE -> {
                    val forensic = ImageAnalyzer.analyze(file, root)
                    if (forensic.bitmap == null) {
                        // Skia has no HEIF/HEVC decoder — fall back to VLC, async so the UI never blocks.
                        tab.imageForensic = forensic.copy(isDecodingFallback = true)
                        VlcImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
                            val current = tab.imageForensic ?: forensic
                            tab.imageForensic = current.copy(
                                bitmap = bitmap,
                                embeddedThumbnail = current.embeddedThumbnail
                                    ?: (bitmap.takeIf { current.hasThumbnailReference }),
                                isDecodingFallback = false,
                            )
                        }
                    } else {
                        tab.imageForensic = forensic
                    }
                }
                MediaType.VIDEO -> {
                    tab.videoAnalysis = VideoAnalyzer.analyze(file, root)
                    // Attempt to extract thumbnail for video files too
                    tab.imageForensic = ImageAnalyzer.analyze(file, root)
                }
                else -> {}
            }
```

(`VlcImageSnapshotDecoder` needs no import — it's already in the `com.multiviewer.ui` package, same as `AppState.kt`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.AppStateTest" --console=plain`
Expected: BUILD SUCCESSFUL, all `AppStateTest` tests pass, including both new ones.

- [ ] **Step 5: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "Fall back to VLC decoding for images Skia cannot decode"
```

---

### Task 4: Loading state in the UI, and end-to-end manual verification

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:71-73`

**Interfaces:**
- Consumes: `ImageForensicData.isDecodingFallback: Boolean` (Task 1, populated by Task 3).
- Produces: nothing consumed by later tasks — this is the final, user-visible piece of the feature.

No automated test: this project has no Compose UI testing setup (`androidx.compose.ui.test` is not a dependency, and no existing file under `ui/` has a corresponding test — confirmed by search), so this task's deliverable is verified manually by running the app, matching the project's existing convention for Compose-layer changes.

- [ ] **Step 1: Show a loading state instead of "Primary Image Decoding Failed" while the VLC fallback is in flight**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, replace:

```kotlin
                        forensic.bitmap?.let { 
                            PixelInspectorPreview(it) 
                        } ?: Text("Primary Image Decoding Failed", color = AppColors.NeonRed, fontSize = 12.sp)
```

with:

```kotlin
                        forensic.bitmap?.let { 
                            PixelInspectorPreview(it) 
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via VLC..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )
```

(`AppColors.TextSecondary` is already used elsewhere in this same file — e.g. `DetailedPropertiesPanel`'s "Select a marker to view details" text — so no new color needs to be defined.)

- [ ] **Step 2: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors.

- [ ] **Step 3: Manually verify against a real HEIC file with no thumbnail reference**

Open `/Library/User Pictures/Flowers/Whiterose.heic` in the running app (this file was confirmed during investigation to be HEVC-only with no `thmb` `iref` entry — `strings -a` on it shows `hvc1`/`iprp` and zero `jpeg` occurrences).

Expected:
- Right panel ("PRIMARY IMAGE VIEW") briefly shows "Decoding via VLC..." then displays the decoded flower photo.
- Left panel ("EMBEDDED EXIF THUMBNAIL") shows "No Embedded Thumbnail" (this file has no `thmb` reference, so `hasThumbnailReference` is `false` and the left panel is correctly left alone).

- [ ] **Step 4: Manually verify against a real JPEG file (no regression)**

Open any ordinary `.jpg` file with an embedded EXIF thumbnail.

Expected: Both panels render exactly as before this change — Skia's `Image.makeFromEncoded` succeeds, so `bitmap != null` and the VLC fallback path in `AppState.kt` never triggers.

- [ ] **Step 5: (If an iPhone-originated HEIC file is available) verify the `hasThumbnailReference == true` path**

Open an HEIC photo taken on an iPhone (these typically do have a `thmb` `iref` entry pointing at the primary item).

Expected: Both left and right panels show the same decoded image (the left panel reusing the primary image's bitmap as a stand-in, per this feature's design — see `docs/superpowers/specs/2026-07-24-heic-vlc-fallback-decoding-design.md`'s Non-Goals).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show a loading state in the primary image panel while VLC decodes"
```
