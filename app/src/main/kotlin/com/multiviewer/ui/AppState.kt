package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.multiviewer.parser.*
import java.awt.EventQueue
import java.io.File

private const val MAX_OPEN_FILES = 20

val IMAGE_EXTENSIONS = listOf(
    "jpg", "jpeg", "png", "bmp", "gif", "webp", "avif", "heic",
    // Plain TIFF -- parseFile's magic-byte dispatch (isTiffMagic) and decodeTiff already handle
    // this; it was only ever reachable via the camera RAW extensions below, so a standalone
    // .tif/.tiff file was rejected by this extension gate before parsing was ever attempted.
    "tif", "tiff",
    // Camera RAW formats -- all TIFF/EP-based, so the existing generic TIFF/IFD walker
    // (decodeTiff, already reached via parseFile's magic-byte detection) parses their structure
    // without a dedicated decoder. No full RAW/demosaic decode: ImageAnalyzer falls back to
    // whatever embedded JPEG preview it can find (same as HEIC), since Skia/ffmpeg can't decode
    // raw sensor data either.
    "cr2", "nef", "arw", "dng",
)
val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v", "webm", "apv", "av1", "ivf")
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3, WAV, FLAC, OGG, and AIFF each have
// their own dedicated parsers (Mp3Walker/WavWalker/FlacWalker/OggWalker/AiffWalker). "opus" files
// are themselves Ogg containers (same "OggS" magic and page format), just carrying an Opus stream
// instead of Vorbis, so they route through the same OggWalker with no separate parser needed.
// "aif"/"aifc" are alternate extensions for the same AIFF/AIFF-C container format as "aiff".
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav", "flac", "ogg", "opus", "aiff", "aif", "aifc")

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
    val orientationCode: Int? = null,
    val thumbnailOrientation: String? = null,
    val thumbnailOrientationCode: Int? = null,
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
    // Set once (per file, in ImageInspectorUI's LaunchedEffect below) when the open HEIC/HEIF has a
    // grid-tiled structure -- null for every other file, which is what gates PixelInspectorPreview's
    // new tile overlay off entirely for the overwhelming majority of images.
    var tileGrid: com.multiviewer.parser.TileGridInfo? by mutableStateOf(null)
    // Set (in ImageInspectorUI's LaunchedEffect below) when the currently tree-selected node is one
    // of tileGrid's own tile items -- the tile's real pixel-data byte range (see findHeicTileGrid +
    // the iloc extent's own offset/length field values, not the small iloc table entry `selected`
    // alone would resolve to) and its row-major index into tileGrid.tileItemIds, for the Hex
    // viewer highlight and the single-tile overlay respectively. Both null whenever the selected
    // node isn't a tile item (including "nothing selected").
    var tileHighlightRange: LongRange? by mutableStateOf(null)
    var selectedTileIndex: Int? by mutableStateOf(null)
    var isPrimaryImagePopupOpen: Boolean by mutableStateOf(false)
    // Decoded GIF animation frames (see GifFrameDecoder.kt) -- null until the background decode
    // in openFile finishes (or forever, for non-GIF files, which never trigger it). A non-null
    // value with frames.size <= 1 means "decoded successfully but not actually animated" -- see
    // GifFilmstripPlayer, which falls back to a plain static view in that case.
    var gifAnimation: GifAnimationData? by mutableStateOf(null)
    var gifFrameIndex: Int by mutableStateOf(0)
    var gifIsPlaying: Boolean by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    // Drives field-level hex highlighting (Main.kt) -- deliberately NOT reset every time
    // `selected` changes; instead Main.kt checks membership (`selectedField in selected.fields`)
    // before using it, so a stale value from a previously-selected node is simply ignored rather
    // than needing to be cleared at every one of `selected`'s several assignment sites.
    var selectedField: BoxField? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)

    // GOP / frame-type analysis (see FrameTypeAnalyzer.kt) -- null gopFrames means "never asked";
    // an empty (non-null) list means "asked, ffprobe found nothing".
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var frameAnalysisLoadedCount: Int by mutableStateOf(0)
    var frameAnalysisProgress: Float? by mutableStateOf(null)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
    // Set once FfmpegVideoPlayer's own background frame-timestamp probe finishes (see its
    // onProbeComplete callback) -- gates the "프레임 분석 시작" button so it can't launch a second,
    // same-cost full-file ffprobe scan while that background one is still running.
    var videoReadyForAnalysis: Boolean by mutableStateOf(false)

    // Live playback position (seconds) reported by FfmpegVideoPlayer, so the GOP graph can
    // highlight and auto-scroll to the frame currently on screen while the video plays.
    var playbackElapsedSeconds: Double by mutableStateOf(0.0)

    // Clicking a GOP frame requests FfmpegVideoPlayer seek to that timestamp. seekRequestTick is
    // bumped on every request (even to the same timestamp twice) since seekTargetSeconds alone
    // wouldn't change identity for a repeat click on the same frame.
    var seekTargetSeconds: Double by mutableStateOf(0.0)
    var seekRequestTick: Int by mutableStateOf(0)

    // Codec-view preview (see CodecViewFrameDecoder.kt) -- a single selected GOP frame
    // re-extracted via ffmpeg's codecview filter, in one of two modes (motion vectors or QP
    // heatmap) baked onto its pixels. Which mode is active is app-level menu state (Main.kt's
    // "보기" menu), NOT per-tab, matching the existing "픽셀 그리드" menu toggle's own precedent --
    // only the decoded result (bitmap/loading flag) is naturally per-tab. Each mode is offered
    // independently per codecViewSupportedFor -- only H.264 today for both, verified separately
    // per mode since they use different ffmpeg mechanisms. null videoCodecName means "not probed
    // yet"; probing happens once per opened video tab.
    var videoCodecName: String? by mutableStateOf(null)

    // H.264 SPS/PPS (see H264ParameterSets.kt / H264ParameterSetExtraction.kt) -- parsed once per
    // video tab from the avcC box, independent of any specific frame selection. Empty lists (not
    // null) mean "not H.264, no avcC box, or nothing parsed successfully" -- the Detail Properties
    // panel shows nothing extra either way, so no separate "not yet probed" state is needed here
    // (unlike videoCodecName, nothing else needs to distinguish those cases).
    var avcSpsList: List<com.multiviewer.parser.H264Sps> by mutableStateOf(emptyList())
    var avcPpsList: List<com.multiviewer.parser.H264Pps> by mutableStateOf(emptyList())
    var avcLengthSize: Int? by mutableStateOf(null)

    // HEVC VPS/SPS/PPS (see HevcParameterSets.kt / HevcParameterSetExtraction.kt) -- parsed once
    // per video tab from the hvcC box, independent of any specific frame selection. Same
    // empty-list-means-"not applicable" convention as the avc* fields above; a stream is either
    // H.264 or HEVC, so at most one of the two field groups is ever populated for a given tab.
    var hevcVpsList: List<com.multiviewer.parser.HevcVps> by mutableStateOf(emptyList())
    var hevcSpsList: List<com.multiviewer.parser.HevcSps> by mutableStateOf(emptyList())
    var hevcPpsList: List<com.multiviewer.parser.HevcPps> by mutableStateOf(emptyList())
    var hevcLengthSize: Int? by mutableStateOf(null)

    // AV1 Sequence Header (see Av1SequenceHeader.kt / Av1ParameterSetExtraction.kt) -- parsed once
    // per video tab from the av1C box's configOBUs field, independent of any specific frame
    // selection. AV1 has one stream-wide Sequence Header, not an id-addressable set like
    // avcC/hvcC's SPS/PPS/VPS, so this is a single nullable object + one offset range, not a
    // List/Map keyed by id.
    var av1SequenceHeader: com.multiviewer.parser.Av1SequenceHeader? by mutableStateOf(null)
    var av1SequenceHeaderOffset: LongRange? by mutableStateOf(null)

    // AV1 Frame Header, per selected frame (see Av1FrameHeader.kt / Av1FrameHeaderAnalyzer.kt) --
    // unlike av1SequenceHeader (stream-wide), this is resolved per frame; populated all at once by
    // a sequential pass over every frame once both the parsed av1SequenceHeader and gopFrames (the
    // user-triggered frame analysis, see AppState.analyzeFrames) are available, keyed by
    // FrameInfo.byteOffset to match how selectedFrame is looked up elsewhere in this class.
    var av1FrameHeaders: Map<Long, com.multiviewer.parser.Av1FrameHeader> by mutableStateOf(emptyMap())

    // Byte-range maps for the "click a Parameter Sets id row to jump the hex viewer to its actual
    // NAL bytes" feature -- keyed by each parameter set's own id (the same id already used to look
    // them up in resolveActiveParameterSets/resolveActiveHevcParameterSets above), each value the
    // exact file byte range of that entry's raw NAL bytes inside the avcC/hvcC box (see RawNal).
    var avcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var avcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcVpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    // Set when a Parameter Sets id row is clicked (see ImageInspectorUI.kt); cleared automatically
    // whenever a different frame is selected (see DetailPropertiesTabContent's LaunchedEffect), so
    // the hex viewer naturally reverts to highlighting the newly-selected frame's own bytes. Read
    // by Main.kt's existing hex-highlight/scroll fallback chain as its new top-priority source.
    var parameterSetHighlightRange: LongRange? by mutableStateOf(null)

    // Frame thumbnail filmstrip (see FrameThumbnailDecoder.kt) -- keyed by frame index, populated
    // lazily as the filmstrip scrolls. pendingThumbnailIndices tracks in-flight requests so a
    // rapid double-trigger (e.g. two scroll events before the first batch returns) doesn't launch
    // two overlapping ffmpeg calls covering the same range.
    var thumbnailCache: Map<Int, androidx.compose.ui.graphics.ImageBitmap> by mutableStateOf(emptyMap())
    var pendingThumbnailIndices: Set<Int> by mutableStateOf(emptySet())

    // Full-size frame preview popup (see FrameFullSizeDecoder.kt) -- true while the popup window
    // is open, set by clicking a filmstrip thumbnail. The popup always shows whichever frame is
    // CURRENTLY selectedFrame, live -- not the frame that was selected at the moment it was
    // opened -- so stepping frames with the filmstrip's arrow keys updates the open popup too.
    // fullSizeFrameBitmap is the actual native-resolution decode (separate from the small
    // thumbnailCache entry for the same frame).
    var fullSizeFramePreviewOpen: Boolean by mutableStateOf(false)
    var fullSizeFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var codecViewFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var isDecodingCodecViewFrame: Boolean by mutableStateOf(false)

    // Motion Photo Video codec-detail enrichment (see StreamCodecDetails.kt) -- button-triggered
    // since, unlike the main video, this requires extracting the embedded video to a temp file
    // before ffprobe can see it. motionPhotoVideoSections is already non-null before this runs
    // (built by buildMediaSummary), so unlike gopFrames its nullability can't signal "not yet
    // asked" -- a separate flag is needed.
    var isAnalyzingMotionPhotoCodec: Boolean by mutableStateOf(false)
    var motionPhotoCodecDetailsLoaded: Boolean by mutableStateOf(false)

    // Motion Photo Video frame-interval analysis (see FrameIntervalAnalysisView.kt) -- same
    // extract-to-temp-file requirement as the codec-detail enrichment above, but reuses the
    // regular video path's own probeFrameTypes/probeVideo instead of probeStreamDetails. null
    // motionPhotoGopFrames means "never asked" (same convention as gopFrames above).
    var motionPhotoGopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingMotionPhotoFrames: Boolean by mutableStateOf(false)
    var motionPhotoVideoFps: Double? by mutableStateOf(null)

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

    // Headerless raw PCM audio parameters (see RawAudioOpenDialog) -- null unless this tab was
    // opened via the raw-audio path. FfmpegAudioPlayer uses this to supply ffmpeg the input-side
    // format hints raw PCM can't self-describe.
    var rawAudioParams: RawAudioParams? by mutableStateOf(null)
}

private val RAW_PIXEL_EXTENSIONS = listOf("raw", "rgb", "rgba", "yuv", "nv12", "nv21")
private val RAW_AUDIO_EXTENSIONS = listOf("pcm")

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
    // Same idea for headerless raw PCM audio -- RawAudioOpenDialog collects sample rate/channels/
    // format/byte order/offset while this is non-null.
    var pendingRawAudioFile: File? by mutableStateOf(null)
    // .raw is ambiguous between the raw-pixel and raw-audio features (both use it in the wild) --
    // openFile() routes a .raw file here first so Main.kt can show a two-button chooser, which
    // then sets pendingRawPixelFile or pendingRawAudioFile based on the user's answer.
    var pendingRawFileChoice: File? by mutableStateOf(null)

    fun cancelRawPixelFile() {
        pendingRawPixelFile = null
    }

    fun chooseRawFileAsPixel() {
        val file = pendingRawFileChoice ?: return
        pendingRawFileChoice = null
        pendingRawPixelFile = file
    }

    fun chooseRawFileAsAudio() {
        val file = pendingRawFileChoice ?: return
        pendingRawFileChoice = null
        pendingRawAudioFile = file
    }

    fun cancelRawFileChoice() {
        pendingRawFileChoice = null
    }

    fun cancelRawAudioFile() {
        pendingRawAudioFile = null
    }

    fun confirmRawAudioFile(params: RawAudioParams) {
        val file = pendingRawAudioFile ?: return
        pendingRawAudioFile = null
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
        val duration = computeRawAudioDuration(
            file.length(), params.offsetBytes, params.sampleRate, params.channels, params.format.bytesPerSample,
        )
        tab.type = MediaType.AUDIO
        tab.rawAudioParams = params
        tab.root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = file.length(),
            children = listOf(
                BoxNode(
                    type = "RawAudioData", offset = 0, headerSize = 0, size = file.length(),
                    fields = listOf(
                        BoxField("Sample Rate", "${params.sampleRate} Hz", 0, file.length()),
                        BoxField("Channels", params.channels.toString(), 0, file.length()),
                        BoxField("Format", params.format.label, 0, file.length()),
                        BoxField("Byte Order", params.byteOrder.label, 0, file.length()),
                        BoxField("Offset", "${params.offsetBytes} bytes", 0, file.length()),
                        BoxField("Duration", "%.2f seconds".format(duration), 0, file.length()),
                    ),
                    summary = "${params.sampleRate} Hz, ${params.channels}ch, ${params.format.label}",
                ),
            ),
        )
        tab.isLoading = false
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
        // .raw is ambiguous with raw-audio (both use this extension in the wild) -- checked before
        // RAW_PIXEL_EXTENSIONS below so it goes through the chooser instead of assuming pixel data.
        if (extension == "raw") {
            statusMessage = null
            pendingRawFileChoice = file
            return
        }
        if (extension in RAW_PIXEL_EXTENSIONS) {
            statusMessage = null
            pendingRawPixelFile = file
            return
        }
        if (extension in RAW_AUDIO_EXTENSIONS) {
            statusMessage = null
            pendingRawAudioFile = file
            return
        }
        // Reject anything else outright rather than falling through to parseFile(): its magic-byte
        // dispatch (ParseFile.kt) has no "unrecognized" case for the box-parser branch, so an
        // arbitrary non-media file would previously be handed to the MP4/MOV box walker and parsed
        // as if it might be one -- undefined behavior on genuinely arbitrary bytes, not something
        // this app can promise won't hang or crash. See README's "Supported Specs & Limits".
        if (extension !in IMAGE_EXTENSIONS && extension !in VIDEO_EXTENSIONS && extension !in AUDIO_EXTENSIONS) {
            val supported = (IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + RAW_PIXEL_EXTENSIONS + RAW_AUDIO_EXTENSIONS).joinToString(", ")
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
                    ByteReader.open(file).use { reader -> findEmbeddedVideo(root, reader) }
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

                        if (finalImageForensic.embeddedThumbnail == null && finalImageForensic.hasThumbnailReference) {
                            // The fast/sync path (ImageAnalyzer.analyze) only decodes JPEG-coded
                            // thumbnail items; a structural thumbnail reference with no decoded
                            // image means it's very likely HEVC-coded instead (common in modern
                            // HEIC files) -- try that specific path too, independently of the
                            // primary image decode below.
                            FfmpegImageSnapshotDecoder.decodeEmbeddedHevcThumbnailAsync(file, root) { thumbBitmap ->
                                if (thumbBitmap != null) {
                                    val current = tab.imageForensic ?: finalImageForensic
                                    val orientedThumb = ImageAnalyzer.orientImageBitmap(thumbBitmap, current.thumbnailOrientationCode)
                                    tab.imageForensic = current.copy(embeddedThumbnail = orientedThumb)
                                }
                            }
                        }

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
                                    // Do NOT substitute the full-resolution primary decode as the
                                    // "thumbnail" when the real embedded thumbnail item couldn't be
                                    // extracted (e.g. an HEVC-coded HEIC "thmb" item -- this parser
                                    // only decodes JPEG-coded thumbnail items) -- that previously
                                    // showed a full-size duplicate of the primary image mislabeled
                                    // as the thumbnail. Leaving it null lets the UI correctly say
                                    // the thumbnail couldn't be decoded instead of showing wrong
                                    // pixels at the wrong size.
                                    tab.imageForensic = current.copy(
                                        bitmap = fallbackBitmap,
                                        isDecodingFallback = false,
                                    )
                                }
                            }
                        }

                        if (extension == "gif") {
                            // Runs alongside the primary static decode above, not instead of it --
                            // the static decode still feeds the (now-hidden-for-GIF) thumbnail/
                            // primary boxes as a fallback for as long as this hasn't finished, and
                            // GifFilmstripPlayer itself falls back to a plain static frame when this
                            // decode fails outright (see GifFrameDecoder.kt).
                            runInBackground {
                                val animation = decodeGifAnimation(file)
                                EventQueue.invokeLater { tab.gifAnimation = animation }
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
        tab.frameAnalysisLoadedCount = 0
        tab.frameAnalysisProgress = null
        runInBackground {
            kotlinx.coroutines.runBlocking {
                probeFrameTypesStreaming(tab.file).collect { progress ->
                    EventQueue.invokeLater {
                        tab.gopFrames = progress.frames
                        tab.frameAnalysisLoadedCount = progress.loadedCount
                        tab.frameAnalysisProgress = if (progress.estimatedTotal != null && progress.estimatedTotal > 0) {
                            (progress.loadedCount.toFloat() / progress.estimatedTotal).coerceIn(0f, 1f)
                        } else null
                        if (progress.isComplete) {
                            tab.isAnalyzingFrames = false
                            tab.frameAnalysisProgress = null
                        }
                    }
                }
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

    fun analyzeMotionPhotoFrames(tab: TabState) {
        val video = tab.embeddedVideo ?: return
        if (tab.isAnalyzingMotionPhotoFrames || tab.motionPhotoGopFrames != null) return
        tab.isAnalyzingMotionPhotoFrames = true
        runInBackground {
            val temp = try {
                val dest = File.createTempFile("motion-photo-frame-interval-", ".${video.extension}")
                dest.deleteOnExit()
                extractEmbeddedVideo(tab.file, video, dest)
                dest
            } catch (e: Exception) {
                null
            }
            val videoInfo = temp?.let { probeVideo(it) }
            if (temp != null) {
                kotlinx.coroutines.runBlocking {
                    probeFrameTypesStreaming(temp).collect { progress ->
                        EventQueue.invokeLater {
                            tab.motionPhotoGopFrames = progress.frames
                            tab.motionPhotoVideoFps = videoInfo?.fps
                            if (progress.isComplete) {
                                tab.isAnalyzingMotionPhotoFrames = false
                            }
                        }
                    }
                }
                temp.delete()
            } else {
                EventQueue.invokeLater {
                    tab.motionPhotoGopFrames = emptyList()
                    tab.isAnalyzingMotionPhotoFrames = false
                }
            }
        }
    }
}
