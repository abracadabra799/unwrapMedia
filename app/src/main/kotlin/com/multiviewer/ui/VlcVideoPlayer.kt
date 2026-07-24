package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File

@Composable
fun VlcVideoPlayer(file: File, modifier: Modifier = Modifier) {
    val osName = remember { System.getProperty("os.name") }
    val osArch = remember { System.getProperty("os.arch") }
    val javaVersion = remember { System.getProperty("java.version") }
    
    var initError by remember { mutableStateOf<String?>(null) }
    var playbackStatus by remember { mutableStateOf("Initializing...") }
    val coroutineScope = rememberCoroutineScope()
    
    val mediaPlayerComponent = remember {
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
            // Refined args for macOS embedding stability
            val vlcArgs = arrayOf(
                "--no-video-title-show",
                "--no-osd",
                "--no-snapshot-preview",
                "--quiet",
                "--vout=macosx",
                "--avcodec-hw=none" // Disable HW accel to rule out black screen
            )
            
            val component = object : EmbeddedMediaPlayerComponent(*vlcArgs) {
                override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                    println("VLC MediaPlayer Ready")
                }
            }
            
            component.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer?) {
                    playbackStatus = "Playing"
                }
                override fun paused(mediaPlayer: MediaPlayer?) {
                    playbackStatus = "Paused"
                }
                override fun error(mediaPlayer: MediaPlayer?) {
                    playbackStatus = "Error"
                    initError = "VLC Playback Error"
                }
                override fun videoOutput(mediaPlayer: MediaPlayer?, newCount: Int) {
                    println("VLC Video Output: $newCount")
                    if (newCount > 0) {
                        playbackStatus = "Video Active"
                        // Force UI refresh on the AWT thread
                        java.awt.EventQueue.invokeLater {
                            component.revalidate()
                            component.repaint()
                        }
                    }
                }
            })
            
            component
        } catch (e: Throwable) {
            initError = e.message ?: e.toString()
            null
        }
    }

    if (mediaPlayerComponent == null) {
        VlcErrorDisplay(osName, osArch, javaVersion, initError, modifier)
        return
    }

    LaunchedEffect(file) {
        coroutineScope.launch {
            var attempts = 0
            while (isActive && !mediaPlayerComponent.isDisplayable && attempts < 30) {
                delay(100)
                attempts++
            }
            
            if (mediaPlayerComponent.isDisplayable) {
                println("VLC Surface ready, playing: ${file.name}")
                mediaPlayerComponent.mediaPlayer().media().play(file.absolutePath)
                
                // Extra kick to ensure rendering starts
                delay(500)
                java.awt.EventQueue.invokeLater {
                    mediaPlayerComponent.repaint()
                }
            } else {
                initError = "Video surface timed out"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerComponent.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (mediaPlayerComponent.mediaPlayer().status().isPlaying) {
                    mediaPlayerComponent.mediaPlayer().controls().pause()
                } else {
                    mediaPlayerComponent.mediaPlayer().controls().play()
                }
            }
    ) {
        SwingPanel(
            factory = { mediaPlayerComponent },
            modifier = Modifier.fillMaxSize()
        )
        
        Text(
            text = playbackStatus,
            color = AppColors.NeonBlue.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
        )
    }
}

@Composable
private fun VlcErrorDisplay(os: String, arch: String, java: String, error: String?, modifier: Modifier) {
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
        
        val isArchMismatch = error?.contains("vlccore") == true || error?.contains("architecture") == true
        
        if (isArchMismatch) {
            Text("ARCHITECTURE MISMATCH DETECTED", color = AppColors.NeonYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your Java process is running as '$arch', but the installed VLC library is for a different architecture. " +
                "Try installing the native version for your system.",
                color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 13.sp
            )
        } else {
            Text(
                "Video playback requires VLC Media Player installed. " +
                "We attempted to use bundled libraries, but initialization failed.",
                color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(12.dp)) {
            DiagnosticLine("OS", os)
            DiagnosticLine("JVM Architecture", arch)
            DiagnosticLine("Java Version", java)
            error?.let { DiagnosticLine("Error Detail", it) }
        }
        Spacer(Modifier.height(24.dp))
        Text("Download VLC for ${if (arch.contains("aarch64")) "Apple Silicon" else "Intel"} from videolan.org", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", color = AppColors.TextSecondary, style = AppTypography.labelLarge, modifier = Modifier.width(120.dp))
        Text(value, color = AppColors.NeonBlue, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
