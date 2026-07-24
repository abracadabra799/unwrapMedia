package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

@Composable
fun VlcVideoPlayer(file: File, modifier: Modifier = Modifier) {
    val osName = remember { System.getProperty("os.name") }
    val osArch = remember { System.getProperty("os.arch") }
    
    var initError by remember { mutableStateOf<String?>(null) }
    var playbackStatus by remember { mutableStateOf("Initializing...") }
    var videoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    val playerState = remember {
        if (osName.contains("Mac", ignoreCase = true)) {
            val vlcPluginsPath = "/Applications/VLC.app/Contents/MacOS/plugins"
            if (File(vlcPluginsPath).exists()) {
                System.setProperty("VLC_PLUGIN_PATH", vlcPluginsPath)
            }
            val vlcLibPath = "/Applications/VLC.app/Contents/MacOS/lib"
            if (File(vlcLibPath).exists()) {
                System.setProperty("jna.library.path", vlcLibPath)
            }
        }

        NativeDiscovery().discover()
        
        try {
            val vlcArgs = arrayOf(
                "--no-video-title-show",
                "--no-osd",
                "--quiet",
                "--avcodec-hw=none"
            )
            
            val factory = MediaPlayerFactory(*vlcArgs)
            val mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
            
            var skiaBitmap = Bitmap()
            var videoBuffer = IntArray(0)
            var w = 0
            var h = 0

            val bufferFormatCallback = object : BufferFormatCallbackAdapter() {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    w = sourceWidth
                    h = sourceHeight
                    videoBuffer = IntArray(w * h)
                    skiaBitmap = Bitmap().apply {
                        allocPixels(ImageInfo.makeN32Premul(w, h))
                    }
                    return RV32BufferFormat(w, h)
                }
            }

            val renderCallback = object : RenderCallbackAdapter(videoBuffer) {
                override fun onDisplay(mediaPlayer: MediaPlayer?, nativeBuffer: IntArray?) {
                    if (nativeBuffer == null || w <= 0 || h <= 0) return
                    
                    val pixelCount = w * h
                    val bytes = ByteArray(pixelCount * 4)
                    for (i in 0 until pixelCount) {
                        val argb = nativeBuffer[i]
                        bytes[i * 4] = (argb and 0xFF).toByte()
                        bytes[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()
                        bytes[i * 4 + 2] = ((argb shr 16) and 0xFF).toByte()
                        bytes[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()
                    }
                    
                    skiaBitmap.installPixels(skiaBitmap.imageInfo, bytes, w * 4)
                    videoBitmap = skiaBitmap.asComposeImageBitmap()
                }
            }

            val videoSurface = CallbackVideoSurface(bufferFormatCallback, renderCallback, true, object : VideoSurfaceAdapter {
                override fun attach(mediaPlayer: MediaPlayer?, videoSurfaceHandle: Long) {}
            })
            mediaPlayer.videoSurface().set(videoSurface)
            
            mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer?) {
                    playbackStatus = "Playing"
                }
                override fun error(mediaPlayer: MediaPlayer?) {
                    playbackStatus = "Error"
                }
            })
            
            Pair(factory, mediaPlayer)
        } catch (e: Throwable) {
            initError = e.message ?: e.toString()
            null
        }
    }

    if (playerState == null) {
        VlcErrorDisplay(osName, osArch, initError, modifier)
        return
    }

    val (factory, mediaPlayer) = playerState

    DisposableEffect(file) {
        mediaPlayer.media().play(file.absolutePath)
        onDispose {
            mediaPlayer.release()
            factory.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (mediaPlayer.status().isPlaying) {
                    mediaPlayer.controls().pause()
                } else {
                    mediaPlayer.controls().play()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = videoBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Video Frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("Connecting to stream...", color = Color.Gray)
        }
        
        Text(
            text = playbackStatus,
            color = AppColors.NeonBlue.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
        )
    }
}

@Composable
private fun VlcErrorDisplay(os: String, arch: String, error: String?, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⚠️ VLC Player Initialization Failed", color = AppColors.NeonRed, style = AppTypography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Video playback requires VLC Media Player installed.", color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(12.dp)) {
            Text("OS: $os", color = Color.Gray)
            Text("JVM Arch: $arch", color = Color.Gray)
            error?.let { Text("Error: $it", color = Color.Gray) }
        }
    }
}
