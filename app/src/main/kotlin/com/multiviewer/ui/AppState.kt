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

private val IMAGE_EXTENSIONS = listOf(
    "jpg", "jpeg", "png", "bmp", "gif", "webp", "avif", "heic",
    // Camera RAW formats -- all TIFF/EP-based, so the existing generic TIFF/IFD walker
    // (decodeTiff, already reached via parseFile's magic-byte detection) parses their structure
    // without a dedicated decoder. No full RAW/demosaic decode: ImageAnalyzer falls back to
    // whatever embedded JPEG preview it can find (same as HEIC), since Skia/ffmpeg can't decode
    // raw sensor data either.
    "cr2", "nef", "arw", "dng",
)
private val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v")
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3 and WAV have their own dedicated
// parsers (Mp3Walker/WavWalker). FLAC and OGG use genuinely different container structures and
// still need their own parsers -- not included yet.
private val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav")

// Resolution guidance shared by the normal open flow (AppState.openFile) and the raw pixel dialog
// (RawPixelOpenDialog) -- see README's "Supported Specs & Limits" section. Below WARN: no notice.
// Between WARN and HARD_LIMIT: non-blocking warning banner. At or above HARD_LIMIT: refused
// outright, since a decode at that size risks exhausting the JVM's default heap (no -Xmx is set)
// and hanging or crashing the app instead of failing cleanly.
private const val WARN_PIXELS_STATIC = 7680L * 4320L // 8K, single image / raw pixel frame
private const val WARN_PIXELS_VIDEO = 3840L * 2160L // 4K, continuous video / raw pixel stream playback
private const val HARD_LIMIT_PIXELS = 16384L * 16384L // ~268MP, i.e. 1GB for a 4-byte-per-pixel decode buffer

fun resolutionWarningMessage(width: Int, height: Int, continuousPlayback: Boolean): String? {
    val pixels = width.toLong() * height.toLong()
    val threshold = if (continuousPlayback) WARN_PIXELS_VIDEO else WARN_PIXELS_STATIC
    if (pixels <= threshold) return null
    val thresholdLabel = if (continuousPlayback) "4K" else "8K"
    return "해상도가 큽니다 (${width}x$height, $thresholdLabel 이상) -- 메모리 사용량이 늘고 " +
        (if (continuousPlayback) "재생이 느려질 수 있습니다." else "디코딩이 느려질 수 있습니다.")
}

fun hardResolutionRejectionMessage(width: Int, height: Int): String? {
    val pixels = width.toLong() * height.toLong()
    if (pixels < HARD_LIMIT_PIXELS) return null
    val limitMp = HARD_LIMIT_PIXELS / 1_000_000
    return "해상도가 지원 한도를 초과합니다 (${width}x$height). 최대 약 ${limitMp}MP까지 지원되며, " +
        "그 이상은 메모리 부족으로 앱이 멈추거나 강제 종료될 수 있어 파일 열기를 차단했습니다."
}

private fun extractResolution(summary: MediaSummary?): Pair<Int, Int>? {
    val fields = summary?.sections?.flatMap { it.fields } ?: return null
    val width = fields.find { it.label == "Width" }?.value?.toIntOrNull() ?: return null
    val height = fields.find { it.label == "Height" }?.value?.toIntOrNull() ?: return null
    return width to height
}

enum class MediaType {
    IMAGE, VIDEO, AUDIO, RAW_PIXEL, UNKNOWN
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
    // Raw dumps carry no frame-rate metadata either -- user-supplied like everything else, and
    // only meaningful (asked for in the dialog) once frameCount > 1 makes this a video stream
    // rather than a single image.
    val fps: Double,
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
    var rawPixelIsPlaying: Boolean by mutableStateOf(false)
    // Live playback rate, seeded from RawPixelParams.fps but independently adjustable from
    // RawPixelInspectorUI without reopening the file -- there's no way to recover a raw dump's
    // true capture rate from the bytes alone, so the dialog's up-front value is only ever a
    // starting guess.
    var rawPixelFps: Double by mutableStateOf(30.0)

    // Non-blocking notice shown when an opened image/video's resolution is above the soft warning
    // threshold but still under the hard limit (see AppState.openFile) -- null means no warning.
    var largeResolutionWarning: String? by mutableStateOf(null)
}

private val RAW_PIXEL_EXTENSIONS = listOf("raw", "rgb", "rgba", "yuv")

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)
    var statusMessage: String? by mutableStateOf(null)

    // Set when openFile() refuses a file outright (unsupported extension, or a declared
    // resolution above HARD_LIMIT_PIXELS) -- shown as a blocking popup in Main.kt so a bad file
    // can't silently leave the app in a half-open state.
    var openFileError: String? by mutableStateOf(null)

    // Headerless raw pixel dumps carry no width/height/format of their own -- openFile() routes
    // them here instead of the normal parse flow, and RawPixelOpenDialog (shown while this is
    // non-null) collects the parameters needed to actually decode the file.
    var pendingRawPixelFile: File? by mutableStateOf(null)

    fun cancelRawPixelFile() {
        pendingRawPixelFile = null
    }

    fun confirmRawPixelFile(width: Int, height: Int, format: RawPixelFormat, byteOrder: RawPixelByteOrder, fps: Double) {
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
        runInBackground {
            val frameCount = rawPixelFrameCount(file.length(), width, height, format)
            val bitmap = if (frameCount > 0) decodeRawPixelFile(file, width, height, format, byteOrder, frameIndex = 0) else null
            EventQueue.invokeLater {
                tab.type = MediaType.RAW_PIXEL
                tab.rawPixelParams = RawPixelParams(width, height, format, byteOrder, frameCount, fps)
                tab.rawPixelFrameIndex = 0
                tab.rawPixelFps = fps
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
                            ) + (if (frameCount > 1) listOf(BoxField("Frame Rate", "$fps fps", 0, file.length())) else emptyList()),
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
        }
    }

    // Re-decodes a different frame of the currently-open raw pixel sequence. Runs on a background
    // thread like the initial decode -- for the ffmpeg-backed YUV formats especially, decoding is
    // a real subprocess call, not a cheap operation to do on the UI thread.
    fun seekRawPixelFrame(tab: TabState, frameIndex: Int) {
        val params = tab.rawPixelParams ?: return
        val clamped = frameIndex.coerceIn(0, (params.frameCount - 1).coerceAtLeast(0))
        if (clamped == tab.rawPixelFrameIndex) return
        tab.rawPixelFrameIndex = clamped
        runInBackground {
            val bitmap = decodeRawPixelFile(tab.file, params.width, params.height, params.format, params.byteOrder, clamped)
            EventQueue.invokeLater {
                if (tab.rawPixelFrameIndex == clamped) {
                    tab.imageForensic = (tab.imageForensic ?: ImageForensicData()).copy(bitmap = bitmap)
                }
            }
        }
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
        val extension = file.extension.lowercase()
        if (extension in RAW_PIXEL_EXTENSIONS) {
            statusMessage = null
            pendingRawPixelFile = file
            return
        }
        // Reject anything else outright rather than falling through to parseFile(): its magic-byte
        // dispatch (ParseFile.kt) has no "unrecognized" case for the box-parser branch, so an
        // arbitrary non-media file would previously be handed to the MP4/MOV box walker and parsed
        // as if it might be one -- undefined behavior on genuinely arbitrary bytes, not something
        // this app can promise won't hang or crash. See README's "Supported Specs & Limits".
        if (extension !in IMAGE_EXTENSIONS && extension !in VIDEO_EXTENSIONS && extension !in AUDIO_EXTENSIONS) {
            val supported = (IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + RAW_PIXEL_EXTENSIONS).joinToString(", ")
            openFileError = "지원하지 않는 파일 형식입니다 (.$extension).\n지원 형식: $supported"
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
                    extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
                    extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
                    extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
                    else -> MediaType.UNKNOWN
                }

                val mediaSummary = try {
                    buildMediaSummary(root, file)
                } catch (e: Exception) {
                    null
                }

                // Resolution is read from the already-parsed header fields (cheap, from
                // buildMediaSummary's own box walking -- not from probeStreamDetails below) so
                // this gate runs before either heavy step: probeStreamDetails spawns a second
                // ffprobe process, and ImageAnalyzer.analyze does a full Image.makeFromEncoded
                // raster decode of the whole file into memory. An oversized file gets rejected
                // without ever attempting either.
                val resolution = extractResolution(mediaSummary)
                val hardBlockMessage = resolution?.let { (w, h) -> hardResolutionRejectionMessage(w, h) }

                if (hardBlockMessage != null) {
                    EventQueue.invokeLater {
                        val idx = tabs.indexOf(tab)
                        if (idx >= 0) tabs.removeAt(idx)
                        if (selectedTabIndex >= tabs.size) selectedTabIndex = (tabs.size - 1).coerceAtLeast(0)
                        openFileError = hardBlockMessage
                    }
                    return@Thread
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
                val warning = resolution?.let { (w, h) -> resolutionWarningMessage(w, h, type == MediaType.VIDEO) }

                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = mediaSummary
                    tab.embeddedVideo = embeddedVideo
                    tab.motionPhotoPreview = motionPhotoPreview
                    tab.largeResolutionWarning = warning
                    tab.isLoading = false

                    if ((type == MediaType.VIDEO || type == MediaType.AUDIO) && mediaSummary != null) {
                        // Per-stream codec details (profile, level, chroma subsampling, bit rate,
                        // etc.) come from a second, separate ffprobe process call -- deferred here
                        // so opening a video/audio file doesn't wait on spawning it. The tab is
                        // already interactive (structure tree, hex view, general summary) with
                        // this filling in a moment later, same as the image decode below.
                        runInBackground {
                            val details = probeStreamDetails(file)
                            if (details != null) {
                                val enriched = mergeStreamCodecDetails(mediaSummary, details.videoFields, details.audioFields)
                                EventQueue.invokeLater { tab.mediaSummary = enriched }
                            }
                        }
                    }

                    if (type == MediaType.IMAGE && finalImageForensic != null) {
                        // The primary pixel decode (a real Skia raster decode + histogram pass,
                        // ~45ms+ measured for a large JPEG) runs here instead of inline in
                        // ImageAnalyzer.analyze() above, so the tab is already interactive
                        // (structure tree, hex view, metadata) the moment the cheap parse finishes
                        // -- the preview/histogram fill in a moment later instead of gating "the
                        // file is open" on them.
                        tab.imageForensic = finalImageForensic.copy(isDecodingFallback = true)
                        runInBackground {
                            val (bitmap, histogram) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)
                            if (bitmap != null) {
                                EventQueue.invokeLater {
                                    val current = tab.imageForensic ?: finalImageForensic
                                    tab.imageForensic = current.copy(bitmap = bitmap, histogram = histogram, isDecodingFallback = false)
                                }
                            } else {
                                // Skia has no HEIF/HEVC decoder — fall back to ffmpeg (already async
                                // and posts back via EventQueue.invokeLater itself).
                                FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { fallbackBitmap ->
                                    val current = tab.imageForensic ?: finalImageForensic
                                    tab.imageForensic = current.copy(
                                        bitmap = fallbackBitmap,
                                        embeddedThumbnail = current.embeddedThumbnail
                                            ?: (fallbackBitmap.takeIf { current.hasThumbnailReference }),
                                        isDecodingFallback = false,
                                    )
                                }
                            }
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
        runInBackground {
            val frames = probeFrameTypes(tab.file)
            EventQueue.invokeLater {
                tab.gopFrames = frames ?: emptyList()
                tab.isAnalyzingFrames = false
            }
        }
    }

    fun analyzeMotionPhotoCodecDetails(tab: TabState) {
        val video = tab.embeddedVideo ?: return
        if (tab.isAnalyzingMotionPhotoCodec || tab.motionPhotoCodecDetailsLoaded) return
        tab.isAnalyzingMotionPhotoCodec = true
        runInBackground {
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
        }
    }
}
