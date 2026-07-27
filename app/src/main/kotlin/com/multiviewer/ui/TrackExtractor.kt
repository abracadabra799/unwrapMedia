package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

// Extracts every video (or every audio) bitstream out of a video file via ffmpeg stream copy
// (-c copy, no re-encode) -- fast and lossless, since it's a remux rather than a transcode.
// -map 0:v / -map 0:a (no stream index) pulls in *all* streams of that type, not just the first,
// so a file with multiple audio tracks (e.g. different languages) gets all of them.
private const val EXTRACT_TIMEOUT_SECONDS = 300L

// Video track: repackaged into the source file's own container format (destination's extension
// is the caller's choice -- see extractVideoTrackToFile in Main.kt, which reuses tab.file's
// extension) since that's already a container ffmpeg can mux this codec into.
fun extractVideoTrack(source: File, destination: File): Boolean {
    val copied = runFfmpegExtract(
        FfmpegLocator.ffmpegPath(), "-y", "-i", source.absolutePath,
        "-map", "0:v", "-c", "copy", "-an", destination.absolutePath,
    )
    if (copied) return true
    // Stream copy can fail if the destination container (chosen to match the source file's own
    // extension) can't hold this particular video codec as-is -- re-encode to H.264, which every
    // standard player supports and every one of this app's supported video containers (mp4/mov/
    // m4v) can hold, so the result is still guaranteed playable.
    return runFfmpegExtract(
        FfmpegLocator.ffmpegPath(), "-y", "-i", source.absolutePath,
        "-map", "0:v", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-an", destination.absolutePath,
    )
}

// Audio track: always M4A -- an MP4-family container (same family this app's video formats
// already belong to) built specifically to hold AAC, the audio codec already in the overwhelming
// majority of consumer-recorded MP4/MOV files, and natively supported by every standard player.
fun extractAudioTrack(source: File, destination: File): Boolean {
    val copied = runFfmpegExtract(
        FfmpegLocator.ffmpegPath(), "-y", "-i", source.absolutePath,
        "-map", "0:a", "-c", "copy", "-vn", destination.absolutePath,
    )
    if (copied) return true
    // Stream copy fails when the source audio codec isn't valid inside an M4A container (e.g.
    // raw/uncompressed PCM) -- re-encode to AAC, M4A's native codec, so the result is still
    // guaranteed to play in any standard player regardless of the source codec.
    return runFfmpegExtract(
        FfmpegLocator.ffmpegPath(), "-y", "-i", source.absolutePath,
        "-map", "0:a", "-c:a", "aac", "-b:a", "192k", "-vn", destination.absolutePath,
    )
}

private fun runFfmpegExtract(vararg command: String): Boolean {
    return try {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val finished = process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (e: Exception) {
        false
    }
}
