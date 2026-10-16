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
import com.multiviewer.parser.hrd.HrdSimulationReport
import com.multiviewer.parser.metadata.TimedMetadataReport
import com.multiviewer.parser.spatial.analyzeSpatialVideo
import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.AudioWaveformData
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.I18n
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.StoredAnalysis
import com.multiviewer.ui.StoredComparison
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Seconds an on-request report may run before the bridge gives up on it (and interrupts it). */
const val REPORT_TIMEOUT_SECONDS = 20L

enum class AiReportKind(val key: String) {
    AVSYNC("avsync"), BITSTREAM("bitstream"), HDR("hdr"), HRD("hrd"), INTERVAL("interval"),
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
    /** A light analysis, blocking; the bridge runs it off the UI thread with a timeout. */
    class OnRequest(val run: () -> String) : AiReportSource
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
    val bitstream: String?,
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

private val TIMED_NODE_TYPES = setOf("camm", "gpmd", "mebx")

private fun menu(name: (AppLanguage) -> String, label: (String, String) -> String) =
    label(name(AppLanguage.KO), name(AppLanguage.EN))

private fun <T> stored(
    s: StoredAnalysis<T>?,
    file: File,
    menuName: String,
    label: (String, String) -> String,
    render: (T) -> String,
): AiReportSource {
    if (s == null) return AiReportSource.Unavailable(label(
        "'$menuName'에서 실행하면 요청 가능", "available after running '$menuName'"))
    val current = s.currentFor(file) ?: return AiReportSource.Unavailable(label(
        "파일이 바뀌어 결과가 무효 — '$menuName'을(를) 다시 실행", "the file changed, so the result is void — run '$menuName' again"))
    // A renderer bug must degrade to one unavailable kind, never block the whole send.
    return try {
        AiReportSource.Ready(render(current))
    } catch (e: Exception) {
        if (e is InterruptedException) throw e
        if (e is java.util.concurrent.CancellationException) throw e
        AiReportSource.Unavailable("report could not be rendered (${e::class.simpleName})")
    }
}

private fun notRun(menuName: String, label: (String, String) -> String) = AiReportSource.Unavailable(label(
    "아직 실행되지 않음 — '$menuName' 실행 필요", "not run for this file — run '$menuName' first"))

private fun audioSource(s: StoredAnalysis<AudioWaveformData>?, file: File, label: (String, String) -> String): AiReportSource {
    if (s == null) return AiReportSource.Unavailable(label(
        "오디오 파형이 그려지면 요청 가능", "available once the waveform has been drawn"))
    val current = s.currentFor(file) ?: return AiReportSource.Unavailable(label(
        "파일이 바뀌어 파형이 무효 — 파일을 다시 여세요", "the file changed, so the waveform is void — reopen the file"))
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
): Map<AiReportKind, AiReportSource> {
    val file = inputs.file
    val root = inputs.root
    fun has(pred: (BoxNode) -> Boolean) = root?.let { findFirst(it, pred) } != null
    val video = inputs.type == MediaType.VIDEO
    val out = linkedMapOf<AiReportKind, AiReportSource>()
    if (video) {
        out[AiReportKind.AVSYNC] = inputs.avSync?.let { AiReportSource.Ready(renderAvSyncReport(it)) }
            ?: notRun(menu(I18n::menuAvSyncAnalysis, label), label)
        // No menu opens the bitstream window yet; the kind is listed only when a result exists.
        inputs.bitstream?.let { out[AiReportKind.BITSTREAM] = AiReportSource.Ready(it) }
        out[AiReportKind.HDR] = stored(inputs.hdr, file, menu(I18n::menuHdrSeiAnalysis, label), label) { renderHdr(it, file) }
        out[AiReportKind.HRD] = stored(inputs.hrd, file, menu(I18n::menuHrdBufferSimulation, label), label) { renderHrdReport(it) }
        val frames = inputs.frames
        out[AiReportKind.INTERVAL] = if (frames != null && frames.size >= 2) {
            AiReportSource.OnRequest { renderIntervalReport(frames) }
        } else {
            AiReportSource.Unavailable(label("프레임 분석(GOP) 실행 필요", "needs frame analysis (GOP) first"))
        }
        out[AiReportKind.GOP] = if (frames != null && frames.size >= 2) {
            AiReportSource.OnRequest { renderGopReport(frames) }
        } else {
            AiReportSource.Unavailable(label("프레임 분석(GOP) 실행 필요", "needs frame analysis (GOP) first"))
        }
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
                is AiReportSource.OnRequest -> label("요청 가능", "on request")
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
