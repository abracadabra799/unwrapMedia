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
    IMAGE, VIDEO, RAW_PIXEL, UNKNOWN
}

// Params used to decode the currently-open headerless raw pixel dump, kept around (rather than
// discarded after the initial decode) so RawPixelInspectorUI can re-decode a different frame on
// seek. frameCount > 1 means the file holds a sequence, not a single image.
data class RawPixelParams(
    val width: Int,
    val height: Int,
    val format: RawPixelFormat,
    val byteOrder: RawPixelByteOrder,
    val frameCount: Int,
)

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
    val orientation: String? = null,
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

    // Live playback position (seconds) reported by FfmpegVideoPlayer, so the GOP graph can
    // highlight and auto-scroll to the frame currently on screen while the video plays.
    var playbackElapsedSeconds: Double by mutableStateOf(0.0)

    // Clicking a GOP frame requests FfmpegVideoPlayer seek to that timestamp. seekRequestTick is
    // bumped on every request (even to the same timestamp twice) since seekTargetSeconds alone
    // wouldn't change identity for a repeat click on the same frame.
    var seekTargetSeconds: Double by mutableStateOf(0.0)
    var seekRequestTick: Int by mutableStateOf(0)

    // Motion Photo Video codec-detail enrichment (see StreamCodecDetails.kt) -- button-triggered
    // since, unlike the main video, this requires extracting the embedded video to a temp file
    // before ffprobe can see it. motionPhotoVideoSections is already non-null before this runs
    // (built by buildMediaSummary), so unlike gopFrames its nullability can't signal "not yet
    // asked" -- a separate flag is needed.
    var isAnalyzingMotionPhotoCodec: Boolean by mutableStateOf(false)
    var motionPhotoCodecDetailsLoaded: Boolean by mutableStateOf(false)

    // Headerless raw pixel dump state (see RawPixelInspectorUI) -- null unless type == RAW_PIXEL.
    var rawPixelParams: RawPixelParams? by mutableStateOf(null)
    var rawPixelFrameIndex: Int by mutableStateOf(0)
}

private val RAW_PIXEL_EXTENSIONS = listOf("raw", "rgb", "rgba", "yuv")

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)
    var statusMessage: String? by mutableStateOf(null)

    // Headerless raw pixel dumps carry no width/height/format of their own -- openFile() routes
    // them here instead of the normal parse flow, and RawPixelOpenDialog (shown while this is
    // non-null) collects the parameters needed to actually decode the file.
    var pendingRawPixelFile: File? by mutableStateOf(null)

    fun cancelRawPixelFile() {
        pendingRawPixelFile = null
    }

    fun confirmRawPixelFile(width: Int, height: Int, format: RawPixelFormat, byteOrder: RawPixelByteOrder) {
        val file = pendingRawPixelFile ?: return
        pendingRawPixelFile = null
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
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
        Thread {
            val frameCount = rawPixelFrameCount(file.length(), width, height, format)
            val bitmap = if (frameCount > 0) decodeRawPixelFile(file, width, height, format, byteOrder, frameIndex = 0) else null
            EventQueue.invokeLater {
                tab.type = MediaType.RAW_PIXEL
                tab.rawPixelParams = RawPixelParams(width, height, format, byteOrder, frameCount)
                tab.rawPixelFrameIndex = 0
                tab.root = BoxNode(
                    type = "root", offset = 0, headerSize = 0, size = file.length(),
                    children = listOf(
                        BoxNode(
                            type = "RawPixelData", offset = 0, headerSize = 0, size = file.length(),
                            fields = listOf(
                                BoxField("Format", format.label, 0, file.length()),
                                BoxField("Width", width.toString(), 0, file.length()),
                                BoxField("Height", height.toString(), 0, file.length()),
                                BoxField("Frame Count", frameCount.toString(), 0, file.length()),
                            ),
                            summary = "${width}x$height, ${format.label}, $frameCount frame(s)",
                        ),
                    ),
                )
                tab.imageForensic = ImageForensicData(bitmap = bitmap)
                tab.isLoading = false
                if (bitmap == null) {
                    tab.error = "지정한 해상도/포맷으로 픽셀 데이터를 해석할 수 없습니다 (파일 크기가 너무 작음)."
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // Re-decodes a different frame of the currently-open raw pixel sequence. Runs on a background
    // thread like the initial decode -- for the ffmpeg-backed YUV formats especially, decoding is
    // a real subprocess call, not a cheap operation to do on the UI thread.
    fun seekRawPixelFrame(tab: TabState, frameIndex: Int) {
        val params = tab.rawPixelParams ?: return
        val clamped = frameIndex.coerceIn(0, (params.frameCount - 1).coerceAtLeast(0))
        if (clamped == tab.rawPixelFrameIndex) return
        tab.rawPixelFrameIndex = clamped
        Thread {
            val bitmap = decodeRawPixelFile(tab.file, params.width, params.height, params.format, params.byteOrder, clamped)
            EventQueue.invokeLater {
                if (tab.rawPixelFrameIndex == clamped) {
                    tab.imageForensic = (tab.imageForensic ?: ImageForensicData()).copy(bitmap = bitmap)
                }
            }
        }.apply { isDaemon = true }.start()
    }

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
        if (file.extension.lowercase() in RAW_PIXEL_EXTENSIONS) {
            statusMessage = null
            pendingRawPixelFile = file
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
                    file.extension.lowercase() in listOf(
                        "jpg", "jpeg", "png", "bmp", "gif", "webp", "avif", "heic",
                        // Camera RAW formats -- all TIFF/EP-based, so the existing generic TIFF/IFD
                        // walker (decodeTiff, already reached via parseFile's magic-byte detection)
                        // parses their structure without a dedicated decoder. No full RAW/demosaic
                        // decode: ImageAnalyzer falls back to whatever embedded JPEG preview it can
                        // find (same as HEIC), since Skia/ffmpeg can't decode raw sensor data either.
                        "cr2", "nef", "arw", "dng",
                    ) -> MediaType.IMAGE
                    file.extension.lowercase() in listOf("mp4", "mov", "m4v") -> MediaType.VIDEO
                    else -> MediaType.UNKNOWN
                }

                val mediaSummary = try {
                    buildMediaSummary(root, file)
                } catch (e: Exception) {
                    null
                }
                // ffprobe -show_entries stream=... is one fast call whose cost doesn't scale with
                // video length (unlike GOP frame analysis), so it's safe to run automatically here
                // on the same background thread, same as probeVideo already does elsewhere.
                val enrichedMediaSummary = if (type == MediaType.VIDEO && mediaSummary != null) {
                    val details = probeStreamDetails(file)
                    if (details != null) {
                        mergeStreamCodecDetails(mediaSummary, details.videoFields, details.audioFields)
                    } else {
                        mediaSummary
                    }
                } else {
                    mediaSummary
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
                    tab.mediaSummary = enrichedMediaSummary
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

    fun analyzeMotionPhotoCodecDetails(tab: TabState) {
        val video = tab.embeddedVideo ?: return
        if (tab.isAnalyzingMotionPhotoCodec || tab.motionPhotoCodecDetailsLoaded) return
        tab.isAnalyzingMotionPhotoCodec = true
        Thread {
            val temp = try {
                val dest = File.createTempFile("motion-photo-codec-probe-", ".${video.extension}")
                dest.deleteOnExit()
                extractEmbeddedVideo(tab.file, video, dest)
                dest
            } catch (e: Exception) {
                null
            }
            val details = temp?.let { probeStreamDetails(it) }
            temp?.delete()
            EventQueue.invokeLater {
                val summary = tab.mediaSummary
                if (details != null && summary != null) {
                    val currentSections = summary.motionPhotoVideoSections ?: emptyList()
                    val merged = mergeStreamCodecDetailsIntoSections(currentSections, details.videoFields, details.audioFields)
                    tab.mediaSummary = summary.copy(motionPhotoVideoSections = merged)
                }
                tab.motionPhotoCodecDetailsLoaded = true
                tab.isAnalyzingMotionPhotoCodec = false
            }
        }.apply { isDaemon = true }.start()
    }
}
