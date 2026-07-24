# ffmpeg-Based Video Player (Sub-project A of VLC Removal) — Design

## Background

This app currently uses `VlcVideoPlayer.kt` (vlcj/libvlc) for two things: standalone video tabs (`VideoInspectorUI`) and the Motion Photo embedded-video preview panel (`ImageInspectorUI`'s `MotionPhotoVideoPreview`). Deploying to Windows/Linux surfaced a real problem: both VLC and ffmpeg must be installed separately by the end user, and while bundling a single ffmpeg binary into the packaged app is straightforward (a future sub-project, "B"), bundling VLC's full runtime (hundreds of plugin/codec files, ~100MB+) is much larger and more fragile. Since ffmpeg (already a runtime dependency for HEIC decoding, `FfmpegImageSnapshotDecoder.kt`) can also decode and output raw video frames, this design replaces the VLC-based player with an ffmpeg-subprocess-based one, so the whole app converges on a single external dependency instead of two.

Two things were empirically verified against a real portrait phone video (`/Users/dong.kim/Downloads/20260718_200431_motion.mp4`, HEVC, 1752×984 raw decode, `rotation=-90` side data, `avg`≈30fps declared as `r_frame_rate=120/1` container rate) before committing to this design:
- `ffmpeg -i in.mp4 -frames:v 1 -update 1 out.png` (the same transcode path `-f rawvideo` output goes through) produces a **984×1752** PNG — ffmpeg auto-applies the display-matrix rotation when transcoding to a raw/pixel output format. No manual rotation handling is needed in this player, unlike the VLC version which needed the `--no-videotoolbox` workaround for exactly this rotation case.
- `ffprobe`'s `r_frame_rate` (the container's nominal timebase, `120/1` here) is not a usable playback rate for this file — the real content is ~30fps. `avg_frame_rate` (computed from actual frame count/duration) must be used instead, with a sane fallback if it's unparseable (`0/0`, which `ffprobe` reports for some streams).

## Goal

A new `FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier)` composable — same call signature as today's `VlcVideoPlayer`, so migrating call sites (sub-project C) is a one-line change per call site — that: shows the first decoded frame immediately (paused), lets the user click a play button to start real-time playback, lets them click again to pause, and requires no native library (vlcj/libvlc) at all — only a subprocess call to `ffmpeg`/`ffprobe`, exactly like `FfmpegImageSnapshotDecoder` already does for stills.

## Non-Goals

- No seek bar / scrubbing — `VlcVideoPlayer` doesn't have one today either; feature parity, not an upgrade.
- No audio playback — `VlcVideoPlayer` already runs with `--no-audio` ("Mute for inspector usage"); this player never even demuxes audio (`-an`).
- No bundled/resolved ffmpeg path — this sub-project calls the literal `"ffmpeg"`/`"ffprobe"` commands via `PATH`, exactly like `FfmpegImageSnapshotDecoder` does today. Path resolution for a bundled binary is sub-project B's job, done once for both call sites together — introducing that abstraction here would be speculative.
- No frame-exact resume after pause. Pausing stops reading ffmpeg's stdout pipe; ffmpeg keeps decoding until its OS pipe buffer fills, then blocks (no CPU/GPU work wasted, no process restart needed). On resume, whatever few frames ffmpeg decoded ahead into that buffer during the pause are drained quickly before real-time playback catches up — a small, likely-imperceptible catch-up blip for this inspector tool, not a professional editor's frame-accurate pause. Achieving frame-exact pause would require killing and re-seeking the process on every pause/resume, which is real added complexity for a preview-only feature.
- No auto-loop or auto-advance at end of stream — playback stops and the last frame stays visible (matches `VlcVideoPlayer`'s `stopped` → `isPlaying = false` behavior, no explicit "did it end vs. get closed" distinction needed since there's no seek bar to reflect it either way).

## Design

### 1. Probing: width, height, playback fps (`ffprobe`)

```kotlin
private data class VideoInfo(val width: Int, val height: Int, val fps: Double)

private fun probeVideo(file: File): VideoInfo? {
    return try {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).start()
        val line = process.inputStream.bufferedReader().readLine() ?: return null
        process.waitFor(5, TimeUnit.SECONDS)
        val parts = line.split(",")
        if (parts.size < 4) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        val fps = parseFrameRate(parts[2]) ?: parseFrameRate(parts[3]) ?: 30.0
        VideoInfo(width, height, fps)
    } catch (e: Exception) {
        null
    }
}

private fun parseFrameRate(fraction: String): Double? {
    val (num, den) = fraction.split("/").let { it.getOrNull(0) to it.getOrNull(1) }
    val n = num?.toDoubleOrNull() ?: return null
    val d = den?.toDoubleOrNull() ?: return null
    if (d == 0.0 || n == 0.0) return null
    return n / d
}
```

`avg_frame_rate` is tried first (the real, content-derived rate); `r_frame_rate` (container nominal rate) is the fallback if `avg_frame_rate` is `0/0`; a hardcoded `30.0` is the last-resort fallback if both are unparseable, so a probe hiccup degrades to "plays at a reasonable guessed rate" rather than a crash or division by zero.

### 2. Frame streaming (`ffmpeg -f rawvideo`)

```kotlin
ProcessBuilder(
    "ffmpeg", "-i", file.absolutePath,
    "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
    "-r", info.fps.toString(),
    "-",
).redirectError(ProcessBuilder.Redirect.DISCARD).start()
```

Output is a continuous stream of `width * height * 4` byte BGRA frames (matches the pixel format `VlcVideoPlayer`'s Skia `Bitmap` already used — `ColorType.BGRA_8888` — so the same `installPixels`/`Image.makeFromBitmap` conversion code applies unchanged). `-r <fps>` forces ffmpeg to resample to a constant rate (duplicating/dropping source frames as needed), so the reader thread can pace itself with a fixed `1000.0 / fps` ms interval per frame instead of needing per-frame presentation timestamps (which raw pipe output doesn't carry).

### 3. Playback state machine (Composable + background reader thread)

```kotlin
@Composable
fun FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier) {
    var videoBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf(false) }

    val info = remember(file) { probeVideo(file) }

    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read video (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    DisposableEffect(file) {
        val process = try {
            ProcessBuilder(
                "ffmpeg", "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
                "-r", info.fps.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            null
        }
        // probeVideo() already succeeded, so ffmpeg/ffprobe are known to work in general -- if this
        // second, separate process still fails to start, that's a genuine (if rare) failure to
        // surface, not silent: without this flag, the UI would otherwise sit on "Decoding stream..."
        // forever, since nothing would ever deliver a frame or any other terminal state.
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)

        val readerThread = if (process != null) {
            Thread {
                val frameSize = info.width * info.height * 4
                val buffer = ByteArray(frameSize)
                val frameDurationMs = (1000.0 / info.fps).toLong()
                val input = process.inputStream

                fun readFrame(): Boolean {
                    var offset = 0
                    while (offset < frameSize) {
                        val read = input.read(buffer, offset, frameSize - offset)
                        if (read < 0) return false
                        offset += read
                    }
                    return true
                }

                fun deliver() {
                    val bitmap = Bitmap().apply {
                        allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), info.width, info.height))
                        installPixels(imageInfo, buffer, info.width * 4)
                    }
                    val snapshot = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                    EventQueue.invokeLater { videoBitmap = snapshot }
                }

                if (readFrame()) deliver() // first frame, shown immediately while paused
                while (!stopped.get()) {
                    if (!isPlaying) {
                        Thread.sleep(50)
                        continue
                    }
                    val start = System.currentTimeMillis()
                    if (!readFrame()) break // EOF
                    deliver()
                    val remaining = frameDurationMs - (System.currentTimeMillis() - start)
                    if (remaining > 0) Thread.sleep(remaining)
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            process?.destroyForcibly()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val currentFrame = videoBitmap
        if (loadError) {
            Text("Could not start ffmpeg playback", color = Color.White)
        } else if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Decoding stream...", color = Color.Gray)
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 10.sp)
            }
        }
        if (!isPlaying) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
        }
    }
}
```

The reader thread's loop condition reads `isPlaying` directly — the same Compose `MutableState<Boolean>` the click handlers write — rather than a separate `AtomicBoolean` mirror of it: Compose's snapshot-based `MutableState` is safe to read from a background thread (this is a documented, standard pattern, not a data race), and introducing a second boolean would just create two sources of truth that could drift. `stopped` is a real `AtomicBoolean` because it isn't Compose state at all — it's a plain shutdown signal set once from `onDispose`, needing a real cross-thread visibility guarantee that a plain `var` wouldn't provide.

**Resource cleanup**: `onDispose` sets `stopped`, interrupts the reader thread (unblocks it if mid-`Thread.sleep`), and force-destroys the ffmpeg process — mirrors `VlcVideoPlayer`'s `onDispose { mediaPlayer.release(); factory.release() }` pattern, adapted for a subprocess instead of a native player handle.

**Error handling**: `probeVideo` returning `null` (ffmpeg/ffprobe not on PATH, corrupt file, `ProcessBuilder.start()` throwing) shows a clear "Could not read video" message immediately, without ever starting the frame-streaming process — mirrors `VlcVideoPlayer`'s `playerState == null` → "VLC Initialization Failed" branch. Separately, if `probeVideo` succeeds but the second `ProcessBuilder(...).start()` call for the raw-frame-streaming process itself throws, `loadError` surfaces a distinct "Could not start ffmpeg playback" message — without this, the UI would otherwise be stuck showing "Decoding stream..." forever, since no frame and no other terminal state would ever arrive.

## Testing

- Unit: `parseFrameRate` — table-driven test over `"30/1"` → `30.0`, `"24000/1001"` → ≈`23.976`, `"0/0"` → `null`, a malformed string → `null`. This is the one pure, easily-unit-testable piece of this design; the rest (subprocess piping, Compose lifecycle, timing) has no existing test coverage precedent in this codebase for equivalent code (`VlcVideoPlayer.kt`, `FfmpegImageSnapshotDecoder.kt`'s subprocess-timing paths) and isn't practical to unit test.
- Manual: open the portrait test video (`20260718_200431_motion.mp4`) — confirm it displays right-side-up (984×1752, not sideways) immediately on load, paused; click play, confirm smooth real-time playback; click again, confirm it pauses (frame freezes, doesn't jump ahead noticeably); close the tab mid-playback, confirm the `ffmpeg` process is no longer running afterward (`ps aux | grep ffmpeg` should show nothing left over). Open a landscape, non-rotated video for a regression check. Open the Motion Photo embedded-video panel with this player wired in temporarily (or defer this check to sub-project C's migration, whichever is available first) to confirm the embedded/short-clip case also works.
