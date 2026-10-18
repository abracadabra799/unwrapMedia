package com.multiviewer.ui.ai

import com.multiviewer.cli.scrubPaths
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.GainmapInfo
import com.multiviewer.parser.GainmapValidator
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.c2pa.hasC2paManifestStore
import com.multiviewer.parser.c2pa.readC2paManifestStore
import com.multiviewer.parser.c2pa.verifyC2paHardBinding
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.scanHdrMetadata
import com.multiviewer.parser.hrd.HrdSimulationReport
import com.multiviewer.parser.metadata.TimedMetadataReport
import com.multiviewer.parser.spatial.analyzeSpatialVideo
import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.AudioWaveformData
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.I18n
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.StoredAnalysis
import com.multiviewer.ui.StoredComparison
import com.multiviewer.ui.probeFrameTypes
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Seconds an on-request report may run before the bridge gives up on it (and interrupts it). */
const val REPORT_TIMEOUT_SECONDS = 20L

/** Seconds a report that first runs its analysis (ffprobe frame probe, HDR scan) may take. */
const val AUTO_RUN_TIMEOUT_SECONDS = 120L

enum class AiReportKind(val key: String) {
    AVSYNC("avsync"), HDR("hdr"), HRD("hrd"), INTERVAL("interval"),
    TIMED("timed"), SPATIAL("spatial"), C2PA("c2pa"), SEF("sef"), GAINMAP("gainmap"),
    GOP("gop"), AUDIO("audio"), QUALITY("quality");

    companion object {
        fun of(key: String): AiReportKind? = entries.firstOrNull { it.key == key }
    }
}

/** What the AI can get for one kind on this file, captured at send time. */
sealed interface AiReportSource {
    /** A stored result, already rendered. */
    data class Ready(val text: String) : AiReportSource
    /**
     * A light analysis, blocking; the bridge runs it off the UI thread with [timeoutSeconds]. [autoRunLabel]
     * is set when the report first runs a heavier analysis the user has not run (shown to the model).
     * [run] is last so call sites can pass it as a trailing lambda.
     */
    class OnRequest(
        val timeoutSeconds: Long = REPORT_TIMEOUT_SECONDS,
        val autoRunLabel: String? = null,
        val run: () -> String,
    ) : AiReportSource
    /** Not run, stale, or missing a prerequisite; [reason] says which (user language). */
    data class Unavailable(val reason: String) : AiReportSource
}

/** Send-time snapshot inputs (read on the UI thread). */
data class AiReportInputs(
    val file: File,
    val root: BoxNode?,
    val type: MediaType,
    /** Rendered by [aiReportSources] (off the UI thread), not at send time. */
    val avSync: com.multiviewer.ui.AvSyncReport?,
    val hdr: StoredAnalysis<HdrScanResult>?,
    val hrd: StoredAnalysis<HrdSimulationReport>?,
    val timed: StoredAnalysis<TimedMetadataReport>?,
    val frames: List<FrameInfo>?,
    val gainmap: GainmapInfo?,
    /** Coded base-image size, as GainmapCheckPage computes it (EXIF rotation undone). */
    val gainmapBase: Pair<Int, Int>?,
    val audio: StoredAnalysis<AudioWaveformData>? = null,
    val quality: StoredComparison? = null,
)

/** Shared by one send: what an auto-run produced, so later requests in the same send reuse it. */
class AiAutoRunState(initialFrames: List<FrameInfo>?) {
    val frames = java.util.concurrent.atomic.AtomicReference(initialFrames)
    val hdr = java.util.concurrent.atomic.AtomicReference<HdrScanResult?>(null)
    /** Why the probe / scan produced nothing usable in this send; later requests return it without re-running. */
    val framesFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val hdrFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
}

/** Injected work for auto-runs (defaults = the real analyses; tests replace them). */
class AiAutoRunHooks(
    val probeFrames: (File) -> List<FrameInfo>? = ::probeFrameTypes,
    val scanHdr: (File, BoxNode, () -> Boolean) -> HdrScanResult? = { f, r, c -> scanHdrMetadata(f, r, isCancelled = c) },
    val captureIdentity: (File) -> ContentFileIdentity? = { runCatching { ContentFileIdentity.capture(it) }.getOrNull() },
    /** Called off the UI thread with a finished result; the controller posts it to the tab on the UI thread. */
    val publishFrames: (List<FrameInfo>) -> Unit = {},
    val publishHdr: (StoredAnalysis<HdrScanResult>) -> Unit = {},
    /** Running indicator: non-null label while an auto-run executes, null after (always cleared in finally). */
    val running: (String?) -> Unit = {},
)

private val TIMED_NODE_TYPES = setOf("camm", "gpmd", "mebx")

private fun menu(name: (AppLanguage) -> String, label: (String, String) -> String) =
    label(name(AppLanguage.KO), name(AppLanguage.EN))

private fun <T : Any> stored(
    s: StoredAnalysis<T>?,
    file: File,
    menuName: String,
    label: (String, String) -> String,
    render: (T) -> String,
): AiReportSource {
    if (s == null) return AiReportSource.Unavailable(label(
        "'$menuName'에서 실행하면 요청 가능", "available after running '$menuName'"))
    val current = s.currentFor(file) ?: return AiReportSource.Unavailable(
        if (s.isReclaimed) label(
            "메모리 확보를 위해 결과가 해제됨 — '$menuName'을(를) 다시 실행",
            "the result was released to free memory — run '$menuName' again")
        else label(
            "파일이 바뀌어 결과가 무효 — '$menuName'을(를) 다시 실행",
            "the file changed, so the result is void — run '$menuName' again"))
    return storedValue(current, label, render)
}

// A renderer bug must degrade to one unavailable kind, never block the whole send.
private fun <T : Any> storedValue(current: T, label: (String, String) -> String, render: (T) -> String): AiReportSource =
    try {
        AiReportSource.Ready(render(current))
    } catch (e: Exception) {
        if (e is InterruptedException) throw e
        if (e is java.util.concurrent.CancellationException) throw e
        AiReportSource.Unavailable("report could not be rendered (${e::class.simpleName})")
    }

private fun notRun(menuName: String, label: (String, String) -> String) = AiReportSource.Unavailable(label(
    "아직 실행되지 않음 — '$menuName' 실행 필요", "not run for this file — run '$menuName' first"))

private fun audioSource(s: StoredAnalysis<AudioWaveformData>?, file: File, label: (String, String) -> String): AiReportSource {
    if (s == null) return AiReportSource.Unavailable(label(
        "오디오 파형이 그려지면 요청 가능", "available once the waveform has been drawn"))
    val current = s.currentFor(file) ?: return AiReportSource.Unavailable(
        if (s.isReclaimed) label(
            "메모리 확보를 위해 파형이 해제됨 — 파일을 다시 여세요",
            "the waveform was released to free memory — reopen the file")
        else label(
            "파일이 바뀌어 파형이 무효 — 파일을 다시 여세요",
            "the file changed, so the waveform is void — reopen the file"))
    return AiReportSource.OnRequest { renderAudioReport(current) }
}

/**
 * Which reports apply to this file and what state each is in. Blocking (file stat, cheap tree scans):
 * call off the UI thread. Kinds that do not apply to this file are absent from the map.
 */
fun aiReportSources(
    inputs: AiReportInputs,
    label: (String, String) -> String,
    /** Test seam: the HDR renderer. */
    renderHdr: (HdrScanResult, File) -> String = ::renderHdrReport,
    /** Shared by every request of one send, so an auto-run happens at most once per send. */
    autoRun: AiAutoRunState = AiAutoRunState(inputs.frames),
    hooks: AiAutoRunHooks = AiAutoRunHooks(),
): Map<AiReportKind, AiReportSource> {
    val file = inputs.file
    val root = inputs.root
    fun has(pred: (BoxNode) -> Boolean) = root?.let { findFirst(it, pred) } != null
    val video = inputs.type == MediaType.VIDEO
    val out = linkedMapOf<AiReportKind, AiReportSource>()
    if (video) {
        out[AiReportKind.AVSYNC] = inputs.avSync?.let { AiReportSource.Ready(renderAvSyncReport(it)) }
            ?: notRun(menu(I18n::menuAvSyncAnalysis, label), label)
        val autoRunLabel = label("분석 자동 실행", "runs the analysis")
        // A current stored result is used as is (a renderer failure stays Unavailable); a missing or stale one
        // is scanned again on request.
        // Read once: a reclaim between two reads must not turn "rescan" into "released".
        val hdrNow = inputs.hdr?.currentFor(file)
        val hdrCurrent = hdrNow != null
        // Only an MP4/MOV-family tree (moov) can be scanned; anything else keeps the stored-or-menu behaviour.
        val canScanHdr = root != null && has { it.type == "moov" }
        out[AiReportKind.HDR] = if (hdrCurrent || !canScanHdr) {
            if (hdrNow != null) storedValue(hdrNow, label) { renderHdr(it, file) }
            else stored(inputs.hdr, file, menu(I18n::menuHdrSeiAnalysis, label), label) { renderHdr(it, file) }
        } else {
            AiReportSource.OnRequest(AUTO_RUN_TIMEOUT_SECONDS, autoRunLabel) {
                autoRun.hdrFailure.get()?.let { return@OnRequest "HDR analysis already failed in this question: $it" }
                val result = autoRun.hdr.get() ?: synchronized(autoRun.hdr) {
                    autoRun.hdrFailure.get()?.let { return@OnRequest "HDR analysis already failed in this question: $it" }
                    autoRun.hdr.get() ?: run {
                        // Captured before the scan: a file changed mid-scan then never matches the stored result.
                        val id = hooks.captureIdentity(file)
                        hooks.running(label("HDR 분석", "HDR analysis"))
                        var r: HdrScanResult? = null
                        var failure = "interrupted or timed out"
                        try {
                            r = hooks.scanHdr(file, root!!) { Thread.currentThread().isInterrupted }
                            if (r == null) failure = "no supported video sample table"
                        } catch (e: Exception) {
                            failure = e::class.simpleName ?: "error"
                            throw e
                        } finally {
                            hooks.running(null)
                            // A null scan is also what an interrupted scan returns: never cache or publish it.
                            if (r == null || Thread.currentThread().isInterrupted) {
                                r = null
                                autoRun.hdrFailure.compareAndSet(null, failure)
                            }
                        }
                        if (r == null) return@OnRequest "HDR scan: no supported video sample table (MP4/MOV family only)"
                        autoRun.hdr.set(r)
                        id?.let { hooks.publishHdr(StoredAnalysis(r, it)) }
                        r
                    }
                }
                renderHdr(result, file)
            }
        }
        out[AiReportKind.HRD] = stored(inputs.hrd, file, menu(I18n::menuHrdBufferSimulation, label), label) { renderHrdReport(it) }
        // One probe per send, shared by gop, interval and later frame requests. A failed probe is remembered
        // (framesFailure) so no later request of the same send spawns ffprobe again. Returns frames or a failure text.
        fun failedText(reason: String) = "frame analysis already failed in this question: $reason"
        fun framesOrRun(): Pair<List<FrameInfo>?, String?> {
            autoRun.frames.get()?.takeIf { it.size >= 2 }?.let { return it to null }
            autoRun.framesFailure.get()?.let { return null to failedText(it) }
            synchronized(autoRun) {
                autoRun.frames.get()?.takeIf { it.size >= 2 }?.let { return it to null }
                autoRun.framesFailure.get()?.let { return null to failedText(it) }
                hooks.running(label("프레임 분석", "frame analysis"))
                var good: List<FrameInfo>? = null
                var text = "frame analysis could not run (ffprobe failed or timed out)"
                try {
                    val probed = hooks.probeFrames(file)
                    if (probed != null && probed.size < 2) text = "frame analysis found fewer than 2 frames"
                    good = probed?.takeIf { it.size >= 2 && !Thread.currentThread().isInterrupted }
                    if (good != null) {
                        autoRun.frames.set(good)
                        hooks.publishFrames(good)
                    }
                } catch (e: Exception) {
                    text = "frame analysis failed (${e::class.simpleName})"
                    throw e
                } finally {
                    hooks.running(null)
                    if (good == null) autoRun.framesFailure.compareAndSet(null, text)
                }
                return if (good != null) good to null else null to text
            }
        }
        fun framesReport(render: (List<FrameInfo>) -> String): AiReportSource {
            val frames = inputs.frames
            if (frames != null && frames.size >= 2) return AiReportSource.OnRequest { render(frames) }
            return AiReportSource.OnRequest(AUTO_RUN_TIMEOUT_SECONDS, autoRunLabel) {
                val (f, failure) = framesOrRun()
                if (f == null) failure ?: "frame analysis could not run" else render(f)
            }
        }
        out[AiReportKind.INTERVAL] = framesReport(::renderIntervalReport)
        out[AiReportKind.GOP] = framesReport(::renderGopReport)
        if (inputs.timed != null || has { it.type in TIMED_NODE_TYPES }) {
            out[AiReportKind.TIMED] = stored(inputs.timed, file, menu(I18n::menuTimedMetadataTimeline, label), label) { renderTimedReport(it) }
        }
        if (root != null && has { it.type == "lhvC" }) {
            out[AiReportKind.SPATIAL] = AiReportSource.OnRequest {
                analyzeSpatialVideo(file, root)?.let { renderSpatialReport(it, file) } ?: "no HEVC video track"
            }
        }
    }
    if (inputs.type == MediaType.AUDIO) {
        out[AiReportKind.AUDIO] = audioSource(inputs.audio, file, label)
    }
    val quality = inputs.quality
    if (quality != null && quality.files.values.any { it.absolutePath == file.absolutePath }) {
        out[AiReportKind.QUALITY] = if (quality.currentFor(file)) {
            AiReportSource.OnRequest { renderQualityReport(quality) }
        } else {
            AiReportSource.Unavailable(label(
                "비교한 파일이 바뀌어 결과가 무효 — 다시 비교", "a compared file changed, so the result is void — compare again"))
        }
    }
    if (root != null && hasC2paManifestStore(root)) {
        out[AiReportKind.C2PA] = AiReportSource.OnRequest {
            val store = readC2paManifestStore(file, root) ?: return@OnRequest "C2PA store could not be read"
            val binding = runCatching { verifyC2paHardBinding(file, root, store) }
            val error = binding.exceptionOrNull()?.let { e ->
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                reportErrorText(e, file)
            }
            renderC2paReport(store, binding.getOrNull(), error, file)
        }
    }
    val sefd = root?.let { findFirst(it) { n -> n.type == "sefd" } }
    if (sefd != null) {
        out[AiReportKind.SEF] = AiReportSource.OnRequest {
            val report = ByteReader.open(file).use {
                SefIntegrityAnalyzer.analyze(it, sefd.offset, sefd.headerSize, sefd.size, file.length())
            }
            renderSefReport(report, file)
        }
    }
    val gainmap = inputs.gainmap
    if (gainmap?.hasGainmap == true && root != null) {
        val base = inputs.gainmapBase
        out[AiReportKind.GAINMAP] = AiReportSource.OnRequest {
            renderGainmapReport(gainmap, GainmapValidator.validate(file, root, gainmap, base?.first, base?.second), file)
        }
    }
    return out
}

/**
 * "ClassName: message" for this app's own exceptions (their messages are written for the user, path-scrubbed,
 * clipped); only the class name for anything else, since JDK messages can echo file content.
 */
fun reportErrorText(e: Throwable, file: File): String {
    val name = e::class.simpleName ?: "Exception"
    val own = e::class.java.name.startsWith("com.multiviewer")
    val detail = if (own) scrubPaths(e.message ?: "", file).take(200) else ""
    return "$name: $detail".trimEnd(' ', ':')
}

/** When the file changed since it was opened the bridge refuses every report, so none may be offered. */
fun markReportsStale(sources: Map<AiReportKind, AiReportSource>, label: (String, String) -> String): Map<AiReportKind, AiReportSource> {
    val reason = AiReportSource.Unavailable(label(
        "파일이 열린 뒤 바뀌었습니다 — 다시 열어 주세요", "the file changed since it was opened — reopen it"))
    return sources.mapValues { (_, s) -> if (s is AiReportSource.Unavailable) s else reason }
}

/** The context lines telling the model which reports exist for this file; empty when none apply. */
fun renderReportSection(sources: Map<AiReportKind, AiReportSource>, label: (String, String) -> String): String {
    if (sources.isEmpty()) return ""
    return buildString {
        appendLine(label(
            "### [분석 리포트 — 필요하면 `$TOOL_PREFIX report <kind>`로 요청]",
            "### [Analysis reports — request with `$TOOL_PREFIX report <kind>` if needed]",
        ))
        AiReportKind.entries.forEach { kind ->
            val state = when (val s = sources[kind] ?: return@forEach) {
                is AiReportSource.Ready -> label("실행됨", "available")
                is AiReportSource.OnRequest -> s.autoRunLabel?.let { label("요청 가능 ($it)", "on request ($it)") }
                    ?: label("요청 가능", "on request")
                is AiReportSource.Unavailable -> s.reason
            }
            appendLine("- ${kind.key}: $state")
        }
    }.trimEnd()
}

/**
 * Runs one on-request report on its own daemon thread, waiting at most [timeoutSeconds]. On timeout or when
 * the caller is interrupted the worker is interrupted; nothing outside this call's executor is touched.
 * Returns (ok, text); failure text carries the exception class and a path-scrubbed message only.
 */
fun runReportWithTimeout(run: () -> String, file: File, timeoutSeconds: Long = REPORT_TIMEOUT_SECONDS): Pair<Boolean, String> {
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-report").apply { isDaemon = true } }
    try {
        val future = executor.submit<String> { run() }
        return try {
            true to future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            false to "The report timed out after $timeoutSeconds s."
        } catch (e: InterruptedException) {
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            false to "The report failed: ${reportErrorText(cause, file)}"
        }
    } finally {
        executor.shutdownNow()
    }
}
