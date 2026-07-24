# HEIC/HEVC Primary Image Decoding via ffmpeg Fallback — Design

## Background

The center-top panel in `ImageInspectorUI` shows two previews side by side: the left box renders the file's *embedded thumbnail* (`ImageForensicData.embeddedThumbnail`, extracted by `ImageAnalyzer.tryExtractEmbeddedJpeg`), and the right box renders the *primary image* (`ImageForensicData.bitmap`, decoded by `org.jetbrains.skia.Image.makeFromEncoded(bytes)` in `ImageAnalyzer.analyze`).

For HEIC files, both boxes currently fail. Investigation (systematic-debugging pass, prior turn) traced this to a codec gap rather than a parsing bug:

- Skiko (JetBrains' Skia build used by Compose Multiplatform Desktop) has no HEIF/HEVC decoder. `Image.makeFromEncoded` returns `null` for any HEIC byte stream, regardless of how well-formed it is.
- `tryExtractEmbeddedJpeg`'s three strategies (ISOBMFF `iloc`/`iinf`/`iref`/`pitm` metadata, `Exif`/`APP1` byte scanning, brute-force magic-byte scanning) are all built around finding and decoding a literal embedded **JPEG** byte stream. Verified against a real HEIC sample (`strings` + hex dump on `/Library/User Pictures/Flowers/Whiterose.heic`): the file's only image data is `hvc1` (HEVC), and it contains zero JPEG bytes anywhere. Even when Strategy 1 correctly locates a `thmb`-referenced item via `iref`, that item's bytes fail the `0xFF 0xD8` JPEG magic check and are discarded. The box parser itself (`IlocBoxDecoder`, `IinfBoxDecoder`, `InfeBoxDecoder`, `IrefBoxDecoder`, `PitmBoxDecoder`, `MetaBoxDecoder`) is correct and unaffected by this design.

**Revision history:** the first version of this design (implemented, reviewed, and merged the same day) used `libvlc` (via vlcj) as the fallback decoder, reusing `VlcVideoPlayer.kt`'s existing `CallbackVideoSurface`/`RenderCallback` pattern. Manual end-to-end verification against the installed `VLC.app` on this machine (both the confirmed-HEVC-only `Whiterose.heic` sample and a real, `grid`-tiled Samsung-originated HEIC) showed the fallback always timing out — "Primary Image Decoding Failed" every time. Bypassing the app entirely and running the VLC CLI directly against both files confirmed the root cause: this VLC build has **no HEIF demuxer at all** (`vlc --list | grep -i heif` returns nothing; `vlc file.heic` fails with `mov demux error: moov atom not found`, since VLC's `mov`/`avcodec` demuxer expects a video-shaped ISOBMFF file and HEIC's `meta`/`iloc`/`pitm` structure isn't one). The core premise of that design — "VLC can open a HEIC file the way it opens a video" — does not hold on this platform's VLC build, so it was not a fixable bug within that architecture; the fallback decoder itself needed to change.

A standalone `ffmpeg` binary (installed via Homebrew for this verification: `brew install ffmpeg`, version 8.1.2) was tested against the same two files and succeeded on both: `ffmpeg -y -i in.heic -frames:v 1 -update 1 out.png` produced a correct, full-resolution PNG in both cases (512×512 for the simple non-tiled sample; 2252×4000 for the real grid-tiled photo — ffmpeg's `mov` demuxer correctly reassembled all 40 tiles referenced via the file's `iref`/`dimg` entries). Both output PNGs were visually confirmed to be the correct, undistorted photos. This design replaces the VLC-based fallback with a `ffmpeg` subprocess call, keeping every other piece of the original design (the `ImageForensicData` fields, the `AppState.openFile` wiring, the UI loading state) unchanged.

## Goal

Opening a HEIC (or any image format Skia's `Image.makeFromEncoded` cannot decode) shows the decoded primary image in the right-hand "PRIMARY IMAGE VIEW" panel, decoded via a `ffmpeg` subprocess as a fallback. The left-hand "EMBEDDED EXIF THUMBNAIL" panel shows the same decoded image when the file structurally references a `thmb` item (even though that item's own bytes can't be independently decoded), and shows "No Embedded Thumbnail" when it doesn't. The UI never blocks while ffmpeg decodes; a loading state is shown until the frame arrives or decoding definitively fails.

## Non-Goals

- No independent decode of the *actual* `thmb` item bytes (a separate, smaller HEVC-coded image distinct from the primary item). The left panel reuses the primary image's decoded bitmap as a stand-in whenever a `thmb` reference exists — confirmed acceptable with the user, since most HEIC thumbnails are visually near-identical to the primary image at the preview sizes this panel renders.
- No change to `tryExtractEmbeddedJpeg`'s three existing strategies — they remain exactly as-is for JPEG/TIFF/PNG files where they already work. This design only adds a fallback for when the *whole pipeline* (Skia primary decode) fails.
- No fallback for `MediaType.VIDEO` tabs. `AppState.kt` already calls `ImageAnalyzer.analyze` for video tabs to attempt a thumbnail, but `tab.imageForensic` is never read anywhere in `VideoInspectorUI.kt` (confirmed by search) — it's dead output today. Fallback is gated to `MediaType.IMAGE` only.
- No bundling of a platform-specific `ffmpeg` binary into the packaged app (DMG/MSI/DEB). This design shells out to whatever `ffmpeg` is found on the system `PATH` — the user must have it installed (e.g. via Homebrew, apt, or an official Windows build). Bundling static per-platform binaries into `nativeDistributions` is real, additional packaging work (binary acquisition/licensing/path resolution) explicitly deferred to a future increment; confirmed acceptable with the user for this iteration.
- No differentiated error messaging for "ffmpeg not found on PATH" vs. "ffmpeg failed to decode this file" vs. "timed out" — all three collapse to the same existing "Primary Image Decoding Failed" text, consistent with how the rest of `ImageInspectorUI` already reports failures without a reason string.

## Design

### 1. `ImageForensicData` gains two fields (`AppState.kt`)

*(Unchanged from the original design — already implemented and merged.)*

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
- `isDecodingFallback`: true while a fallback decode is in flight for this tab, so the UI can distinguish "still trying" from "gave up."

### 2. `ImageAnalyzer.kt` — surface `hasThumbnailReference`

*(Unchanged from the original design — already implemented and merged.)* `tryExtractEmbeddedJpeg` returns a `ThumbnailExtractionResult(image: Image?, hasThumbnailReference: Boolean)`; `analyze()` unpacks both fields into `ImageForensicData`.

### 3. New file: `FfmpegImageSnapshotDecoder.kt` (`com.multiviewer.ui`)

Replaces `VlcImageSnapshotDecoder.kt` (deleted, along with its test — the VLC-based approach doesn't work on this platform's VLC build, see Background). A headless, one-shot decoder that shells out to `ffmpeg` to extract exactly one decoded frame as a temporary PNG, then hands its bytes to Skia (the same decoder every other supported format in this app already goes through).

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless, one-shot fallback for images Skia's Image.makeFromEncoded can't decode (HEIC/HEVC and
 * other HEIF-family stills). Shells out to a system `ffmpeg` (must be on PATH) to extract the
 * primary frame as a temporary PNG, then decodes that PNG via Skia like any other supported image.
 */
object FfmpegImageSnapshotDecoder {
    private const val TIMEOUT_MS = 8000L

    fun decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val tempPng = try {
                File.createTempFile("ffmpeg-snapshot-", ".png")
            } catch (e: Exception) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            tempPng.deleteOnExit()

            val result = try {
                val process = ProcessBuilder(
                    "ffmpeg", "-y", "-i", file.absolutePath,
                    "-frames:v", "1", "-update", "1",
                    tempPng.absolutePath,
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
                    null
                } else {
                    Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
                }
            } catch (e: Exception) {
                // ProcessBuilder.start() throws IOException when `ffmpeg` isn't on PATH.
                null
            } finally {
                tempPng.delete()
            }

            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

No native library, no callback/render-surface wiring, no cross-thread pixel buffer, no idempotent-delivery bookkeeping — `waitFor(TIMEOUT_MS, ...)` blocks this single background thread synchronously (never the caller), so there is exactly one linear path to exactly one `onResult` call, posted via `EventQueue.invokeLater` to match the rest of the app's Compose-state-from-background-thread convention (`VlcVideoPlayer.kt` uses the same handover). The temp file is deleted in a `finally` block regardless of outcome; `deleteOnExit()` is a backstop in case the JVM is killed before that runs.

**Timeout:** 8000 ms (`TIMEOUT_MS`) — raised from the original VLC design's 5000 ms. Measured against a real 4000×2252, 40-tile grid HEIC on this machine, `ffmpeg` took 1.44 s; 8 s leaves headroom for slower hardware or larger files while still failing fast rather than hanging.

### 4. `AppState.kt` — wire the fallback into `openFile`

Identical structure to the original design; only the object name changes.

```kotlin
MediaType.IMAGE -> {
    val forensic = ImageAnalyzer.analyze(file, root)
    if (forensic.bitmap == null) {
        tab.imageForensic = forensic.copy(isDecodingFallback = true)
        FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
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

Reading `tab.imageForensic` (rather than the closed-over `forensic`) inside the callback guards against the tab having been closed/replaced by the time the async result arrives — matches the existing app's `TabState` being a live mutable object rather than a snapshot.

### 5. `ImageInspectorUI.kt` — loading state

Only the loading-state copy changes (VLC → ffmpeg); logic and color rules are unchanged.

```kotlin
forensic.bitmap?.let {
    PixelInspectorPreview(it)
} ?: Text(
    if (forensic.isDecodingFallback) "Decoding via ffmpeg..." else "Primary Image Decoding Failed",
    color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
    fontSize = 12.sp,
)
```

Left panel is unaffected by this design directly — it already renders `forensic.embeddedThumbnail` when present, which will simply become non-null once the fallback populates it per the rule in §4.

## Error Handling

- `ffmpeg` not installed / not on `PATH`: `ProcessBuilder.start()` throws `IOException`, caught, delivers `null` — panel falls back to "Primary Image Decoding Failed."
- `ffmpeg` runs but fails to decode the file (corrupt file, genuinely unsupported codec/container): non-zero exit code, delivers `null`, same end state.
- `ffmpeg` hangs or takes longer than `TIMEOUT_MS`: `waitFor` returns `false`, the process is force-killed via `destroyForcibly()`, delivers `null`, same end state.
- All failure paths converge on `isDecodingFallback = false, bitmap = null` — the UI text distinguishes only "trying" vs. "gave up," not the specific failure reason, consistent with how the rest of `ImageInspectorUI` already reports failures.

## Testing

- Unit: `ImageAnalyzer`'s restructured thumbnail extraction (already implemented, unaffected by this revision) — a synthetic ISOBMFF tree with an `iref` `thmb` entry but a non-JPEG item payload asserts `hasThumbnailReference == true` and `embeddedThumbnail == null`; a tree with no `iref` box at all asserts `hasThumbnailReference == false`.
- `FfmpegImageSnapshotDecoder`: a garbage (non-media) input file asserts `onResult(null)` fires within the timeout window (ffmpeg will exit non-zero quickly for unrecognized content, so this should resolve well under `TIMEOUT_MS`, not by exhausting the full timeout the way the old VLC-based test had to). This test requires `ffmpeg` to be installed and on `PATH` in the environment running it, matching this codebase's existing convention of testing VLC-dependent code (`VlcVideoPlayer.kt`) against the real native dependency rather than mocking it.
- Manual: open the confirmed-HEVC-only macOS sample (`Whiterose.heic`, no `thmb` reference) — expect right panel to show the ffmpeg-decoded image, left panel to show "No Embedded Thumbnail." Open the real Samsung-originated grid-tiled HEIC at `/Users/dong.kim/Downloads/20260715_223835.heic` (has a `thmb` reference per its `iref` box) — expect both panels to show the same correctly-assembled, full-resolution decoded image. Open a normal JPEG — expect zero behavior change (Skia succeeds, the fallback path never triggers).
