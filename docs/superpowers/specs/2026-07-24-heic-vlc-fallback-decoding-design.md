# HEIC/HEVC Primary Image Decoding via VLC Fallback — Design

## Background

The center-top panel in `ImageInspectorUI` shows two previews side by side: the left box renders the file's *embedded thumbnail* (`ImageForensicData.embeddedThumbnail`, extracted by `ImageAnalyzer.tryExtractEmbeddedJpeg`), and the right box renders the *primary image* (`ImageForensicData.bitmap`, decoded by `org.jetbrains.skia.Image.makeFromEncoded(bytes)` in `ImageAnalyzer.analyze`).

For HEIC files, both boxes currently fail. Investigation (systematic-debugging pass, prior turn) traced this to a codec gap rather than a parsing bug:

- Skiko (JetBrains' Skia build used by Compose Multiplatform Desktop) has no HEIF/HEVC decoder. `Image.makeFromEncoded` returns `null` for any HEIC byte stream, regardless of how well-formed it is.
- `tryExtractEmbeddedJpeg`'s three strategies (ISOBMFF `iloc`/`iinf`/`iref`/`pitm` metadata, `Exif`/`APP1` byte scanning, brute-force magic-byte scanning) are all built around finding and decoding a literal embedded **JPEG** byte stream. Verified against a real HEIC sample (`strings` + hex dump on `/Library/User Pictures/Flowers/Whiterose.heic`): the file's only image data is `hvc1` (HEVC), and it contains zero JPEG bytes anywhere. Even when Strategy 1 correctly locates a `thmb`-referenced item via `iref`, that item's bytes fail the `0xFF 0xD8` JPEG magic check and are discarded. The box parser itself (`IlocBoxDecoder`, `IinfBoxDecoder`, `InfeBoxDecoder`, `IrefBoxDecoder`, `PitmBoxDecoder`, `MetaBoxDecoder`) is correct and unaffected by this design.

The app already has a working HEVC decode path: `VlcVideoPlayer.kt` drives `libvlc` (via vlcj) through a `CallbackVideoSurface`/`RenderCallback` pair to render video frames into a Skia `Bitmap`, with no visible native window required — the callback delivers raw RGBA pixels directly. VLC (via its bundled `libavformat`/`libheif` support) can open a `.heic` file the same way it opens a video file, demuxing and decoding the primary HEVC item as a single displayable frame. This design reuses that mechanism as a fallback source of pixels when Skia's `Image.makeFromEncoded` fails, scoped generically (any format, not just `.heic`) so `.avif` and similar HEIF-family stills that hit the same Skia gap benefit without a separate change.

## Goal

Opening a HEIC (or any image format Skia's `Image.makeFromEncoded` cannot decode) shows the decoded primary image in the right-hand "PRIMARY IMAGE VIEW" panel, decoded via VLC as a fallback. The left-hand "EMBEDDED EXIF THUMBNAIL" panel shows the same decoded image when the file structurally references a `thmb` item (even though that item's own bytes can't be independently decoded), and shows "No Embedded Thumbnail" when it doesn't. The UI never blocks while VLC decodes; a loading state is shown until the frame arrives or decoding definitively fails.

## Non-Goals

- No independent decode of the *actual* `thmb` item bytes (a separate, smaller HEVC-coded image distinct from the primary item). The left panel reuses the primary image's decoded bitmap as a stand-in whenever a `thmb` reference exists — confirmed acceptable with the user, since most HEIC thumbnails are visually near-identical to the primary image at the preview sizes this panel renders.
- No change to `tryExtractEmbeddedJpeg`'s three existing strategies — they remain exactly as-is for JPEG/TIFF/PNG files where they already work. This design only adds a fallback for when the *whole pipeline* (Skia primary decode) fails.
- No VLC fallback for `MediaType.VIDEO` tabs. `AppState.kt` already calls `ImageAnalyzer.analyze` for video tabs to attempt a thumbnail, but `tab.imageForensic` is never read anywhere in `VideoInspectorUI.kt` (confirmed by search) — it's dead output today. Adding VLC decoding there would spin up a redundant `libvlc` instance for no visible effect, and could contend with the real `VlcVideoPlayer` already playing that same file. Fallback is gated to `MediaType.IMAGE` only.
- No shared/pooled VLC instance. Each fallback decode creates its own `MediaPlayerFactory`/`MediaPlayer` and releases it immediately after capturing one frame (or timing out) — confirmed acceptable given `MAX_OPEN_FILES = 2`.

## Design

### 1. `ImageForensicData` gains two fields (`AppState.kt`)

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

- `hasThumbnailReference`: true if the box tree has an `iref` `thmb` entry pointing at the primary item, regardless of whether that item's bytes could be decoded as JPEG. Purely structural — no byte decoding involved.
- `isDecodingFallback`: true while a VLC fallback decode is in flight for this tab, so the UI can distinguish "still trying" from "gave up."

### 2. `ImageAnalyzer.kt` — surface `hasThumbnailReference`

`tryExtractEmbeddedJpeg` already computes `thumbIds` (the set of item IDs referenced via `iref` `thmb` entries pointing at the primary item) inside its ISOBMFF strategy block. Restructure so `analyze()` can see whether that set was non-empty, without changing any of the three strategies' extraction logic:

```kotlin
private data class ThumbnailExtractionResult(val image: Image?, val hasThumbnailReference: Boolean)

private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): ThumbnailExtractionResult {
    // ... existing logic unchanged ...
    // thumbIds computed as today; capture `thumbIds.isNotEmpty()` before returning
}
```

`analyze()` unpacks this into the two `ImageForensicData` fields it already needs (`thumbBitmap` via `.image`, plus the new `hasThumbnailReference` via `.hasThumbnailReference`).

### 3. New file: `VlcImageSnapshotDecoder.kt` (`com.multiviewer.ui`)

A headless, one-shot sibling to `VlcVideoPlayer`'s continuous player — same `CallbackVideoSurface`/`BufferFormatCallbackAdapter`/`RenderCallback` wiring, but captures exactly one frame and tears everything down.

```kotlin
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

            // Single teardown point: blocks this background thread (not the caller) until
            // the render callback delivers a frame, the timeout elapses, or play() throws.
            resultLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            deliver(null) // no-op if display() already delivered a result
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            factory.release()
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

Single teardown point: the background thread blocks on `resultLatch` (frame delivered, or `TIMEOUT_MS` elapses) before releasing VLC resources and posting the final result — `deliver` is idempotent (`AtomicBoolean.compareAndSet`) so a late `display()` callback arriving after timeout can't double-count. `onResult` is guaranteed to fire exactly once, on the AWT event thread, within `TIMEOUT_MS` of the call.

New imports beyond what `VlcVideoPlayer.kt` already uses: `java.util.concurrent.CountDownLatch`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicBoolean`.

### 4. `AppState.kt` — wire the fallback into `openFile`

```kotlin
MediaType.IMAGE -> {
    val forensic = ImageAnalyzer.analyze(file, root)
    if (forensic.bitmap == null) {
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
```

Reading `tab.imageForensic` (rather than the closed-over `forensic`) inside the callback guards against the tab having been closed/replaced by the time VLC's async result arrives — matches the existing app's `TabState` being a live mutable object rather than a snapshot.

### 5. `ImageInspectorUI.kt` — loading state

Right panel's "no bitmap" branch splits into two cases:

```kotlin
forensic.bitmap?.let {
    PixelInspectorPreview(it)
} ?: Text(
    if (forensic.isDecodingFallback) "Decoding via VLC..." else "Primary Image Decoding Failed",
    color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
    fontSize = 12.sp,
)
```

Left panel is unaffected by this design directly — it already renders `forensic.embeddedThumbnail` when present, which will simply become non-null once the fallback populates it per the rule in §4.

## Error Handling

- VLC/`libvlc` unavailable in the runtime environment (matches `VlcVideoPlayer`'s existing "VLC Initialization Failed" case): `MediaPlayerFactory` construction throws, `deliver(null)` fires immediately, panel falls back to "Primary Image Decoding Failed."
- File opens in VLC but never produces a frame (corrupt file, genuinely unsupported codec): the 5-second timeout guard calls `deliver(null)`, same end state.
- Both failure paths converge on `isDecodingFallback = false, bitmap = null` — the UI text distinguishes only "trying" vs. "gave up," not the specific failure reason, consistent with how the rest of `ImageInspectorUI` already reports failures.

## Testing

- Unit: `ImageAnalyzer`'s restructured thumbnail extraction — a synthetic ISOBMFF tree with an `iref` `thmb` entry but a non-JPEG item payload asserts `hasThumbnailReference == true` and `embeddedThumbnail == null`; a tree with no `iref` box at all asserts `hasThumbnailReference == false`.
- Manual: open the confirmed-HEVC-only macOS sample (`Whiterose.heic`, no `thmb` reference) — expect right panel to show the VLC-decoded image, left panel to show "No Embedded Thumbnail." Open a real iPhone-originated HEIC (typically has a `thmb` reference) — expect both panels to show the same decoded image. Open a normal JPEG — expect zero behavior change (Skia succeeds, VLC path never triggers). Temporarily rename/corrupt a HEIC file's extension-matching content to confirm the timeout path resolves to "Primary Image Decoding Failed" rather than hanging.
