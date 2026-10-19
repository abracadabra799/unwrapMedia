# Half-resolution video playback preview

## Intent

Windows playback may be limited by the uncompressed BGRA pipe. A JDK 21/macOS benchmark of 300 1280×720 frames showed that NV12 reduced pipe-read time but its CPU YUV→BGRA conversion raised total processing time from about 0.53 s to 0.76 s. Use the user's requested fallback: halve the playback preview's current 1280-pixel long-side cap to 640 pixels. This reduces maximum BGRA frame area and pipe bytes to about one quarter. Keep ffmpeg's passthrough frame count and the existing audio/PTS timing.

## Scope

Change only the video playback pipe's scale filter in `FfmpegVideoPlayer`. Continue reading ffmpeg's actual output dimensions from stderr for the raw-frame buffer. Do not upscale source frames smaller than the cap. The popup's separate native still-frame decoder remains available for full-resolution forensic inspection.

Release this as v1.30.4 by changing `packageVersion`, `APP_VERSION`, and `version.json` together, including release notes for the playback-size change and the preceding audio-EOF fix.

## Verification

Generate real 1280×720 and small video fixtures with ffmpeg in tests. Assert that the player's actual ffmpeg output is 640×360 for the large fixture and retains original dimensions for the small fixture; read a complete BGRA frame to prove buffer boundaries. Run focused and full app tests. Windows real-time throughput must still be checked on a Windows machine.
