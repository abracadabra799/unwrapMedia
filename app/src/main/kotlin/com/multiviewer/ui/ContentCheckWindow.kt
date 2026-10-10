package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.JsonValue
import com.multiviewer.cli.buildAnalysisCaseJson
import com.multiviewer.cli.buildCheckJson
import com.multiviewer.cli.imageIntegrityJson
import com.multiviewer.cli.motionPhotoJson
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.collectWarnings
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Composable
private fun integrityStatusColor(status: IntegrityStatus): Color = when (status) {
    IntegrityStatus.NOT_RUN -> AppColors.TextMuted
    IntegrityStatus.CLEAN -> AppColors.NeonGreen
    IntegrityStatus.ISSUES -> AppColors.NeonYellow
    IntegrityStatus.FAILED -> AppColors.NeonRed
}

/**
 * Analysis → 컨텐츠 검사 / Content Check: one window per file that runs every applicable check
 * for its media type (structure on open; image decode / motion photo / video integrity on 검사 시작).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentCheckWindow(
    tab: TabState,
    language: AppLanguage,
    onAiDiagnosis: () -> Unit,
    onCloseRequest: () -> Unit,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    var structure by remember(tab.file) { mutableStateOf<ImageStructureReport?>(null) }
    var structureError by remember(tab.file) { mutableStateOf<String?>(null) }
    var imageDecode by remember(tab.file) { mutableStateOf<ImageDecodeReport?>(null) }
    var motion by remember(tab.file) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }
    var motionError by remember(tab.file) { mutableStateOf<String?>(null) }
    var video by remember(tab.file) { mutableStateOf<VideoIntegrityReport?>(null) }
    var job by remember(tab.file) { mutableStateOf<Job?>(null) }
    var running by remember(tab.file) { mutableStateOf(false) }
    var saving by remember(tab.file) { mutableStateOf(false) }
    var runId by remember(tab.file) { mutableStateOf(0) }
    var message by remember(tab.file) { mutableStateOf("") }
    var selectedTab by remember(tab.file) { mutableStateOf(ContentTab.STRUCTURE) }
    // Hoisted out of the video panels so they survive switching between the decode and packet tabs.
    var expandedDiagnostic by remember(tab.file) { mutableStateOf<Int?>(null) }
    var selectedPacket by remember(tab.file) { mutableStateOf<IntegrityPacket?>(null) }
    fun cancelledMessage() = label("중단 · 검사 완료되지 않음", "Cancelled · inspection incomplete")

    val type = tab.type
    val motionDetected = remember(tab.root, type) { type == MediaType.IMAGE && tab.root?.let(::hasMotionPhotoData) == true }
    val tabs = contentTabs(type, motionDetected)
    val steps = heavySteps(type, motionDetected)
    DisposableEffect(tab.file) { onDispose { job?.cancel() } }

    // Publish the current run's results to the tab for the AI prompt. These states are only ever
    // written by the current run (every write is behind the runId/current() guard), so a stale or
    // cancelled run can never reach the tab through here.
    LaunchedEffect(tab, structure, imageDecode, motion, motionError, video, motionDetected, type) {
        tab.contentCheck = ContentCheckSnapshot(structure, imageDecode, motionDetected, motion, motionError, video, type)
    }

    // Keyed on tab.root too: the window can open before the file's structure tree finishes loading.
    LaunchedEffect(tab.file, tab.root, type) {
        val root = tab.root
        if (root == null) {
            structureError = label("파일 구조가 아직 로드되지 않았습니다", "The file structure is not loaded yet")
            return@LaunchedEffect
        }
        structureError = null
        try {
            structure = withContext(Dispatchers.IO) { contentStructureReport(tab.file, root, type) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            structureError = e.message ?: e.toString()
        }
    }

    /** The `imageIntegrity` JSON for images (same shape as `unwrapMedia check`), null for other types. */
    fun imageJson(
        s: ImageStructureReport?,
        decode: ImageDecodeReport?,
        motionReport: MotionPhotoIntegrityReport?,
        motionErr: String?,
    ): JsonValue? =
        if (type == MediaType.IMAGE && s != null) {
            imageIntegrityJson(s, decode, motionPhotoJson(motionDetected, motionReport, tab.file, motionErr))
        } else {
            null
        }

    Window(
        onCloseRequest = { job?.cancel(); onCloseRequest() },
        state = rememberWindowState(width = 1080.dp, height = 780.dp),
        title = label("컨텐츠 검사", "Content Check") + " — ${tab.file.name}",
    ) {
        Column(
            Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val s = structure
            val v = video
            Text(tab.file.name, color = AppColors.TextPrimary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label("유형: ", "Type: ") + type.name, color = AppColors.TextSecondary)
                if (type == MediaType.IMAGE) {
                    Text(label("형식: ", "Format: ") + (s?.format ?: "…"), color = AppColors.TextSecondary)
                }
                for (t in tabs) {
                    when (t) {
                        ContentTab.STRUCTURE -> Text(
                            label("구조: ", "Structure: ") + (s?.overall?.let { checkStatusLabel(it, ko) } ?: "…"),
                            color = s?.overall?.let { checkStatusColor(it) } ?: AppColors.TextMuted,
                        )
                        ContentTab.IMAGE_DECODE -> {
                            val ds = imageDecode?.status ?: ImageDecodeStatus.NOT_RUN
                            Text(label("디코딩: ", "Decode: ") + headerDecodeLabel(imageDecode != null, ds, ko), color = decodeStatusColor(ds))
                        }
                        ContentTab.MOTION_PHOTO -> {
                            val mStatus = motion?.verdictStatus()
                            Text(
                                label("모션포토: ", "Motion photo: ") + when {
                                    mStatus != null -> checkStatusLabel(mStatus, ko)
                                    motionError != null -> label("실패", "Failed")
                                    else -> label("미검사", "Not run")
                                },
                                color = when {
                                    mStatus != null -> checkStatusColor(mStatus)
                                    motionError != null -> AppColors.NeonRed
                                    else -> AppColors.TextMuted
                                },
                            )
                        }
                        ContentTab.VIDEO_DECODE -> {
                            val st = v?.decodeStatus ?: IntegrityStatus.NOT_RUN
                            Text(label("영상 디코딩: ", "Video decode: ") + videoStatusLabel(st, ko), color = integrityStatusColor(st))
                        }
                        ContentTab.VIDEO_PACKETS -> {
                            val st = v?.packetStatus ?: IntegrityStatus.NOT_RUN
                            Text(label("패킷: ", "Packets: ") + videoStatusLabel(st, ko), color = integrityStatusColor(st))
                        }
                    }
                }
            }
            if (type == MediaType.VIDEO) {
                Text(
                    label("첫 번째 영상 트랙을 소프트웨어로 디코딩합니다. 오디오 검사는 포함하지 않습니다.",
                        "Software decoding of the first video track. Audio is not inspected."),
                    color = AppColors.TextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Image decode needs the structure report as input; the video check does not.
                val canStart = steps.isNotEmpty() && (s != null || HeavyStep.IMAGE_DECODE !in steps)
                Button(enabled = !running && !saving && canStart, onClick = {
                    val snapshot = s
                    if (HeavyStep.IMAGE_DECODE in steps && snapshot == null) return@Button
                    val runSteps = steps
                    val myRun = ++runId
                    running = true
                    imageDecode = null
                    motion = null
                    motionError = null
                    video = null
                    expandedDiagnostic = null
                    selectedPacket = null
                    message = when {
                        HeavyStep.MOTION_PHOTO in runSteps -> label("디코딩·모션포토 검사 중…", "Decoding and checking motion photo…")
                        HeavyStep.IMAGE_DECODE in runSteps -> label("디코딩 중…", "Decoding…")
                        else -> label("검사 중…", "Inspecting…")
                    }
                    job = scope.launch {
                        // A cancelled or superseded run must never write results/state for the current one.
                        fun current() = isActive && myRun == runId
                        var decodeFailure: String? = null
                        try {
                            for (step in runSteps) {
                                when (step) {
                                    HeavyStep.IMAGE_DECODE -> {
                                        try {
                                            val decoded = inspectImageDecode(tab.file, snapshot!!)
                                            if (!current()) return@launch
                                            imageDecode = decoded
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            // Motion analysis is independent of image decode: record and carry on.
                                            if (!current()) return@launch
                                            decodeFailure = e.message ?: e.toString()
                                            message = label("검사 실패: ", "Inspection failed: ") + decodeFailure
                                        }
                                    }
                                    HeavyStep.MOTION_PHOTO -> {
                                        val root = tab.root ?: continue
                                        try {
                                            // The analyzer blocks (ffmpeg decode, up to ~60 s) and ignores cancellation;
                                            // await() returns at once on Cancel, and the late result is discarded.
                                            val result = awaitDetached { MotionPhotoIntegrityAnalyzer.analyze(tab.file, root) }
                                            if (current()) motion = result
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            if (current()) {
                                                motionError = e.message ?: e.toString()
                                                message = label("모션포토 분석 실패: ", "Motion photo analysis failed: ") + motionError +
                                                    (decodeFailure?.let { " / " + label("검사 실패: ", "Inspection failed: ") + it } ?: "")
                                            }
                                        }
                                        if (!current()) return@launch
                                    }
                                    HeavyStep.VIDEO_INTEGRITY -> {
                                        val result = inspectVideoIntegrity(tab.file) { phase, count ->
                                            scope.launch progress@{
                                                if (!running || myRun != runId || job?.isActive != true) return@progress
                                                message = if (phase == "packets") label("패킷 읽는 중: $count", "Reading packets: $count")
                                                    else label("디코딩 중: $count 프레임", "Decoding: $count frames")
                                            }
                                        }
                                        if (!current()) return@launch
                                        video = result
                                    }
                                }
                            }
                            if (!current()) return@launch
                            if (motionError == null && decodeFailure == null) {
                                message = if (HeavyStep.VIDEO_INTEGRITY in runSteps) {
                                    label("검사 종료 — 아래 단계별 결과를 확인하세요", "Inspection finished — see results below")
                                } else {
                                    ""
                                }
                            }
                            selectedTab = when (runSteps.first()) {
                                HeavyStep.IMAGE_DECODE, HeavyStep.MOTION_PHOTO -> ContentTab.IMAGE_DECODE
                                HeavyStep.VIDEO_INTEGRITY -> ContentTab.VIDEO_DECODE
                            }
                        } catch (e: CancellationException) {
                            if (myRun == runId) message = cancelledMessage()
                            throw e
                        } catch (e: Exception) {
                            if (current()) message = label("검사 실패: ", "Inspection failed: ") + (e.message ?: e.toString())
                        } finally {
                            if (myRun == runId) running = false
                        }
                    }
                }) { Text(label("검사 시작", "Start inspection")) }
                OutlinedButton(enabled = running, onClick = {
                    job?.cancel()
                    running = false
                    message = cancelledMessage()
                }) { Text(label("취소", "Cancel")) }
                val canExport = tab.root != null && !running && !saving && (type != MediaType.IMAGE || s != null)
                OutlinedButton(enabled = canExport, onClick = {
                    val root = tab.root ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, label("분석 케이스 저장", "Save analysis case"), FileDialog.SAVE)
                    dialog.file = "${tab.file.nameWithoutExtension}-case.json"
                    dialog.isVisible = true
                    val output = dialog.file?.let { File(dialog.directory, it) }
                    dialog.dispose()
                    if (output == null) return@OutlinedButton
                    val structureSnapshot = s
                    val decodeSnapshot = imageDecode
                    val motionSnapshot = motion
                    val motionErrorSnapshot = motionError
                    val videoSnapshot = video
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val caseJson = buildAnalysisCaseJson(
                                    tab.file, collectWarnings(root), buildMediaSummary(root, tab.file),
                                    integrityReport = videoSnapshot,
                                    imageIntegrity = imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                )
                                // CREATE_NEW: never overwrite an existing file.
                                Files.writeString(output.toPath(), caseJson, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                            }
                            message = label("저장됨: ", "Saved: ") + output.name
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = label("저장 실패 (기존 파일 덮어쓰기 불가): ", "Save failed (existing files are not overwritten): ") +
                                (e.message ?: e.toString())
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(label("분석 케이스 저장", "Save analysis case")) }
                OutlinedButton(enabled = canExport, onClick = {
                    val root = tab.root ?: return@OutlinedButton
                    val structureSnapshot = s
                    val decodeSnapshot = imageDecode
                    val motionSnapshot = motion
                    val motionErrorSnapshot = motionError
                    val videoSnapshot = video
                    scope.launch {
                        val copiedText = label("복사됨", "Copied")
                        message = try {
                            val json = withContext(Dispatchers.Default) {
                                buildCheckJson(
                                    tab.file, collectWarnings(root), videoSnapshot,
                                    imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                )
                            }
                            if (ClipboardUtil.copyToClipboard(json)) copiedText else label("복사 실패", "Copy failed")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            label("복사 실패: ", "Copy failed: ") + (e.message ?: e.toString())
                        }
                        if (message == copiedText) {
                            delay(2000)
                            if (message == copiedText) message = ""
                        }
                    }
                }) { Text(label("JSON 복사", "Copy JSON")) }
                OutlinedButton(enabled = tab.root != null, onClick = onAiDiagnosis) { Text(label("AI 진단", "AI diagnosis")) }
            }
            if (steps.isEmpty()) {
                Text(
                    label("이 파일 형식은 1단계에서 구조 검사만 지원합니다", "Only structure checks are available for this type yet"),
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
            val shownTab = if (selectedTab in tabs) selectedTab else ContentTab.STRUCTURE
            TabRow(selectedTabIndex = tabs.indexOf(shownTab).coerceAtLeast(0)) {
                tabs.forEach { t ->
                    val title = when (t) {
                        ContentTab.STRUCTURE -> label("구조", "Structure")
                        ContentTab.IMAGE_DECODE -> label("디코딩", "Decode")
                        ContentTab.MOTION_PHOTO -> label("모션포토", "Motion photo")
                        ContentTab.VIDEO_DECODE -> label("영상 디코딩", "Video decode")
                        ContentTab.VIDEO_PACKETS -> label("패킷 매핑", "Packet mapping")
                    }
                    Tab(selected = shownTab == t, onClick = { selectedTab = t }, text = { Text(title) })
                }
            }
            when (shownTab) {
                ContentTab.STRUCTURE -> ImageStructurePanel(s, structureError, ko) { item ->
                    val offset = item.offset ?: return@ImageStructurePanel
                    tab.parameterSetHighlightRange = offset until offset + maxOf(1L, item.length ?: 1L)
                }
                ContentTab.IMAGE_DECODE -> ImageDecodePanel(imageDecode, ko)
                ContentTab.MOTION_PHOTO -> {
                    val m = motion
                    if (m == null && motionError != null) {
                        Text(label("모션포토 분석 실패: ", "Motion photo analysis failed: ") + motionError, color = AppColors.NeonRed)
                    } else if (m == null) {
                        Text(
                            label(
                                "'검사 시작'을 눌러 모션포토 영상 검사를 실행하세요. (영상 전체를 디코딩하므로 시간이 걸릴 수 있습니다)",
                                "Press 'Start inspection' to check the motion photo video. (It decodes the whole video, so it may take a while)",
                            ),
                            color = AppColors.TextSecondary,
                        )
                    } else {
                        MotionPhotoReportContent(m, Modifier.fillMaxSize())
                    }
                }
                ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS -> {
                    if (v == null) {
                        Text(
                            label("'검사 시작'을 눌러 영상 디코딩·패킷 검사를 실행하세요.",
                                "Press 'Start inspection' to run the video decode and packet checks."),
                            color = AppColors.TextSecondary,
                        )
                    } else if (shownTab == ContentTab.VIDEO_DECODE) {
                        VideoDecodePanel(
                            v, ko,
                            expandedDiagnostic = expandedDiagnostic,
                            onExpandedDiagnosticChange = { expandedDiagnostic = it },
                            onShowPackets = { selectedTab = ContentTab.VIDEO_PACKETS },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        VideoPacketPanel(
                            v, tab, ko,
                            selectedPacket = selectedPacket,
                            onSelectedPacketChange = { selectedPacket = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
