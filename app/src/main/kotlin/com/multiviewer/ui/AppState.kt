package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.multiviewer.parser.*
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

data class BitratePoint(val timestampSeconds: Double, val kbps: Double)

data class VideoAnalysisData(
    val bitratePoints: List<BitratePoint> = emptyList(),
    val boxWeights: Map<String, Long> = emptyMap()
)

class TabState(val file: File) {
    var type by mutableStateOf(MediaType.UNKNOWN)
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var imageForensic: ImageForensicData? by mutableStateOf(null)
    var videoAnalysis: VideoAnalysisData? by mutableStateOf(null)
    
    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)
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
        try {
            val root = parseFile(file)
            tab.root = root
            
            // Detect Type
            tab.type = when {
                file.extension.lowercase() in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "avif", "heic") -> MediaType.IMAGE
                file.extension.lowercase() in listOf("mp4", "mov", "m4v") -> MediaType.VIDEO
                else -> MediaType.UNKNOWN
            }

            tab.mediaSummary = try {
                buildMediaSummary(root, file)
            } catch (e: Exception) {
                null
            }
            tab.embeddedVideo = try {
                findEmbeddedVideo(root)
            } catch (e: Exception) {
                null
            }
            tab.motionPhotoPreview = try {
                findMotionPhotoPreview(root)
            } catch (e: Exception) {
                null
            }
            
            // Trigger analysis based on type
            when (tab.type) {
                MediaType.IMAGE -> tab.imageForensic = ImageAnalyzer.analyze(file, root)
                MediaType.VIDEO -> {
                    tab.videoAnalysis = VideoAnalyzer.analyze(file, root)
                    // Attempt to extract thumbnail for video files too
                    tab.imageForensic = ImageAnalyzer.analyze(file, root)
                }
                else -> {}
            }
            
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
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
}
