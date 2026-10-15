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
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.I18n
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.StoredAnalysis
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Seconds an on-request report may run before the bridge gives up on it (and interrupts it). */
const val REPORT_TIMEOUT_SECONDS = 20L

enum class AiReportKind(val key: String) {
    AVSYNC("avsync"), BITSTREAM("bitstream"), HDR("hdr"), HRD("hrd"), INTERVAL("interval"),
    TIMED("timed"), SPATIAL("spatial"), C2PA("c2pa"), SEF("sef"), GAINMAP("gainmap");

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
    val avSync: String?,
    val bitstream: String?,
    val hdr: StoredAnalysis<HdrScanResult>?,
    val hrd: StoredAnalysis<HrdSimulationReport>?,
    val timed: StoredAnalysis<TimedMetadataReport>?,
    val frames: List<FrameInfo>?,
    val gainmap: GainmapInfo?,
    /** Coded base-image size, as GainmapCheckPage computes it (EXIF rotation undone). */
    val gainmapBase: Pair<Int, Int>?,
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
        out[AiReportKind.AVSYNC] = inputs.avSync?.let { AiReportSource.Ready(it) }
            ?: notRun(menu(I18n::menuAvSyncAnalysis, label), label)
        // Nothing in the app produces this result yet; list it only when one exists.
        inputs.bitstream?.let { out[AiReportKind.BITSTREAM] = AiReportSource.Ready(it) }
        out[AiReportKind.HDR] = stored(inputs.hdr, file, menu(I18n::menuHdrSeiAnalysis, label), label) { renderHdr(it, file) }
        out[AiReportKind.HRD] = stored(inputs.hrd, file, menu(I18n::menuHrdBufferSimulation, label), label) { renderHrdReport(it) }
        val frames = inputs.frames
        out[AiReportKind.INTERVAL] = if (frames != null && frames.size >= 2) {
            AiReportSource.OnRequest { renderIntervalReport(frames) }
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
    if (root != null && hasC2paManifestStore(root)) {
        out[AiReportKind.C2PA] = AiReportSource.OnRequest {
            val store = readC2paManifestStore(file, root) ?: return@OnRequest "C2PA store could not be read"
            val binding = runCatching { verifyC2paHardBinding(file, root, store) }
            val error = binding.exceptionOrNull()?.let { e ->
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                scrubPaths("${e::class.simpleName}: ${e.message ?: ""}", file)
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
            val name = cause::class.simpleName ?: "Exception"
            // Only this app's own exceptions have messages written for the user; JDK ones can echo file content.
            val own = cause::class.java.name.startsWith("com.multiviewer")
            val detail = if (own) scrubPaths(cause.message ?: "", file).take(200) else ""
            false to "The report failed: $name: $detail".trimEnd(' ', ':')
        }
    } finally {
        executor.shutdownNow()
    }
}
