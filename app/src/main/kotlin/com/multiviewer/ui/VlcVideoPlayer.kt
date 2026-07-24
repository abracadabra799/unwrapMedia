package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.*
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

@Composable
fun VlcVideoPlayer(file: File, modifier: Modifier = Modifier) {
    // neverEqualPolicy ensures we recompose on EVERY frame update
    var videoBitmap by remember { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember { mutableStateOf(false) }
    
    val playerState = remember {
        NativeDiscovery().discover()
        try {
            val vlcArgs = arrayOf(
                "--no-video-title-show",
                "--no-osd",
                "--quiet",
                "--avcodec-hw=none",
                "--no-videotoolbox", // VideoToolbox's CVPX output can't be rotated by the transform
                                     // filter (needed for portrait phone videos), causing an
                                     // endless vout-creation retry loop; force software decode.
                "--no-audio" // Mute for inspector usage
            )
            val factory = MediaPlayerFactory(*vlcArgs)
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
                    println("VLC Engine: Format Negotiated ${sourceWidth}x${sourceHeight}")
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

                        // We must update the state on the AWT thread, but the copy should be fast
                        java.awt.EventQueue.invokeLater {
                            try {
                                skiaBitmap.installPixels(skiaBitmap.imageInfo, currentBuffer, w * 4)
                                // Snapshot for safe thread-handover
                                val snapshot = Image.makeFromBitmap(skiaBitmap)
                                videoBitmap = snapshot.toComposeImageBitmap()
                            } catch (e: Exception) {}
                        }
                    }
                }
                override fun unlock(mediaPlayer: MediaPlayer?) {}
            }

            mediaPlayer.videoSurface().set(CallbackVideoSurface(bufferFormatCallback, renderCallback, true, object : VideoSurfaceAdapter {
                override fun attach(mediaPlayer: MediaPlayer?, videoSurfaceHandle: Long) {}
            }))
            
            var hasAutoPaused = false
            mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer?) {
                    isPlaying = true
                    // Auto-pause once real playback has actually started, so at least one frame
                    // reaches the render callback first. Pausing synchronously right after play()
                    // (the old approach) raced VLC's own startup and could pause before any frame
                    // was ever decoded, leaving the panel permanently black.
                    if (!hasAutoPaused) {
                        hasAutoPaused = true
                        mediaPlayer?.controls()?.pause()
                    }
                }
                override fun paused(mediaPlayer: MediaPlayer?) { isPlaying = false }
                override fun stopped(mediaPlayer: MediaPlayer?) { isPlaying = false }
            })
            
            Pair(factory, mediaPlayer)
        } catch (e: Throwable) {
            println("VLC Engine: Initialization Failed: ${e.message}")
            null
        }
    }

    if (playerState == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("VLC Initialization Failed", color = Color.White)
        }
        return
    }

    val (factory, mediaPlayer) = playerState

    DisposableEffect(file) {
        println("VLC Engine: Loading ${file.name}")
        mediaPlayer.media().play(file.absolutePath)

        onDispose {
            println("VLC Engine: Releasing resources")
            mediaPlayer.release()
            factory.release()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val currentFrame = videoBitmap
        if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Decoding stream...", color = Color.Gray)
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 10.sp)
            }
        }
        
        // Play Button Overlay
        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { mediaPlayer.controls().play() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().clickable { mediaPlayer.controls().pause() })
        }
    }
}
