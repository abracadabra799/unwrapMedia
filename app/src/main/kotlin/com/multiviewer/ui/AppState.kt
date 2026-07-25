package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.multiviewer.parser.*
import java.awt.EventQueue
import java.io.File

private const val MAX_OPEN_FILES = 2

enum class MediaType {
    IMAGE, VIDEO, UNKNOWN
}

data class HistogramData(
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
    val y: FloatArray
)

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

class TabState(val file: File) {
    var isLoading: Boolean by mutableStateOf(true)
    var type by mutableStateOf(MediaType.UNKNOWN)
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var imageForensic: ImageForensicData? by mutableStateOf(null)

    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)

    // GOP / frame-type analysis (see FrameTypeAnalyzer.kt) -- null gopFrames means "never asked";
    // an empty (non-null) list means "asked, ffprobe found nothing".
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
}

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)
    var statusMessage: String? by mutableStateOf(null)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            statusMessage = null
            return
        }
        if (tabs.size >= MAX_OPEN_FILES) {
            statusMessage = "You can only have $MAX_OPEN_FILES files open at a time."
            return
        }
        statusMessage = null
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1

        // Parsing + analysis do many small random-access file reads (a single video's bitrate
        // graph alone issues ~600 individual seek+read syscalls) — cheap on a fast local SSD but
        // slow enough on some setups (notably Windows, where each of those reads carries far more
        // per-syscall overhead) to freeze the whole UI if run inline. Run it on a background
        // thread and marshal results back via EventQueue.invokeLater, same pattern already used
        // for the ffmpeg HEIC fallback decode below.
        Thread {
            try {
                val root = parseFile(file)

                val type = when {
                    file.extension.lowercase() in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "avif", "heic") -> MediaType.IMAGE
                    file.extension.lowercase() in listOf("mp4", "mov", "m4v") -> MediaType.VIDEO
                    else -> MediaType.UNKNOWN
                }

                val mediaSummary = try {
                    buildMediaSummary(root, file)
                } catch (e: Exception) {
                    null
                }
                val embeddedVideo = try {
                    findEmbeddedVideo(root)
                } catch (e: Exception) {
                    null
                }
                val motionPhotoPreview = try {
                    findMotionPhotoPreview(root)
                } catch (e: Exception) {
                    null
                }

                var imageForensic: ImageForensicData? = null
                when (type) {
                    MediaType.IMAGE -> imageForensic = ImageAnalyzer.analyze(file, root)
                    MediaType.VIDEO -> {
                        // Attempt to extract thumbnail for video files too
                        imageForensic = ImageAnalyzer.analyze(file, root)
                    }
                    else -> {}
                }
                val finalImageForensic = imageForensic

                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = mediaSummary
                    tab.embeddedVideo = embeddedVideo
                    tab.motionPhotoPreview = motionPhotoPreview
                    tab.isLoading = false

                    if (type == MediaType.IMAGE && finalImageForensic != null && finalImageForensic.bitmap == null) {
                        // Skia has no HEIF/HEVC decoder — fall back to ffmpeg, async so the UI never blocks.
                        tab.imageForensic = finalImageForensic.copy(isDecodingFallback = true)
                        FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
                            val current = tab.imageForensic ?: finalImageForensic
                            tab.imageForensic = current.copy(
                                bitmap = bitmap,
                                embeddedThumbnail = current.embeddedThumbnail
                                    ?: (bitmap.takeIf { current.hasThumbnailReference }),
                                isDecodingFallback = false,
                            )
                        }
                    } else {
                        tab.imageForensic = finalImageForensic
                    }
                }
            } catch (e: Exception) {
                EventQueue.invokeLater {
                    tab.error = e.message ?: "Failed to open file"
                    tab.isLoading = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun closeTab(index: Int) {
        statusMessage = null
        tabs.removeAt(index)
        selectedTabIndex = when {
            tabs.isEmpty() -> 0
            index < selectedTabIndex -> selectedTabIndex - 1
            index == selectedTabIndex -> index.coerceAtMost(tabs.size - 1)
            else -> selectedTabIndex
        }
    }

    fun analyzeFrames(tab: TabState) {
        if (tab.isAnalyzingFrames || tab.gopFrames != null) return
        tab.isAnalyzingFrames = true
        Thread {
            val frames = probeFrameTypes(tab.file)
            EventQueue.invokeLater {
                tab.gopFrames = frames ?: emptyList()
                tab.isAnalyzingFrames = false
            }
        }.apply { isDaemon = true }.start()
    }
}
