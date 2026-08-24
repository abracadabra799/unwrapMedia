package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.Executors

// Dedicated thread pool for full-size frame decodes -- prevents blocking the UI or competing with
// thumbnail decoders, and reuses threads across requests.
private val fullSizeDecoderExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

// Fast accurate-seek single-frame extraction at native resolution with hardware acceleration.
// Uses fast seek (-ss placed BEFORE -i) to immediately jump to the target timestamp without
// sequentially decoding all preceding frames, combined with JPEG (-q:v 2) for rapid Skia rendering.
object FrameFullSizeDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit) {
        fullSizeDecoderExecutor.submit {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                listOf(
                    FfmpegLocator.ffmpegPath(), "-y",
                    "-hwaccel", "auto",
                    "-ss", ptsSeconds.toString(),
                    "-i", file.absolutePath,
                    "-frames:v", "1",
                    "-q:v", "2",
                    "-update", "1",
                ),
                tempExtension = ".jpg",
            )
            EventQueue.invokeLater { onResult(result) }
        }
    }
}
