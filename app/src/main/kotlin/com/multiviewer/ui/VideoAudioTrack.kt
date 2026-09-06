package com.multiviewer.ui

// Pure pacing decision for the video reader loop when audio is the master clock. Kept free of
// I/O / Thread / Compose so it is directly unit-testable, same convention as shouldSkipFrame in
// FfmpegVideoPlayer.kt. frameStartSeconds and audioClockSeconds are both measured relative to the
// same ffmpeg -ss seek point, so their difference is the video frame's lead (+) or lag (-) versus
// the audio actually rendered by the mixer.
sealed interface FrameAction {
    data object Deliver : FrameAction
    data object Drop : FrameAction
    data class WaitThenDeliver(val millis: Long) : FrameAction
}

fun frameSyncAction(frameStartSeconds: Double, audioClockSeconds: Double): FrameAction {
    val leadSeconds = frameStartSeconds - audioClockSeconds
    return when {
        // Frame is due later than the audio has reached -- hold it. Cap the hold so a bogus clock
        // reading (e.g. line not yet started, position still 0) can't freeze the reader for
        // seconds; 500ms is well past any real single-frame interval.
        leadSeconds > 0.0 -> FrameAction.WaitThenDeliver((leadSeconds * 1000).toLong().coerceAtMost(500))
        // More than 100ms behind audio -- rendering this frame would only widen the gap; skip its
        // (expensive) bitmap construction. Its bytes were already read off the pipe by the caller.
        leadSeconds < -0.10 -> FrameAction.Drop
        else -> FrameAction.Deliver
    }
}
