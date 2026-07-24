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

            // Everything from here through attaching the video surface can throw (native player
            // creation, callback wiring, surface attach). Guard the whole chain so a failure here
            // still delivers onResult exactly once and releases whatever was actually constructed
            // (mediaPlayer if created, plus factory) instead of leaking them and hanging the caller.
            var mediaPlayer: MediaPlayer? = null
            try {
                val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
                mediaPlayer = player

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
                        // A result may already have been delivered (winning frame or timeout) while
                        // teardown is still in flight on the background thread. Stop touching the
                        // shared pixel buffer/bitmap immediately in that case to avoid corrupting the
                        // Bitmap already handed back to the caller inside the delivered snapshot.
                        if (delivered.get()) return
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

                player.videoSurface().set(CallbackVideoSurface(bufferFormatCallback, renderCallback, true, object : VideoSurfaceAdapter {
                    override fun attach(mediaPlayer: MediaPlayer?, videoSurfaceHandle: Long) {}
                }))
            } catch (e: Throwable) {
                deliver(null)
                try { mediaPlayer?.release() } catch (_: Throwable) {}
                try { factory.release() } catch (_: Throwable) {}
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }

            // mediaPlayer is guaranteed non-null here: the catch above returns before this point
            // whenever construction failed.
            val player = mediaPlayer!!

            try {
                player.media().play(file.absolutePath)
            } catch (e: Throwable) {
                deliver(null)
            }

            // Single teardown point: blocks this background thread (never the caller) until the
            // render callback delivers a frame or the timeout elapses, then releases VLC resources
            // exactly once before posting the final result.
            resultLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            deliver(null) // no-op via compareAndSet if display() already delivered a result
            player.controls().stop()
            player.release()
            factory.release()
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
