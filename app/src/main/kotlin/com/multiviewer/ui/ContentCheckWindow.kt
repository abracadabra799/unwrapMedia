package com.multiviewer.ui

import com.multiviewer.parser.gainmapOverall
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.multiviewer.parser.GainmapValidator
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
 * for its media type only after an explicit run action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentCheckWindow(
    tab: TabState,
    language: AppLanguage,
    onAiDiagnosis: () -> Unit,
    onOpenMotionPhotoInspector: () -> Unit = {},
    onCloseRequest: () -> Unit,
    initialTab: ContentTab = ContentTab.STRUCTURE,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    // Scope every result/job to the target tab, even when two tabs refer to the same file.
    var structure by remember(tab) { mutableStateOf<ImageStructureReport?>(null) }
    var audioStructure by remember(tab) { mutableStateOf<AudioStructureReport?>(null) }
    var conformance by remember(tab) { mutableStateOf<ConformanceReport?>(null) }
    var fileLength by remember(tab) { mutableStateOf(0L) }
    var structureError by remember(tab) { mutableStateOf<String?>(null) }
    var imageDecode by remember(tab) { mutableStateOf<ImageDecodeReport?>(null) }
    var motion by remember(tab) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }
    var motionError by remember(tab) { mutableStateOf<String?>(null) }
    var video by remember(tab) { mutableStateOf<VideoIntegrityReport?>(null) }
    var audio by remember(tab) { mutableStateOf<AudioIntegrityReport?>(null) }
    var audioError by remember(tab) { mutableStateOf<String?>(null) }
    var imageDecodeError by remember(tab) { mutableStateOf<String?>(null) }
    var videoError by remember(tab) { mutableStateOf<String?>(null) }
    var job by remember(tab) { mutableStateOf<Job?>(null) }
    var running by remember(tab) { mutableStateOf(false) }
    var saving by remember(tab) { mutableStateOf(false) }
    var exportMenuExpanded by remember(tab) { mutableStateOf(false) }
    var runId by remember(tab) { mutableStateOf(0) }
    var message by remember(tab) { mutableStateOf("") }
    var selectedTab by remember(tab, initialTab) { mutableStateOf(initialTab) }
    var selectedStage by remember(tab) { mutableStateOf(CheckStage.STRUCTURE) }
    var manuallySelectedStage by remember(tab) { mutableStateOf(false) }
    // Hoisted out of the video panels so they survive switching between the decode and packet tabs.
    var expandedDiagnostic by remember(tab) { mutableStateOf<Int?>(null) }
    var selectedPacket by remember(tab) { mutableStateOf<IntegrityPacket?>(null) }
    // The file state the structure check read; null until it ran. Once the file on disk differs,
    // every result in this window is invalid and [fileChanged] stays true for this tab.
    var checkedIdentity by remember(tab) { mutableStateOf<ContentFileIdentity?>(null) }
    var fileChanged by remember(tab) { mutableStateOf(false) }
    fun cancelledMessage() = label("중단 · 검사 완료되지 않음", "Cancelled · inspection incomplete")

    val type = tab.type
    val motionDetected = remember(tab.root, type) { type == MediaType.IMAGE && tab.root?.let(::hasMotionPhotoData) == true }
    val gainmapDetected = type == MediaType.IMAGE && tab.gainmapInfo?.hasGainmap == true
    val tabs = contentTabs(type, motionDetected, gainmapDetected)
    var gainmapChecks by remember(tab) { mutableStateOf<List<IntegrityCheckItem>?>(null) }
    var stageStates by remember(tab) { mutableStateOf<Map<CheckStage, CheckStageState>>(emptyMap()) }
    val stageSpecs = contentCheckStages(type, tab.root, motionDetected, gainmapDetected,
        type == MediaType.AUDIO || tab.mediaSummary?.sections?.any { it.title == "Audio" } == true,
        codecRunnerAvailable = false)
    val steps = heavySteps(type, motionDetected)
    val targetJob = job
    DisposableEffect(tab, targetJob) { onDispose { targetJob?.cancel() } }

    // Publish the current run's results to the tab for the AI prompt. These states are only ever
    // written by the current run (every write is behind the runId/current() guard), so a stale or
    // cancelled run can never reach the tab through here.
    LaunchedEffect(tab, structure, audioStructure, conformance, structureError, imageDecode, imageDecodeError, motion, motionError, video, videoError, audio, audioError, running, motionDetected, type, checkedIdentity, stageStates) {
        tab.contentCheck = ContentCheckSnapshot(
            structure, imageDecode, motionDetected, motion, motionError, video, type,
            structureError = structureError, imageDecodeError = imageDecodeError, videoError = videoError, running = running,
            audio = audio, audioError = audioError,
            audioStructure = audioStructure,
            conformance = conformance,
            fileIdentity = checkedIdentity,
            stages = stageStates,
        )
    }

    fun fileChangedMessage() = label(
        "검사 후 파일이 변경되었습니다. 이전 결과는 무효이며, 파일을 다시 열어 검사해야 합니다.",
        "The file changed after it was checked. Previous results are invalid; reopen the file to check it again.",
    )

    /** Drops every result bound to the old bytes; the conformance verdict falls back to an inspection error. */
    fun invalidateForFileChange() {
        job?.cancel()
        runId++
        running = false
        fileChanged = true
        checkedIdentity = null
        structure = null
        audioStructure = null
        imageDecode = null
        imageDecodeError = null
        motion = null
        motionError = null
        video = null
        videoError = null
        audio = null
        audioError = null
        gainmapChecks = null
        stageStates = emptyMap()
        expandedDiagnostic = null
        selectedPacket = null
        structureError = fileChangedMessage()
        conformance = contentConformanceReport(type, inspectionError = structureError)
        message = fileChangedMessage()
    }

    // Size/mtime poll off the UI thread (Windows: no blocking stat on the UI thread).
    LaunchedEffect(tab, checkedIdentity) {
        val expected = checkedIdentity ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            if (!withContext(Dispatchers.IO) { expected.matches(tab.file) }) {
                invalidateForFileChange()
                break
            }
        }
    }

    // The tree is ordinary viewer state; Content Check does not inspect it until Run is pressed.
    suspend fun runStructure(root: com.multiviewer.parser.BoxNode, captured: ContentFileIdentity) {
        if (tab.parsedIdentity != null && tab.parsedIdentity != captured) throw ContentFileChangedException()
        val result = runInterruptible(Dispatchers.IO) {
            if (type == MediaType.AUDIO) {
                val checked = contentAudioStructureReport(tab.file, root)
                null to checked
            } else {
                val checked = contentStructureReport(tab.file, root, type)
                checked to null
            }
        }
        if (!withContext(Dispatchers.IO) { captured.matches(tab.file) }) throw ContentFileChangedException()
        structure = result.first
        audioStructure = result.second
        conformance = contentConformanceReport(type, structure = result.first, audioStructure = result.second)
        checkedIdentity = captured
        fileLength = captured.length
    }

    fun startChecks(requested: List<CheckStage>) {
        if (running || fileChanged || tab.root == null || requested.isEmpty()) return
        val runStages = requested.filter { stage -> stageSpecs.any { it.stage == stage && it.availability == CheckAvailability.AVAILABLE } }
        if (runStages.isEmpty()) return
        val myRun = ++runId
        running = true
        job = scope.launch {
            fun current() = isActive && myRun == runId
            try {
                val root = tab.root ?: return@launch
                val identity = withContext(Dispatchers.IO) { ContentFileIdentity.capture(tab.file) }
                if (tab.parsedIdentity != null && tab.parsedIdentity != identity) throw ContentFileChangedException()
                if (checkedIdentity != null && checkedIdentity != identity) throw ContentFileChangedException()
                val runner = ContentCheckRunner { stage, progress ->
                    when (stage) {
                        CheckStage.STRUCTURE -> runStructure(root, identity)
                        CheckStage.CODEC -> error("Codec checker unavailable")
                        CheckStage.VISUAL_DECODE -> {
                            if (type == MediaType.IMAGE) {
                                if (structure == null) runStructure(root, identity)
                                imageDecode = inspectImageDecode(tab.file, structure ?: error("Structure unavailable"))
                            } else {
                                video = inspectVideoIntegrity(tab.file) { phase, count ->
                                    if (current() && count % 20 == 0L) progress(CheckProgress(count, null,
                                        if (phase == "packets") "packets" else "frames"))
                                }
                            }
                        }
                        CheckStage.AUDIO_DECODE -> audio = inspectAudioIntegrity(tab.file)
                        CheckStage.MOTION_PHOTO -> motion = runInterruptible(Dispatchers.IO) {
                            MotionPhotoIntegrityAnalyzer.analyze(tab.file, root)
                        }
                        CheckStage.GAINMAP -> {
                            val info = tab.gainmapInfo ?: error("Gain map metadata unavailable")
                            val bitmap = tab.imageForensic?.bitmap
                            val coded = bitmap?.let { codedSize(it.width, it.height, tab.imageForensic?.orientationCode) }
                            gainmapChecks = runInterruptible(Dispatchers.IO) {
                                GainmapValidator.validate(tab.file, root, info, coded?.first, coded?.second)
                            }
                        }
                    }
                    if (!withContext(Dispatchers.IO) { identity.matches(tab.file) }) throw ContentFileChangedException()
                }
                runner.run(runStages) { stage, state ->
                    if (current()) {
                        stageStates = stageStates + (stage to state)
                        if (state.status == CheckRunStatus.RUNNING && state.progress == null) {
                            selectedStage = nextSelectedStage(selectedStage, manuallySelectedStage, stage)
                            if (!manuallySelectedStage) selectedTab = checkStageDetailTab(selectedStage, type)
                        }
                    }
                }
                if (current()) message = label("검사 완료", "Checks complete")
            } catch (e: ContentFileChangedException) {
                if (myRun == runId) invalidateForFileChange()
            } catch (e: CancellationException) {
                if (myRun == runId) message = cancelledMessage()
                throw e
            } finally {
                if (myRun == runId) running = false
            }
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
            val treePending = tab.root == null && !fileChanged
            Text(tab.file.name, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(label("유형: ", "Type: ") + type.name, color = AppColors.TextSecondary)
                if (type == MediaType.IMAGE) {
                    Text(label("형식: ", "Format: ") + (s?.format ?: "…"), color = AppColors.TextSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val actions = contentCheckActionState(type, stageSpecs.any { it.availability == CheckAvailability.AVAILABLE }, true, tab.root != null,
                    fileChanged, running, saving)
                Button(enabled = actions.canRun && tab.root != null, onClick = {
                    startChecks(stageSpecs.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage })
                }) { Text(label("모든 검사 실행", "Run all checks")) }
                if (actions.canCancel) OutlinedButton(onClick = {
                    job?.cancel()
                    running = false
                    message = cancelledMessage()
                }) { Text(label("취소", "Cancel")) }
                Box {
                    OutlinedButton(enabled = actions.canExport, onClick = { exportMenuExpanded = true }) {
                        Text(label("결과 내보내기", "Export results"))
                    }
                    DropdownMenu(expanded = exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text(label("JSON 복사", "Copy JSON")) }, onClick = {
                            exportMenuExpanded = false
                            val root = tab.root ?: return@DropdownMenuItem
                            val structureSnapshot = s
                            val decodeSnapshot = imageDecode
                            val motionSnapshot = motion
                            val motionErrorSnapshot = motionError
                            val videoSnapshot = video
                            val audioSnapshot = audio
                            scope.launch {
                                val copiedText = label("복사됨", "Copied")
                                message = try {
                                    val json = withContext(Dispatchers.Default) {
                                        buildCheckJson(
                                            tab.file, collectWarnings(root), videoSnapshot,
                                            imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                            audioSnapshot,
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
                        }, enabled = actions.canExport)
                        DropdownMenuItem(text = { Text(label("분석 케이스 저장", "Save analysis case")) }, onClick = {
                            exportMenuExpanded = false
                            val root = tab.root ?: return@DropdownMenuItem
                            val dialog = FileDialog(null as Frame?, label("분석 케이스 저장", "Save analysis case"), FileDialog.SAVE)
                            dialog.file = "${tab.file.nameWithoutExtension}-case.json"
                            dialog.isVisible = true
                            val output = dialog.file?.let { File(dialog.directory, it) }
                            dialog.dispose()
                            if (output == null) return@DropdownMenuItem
                            val structureSnapshot = s
                            val decodeSnapshot = imageDecode
                            val motionSnapshot = motion
                            val motionErrorSnapshot = motionError
                            val videoSnapshot = video
                            val audioSnapshot = audio
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val caseJson = buildAnalysisCaseJson(
                                            tab.file, collectWarnings(root), buildMediaSummary(root, tab.file),
                                            integrityReport = videoSnapshot,
                                            imageIntegrity = imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                            audioIntegrity = audioSnapshot,
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
                        }, enabled = actions.canExport)
                    }
                }
                OutlinedButton(enabled = actions.canDiagnose, onClick = onAiDiagnosis) { Text(label("AI 진단", "AI diagnosis")) }
            }
            if (message.isNotEmpty()) Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label("규격 검사", "Conformance checks"), color = AppColors.TextPrimary)
                    stageSpecs.forEach { spec ->
                        if (spec.stage == CheckStage.VISUAL_DECODE) {
                            Text(label("동작 진단", "Operation checks"), color = AppColors.TextPrimary)
                        }
                        if (spec.stage == CheckStage.MOTION_PHOTO) {
                            Text(label("확장 검사", "Additional checks"), color = AppColors.TextPrimary)
                        }
                        val state = stageStates[spec.stage] ?: CheckStageState(CheckRunStatus.NOT_RUN)
                        Column(Modifier.fillMaxWidth().background(AppColors.Surface).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = {
                                    selectedStage = spec.stage
                                    manuallySelectedStage = true
                                    selectedTab = checkStageDetailTab(spec.stage, type)
                                }, modifier = Modifier.weight(1f)) {
                                    Text(checkStageLabel(spec.stage, ko), fontSize = 12.sp)
                                }
                                if (spec.availability == CheckAvailability.AVAILABLE) {
                                    OutlinedButton(enabled = !running && !fileChanged, onClick = { startChecks(listOf(spec.stage)) }) {
                                        Text(checkStageActionLabel(state.status, ko), fontSize = 11.sp)
                                    }
                                }
                            }
                            Text(checkStageDescription(spec.stage, ko), color = AppColors.TextSecondary, fontSize = 11.sp)
                            val status = when (spec.availability) {
                                CheckAvailability.UNSUPPORTED -> label("미지원", "Unsupported")
                                CheckAvailability.NOT_APPLICABLE -> label("해당 없음", "Not applicable")
                                CheckAvailability.LOADING -> label("파일 구조 불러오는 중", "Loading file structure")
                                CheckAvailability.AVAILABLE -> checkRunStatusLabel(state.status, ko)
                            }
                            Text(status + (state.reason?.let { " · $it" } ?: ""), color = AppColors.TextSecondary, fontSize = 11.sp)
                            if (state.status == CheckRunStatus.COMPLETED || state.status == CheckRunStatus.PARTIAL) {
                                val verdict = when (spec.stage) {
                                    CheckStage.STRUCTURE -> if (type == MediaType.AUDIO) audioStructure?.overall?.let { checkStatusLabel(it, ko) }
                                        else structure?.overall?.let { checkStatusLabel(it, ko) }
                                    CheckStage.CODEC -> conformance?.overall?.let { conformanceStatusLabel(it, ko) }
                                    CheckStage.VISUAL_DECODE -> if (type == MediaType.IMAGE) imageDecode?.status?.let { headerDecodeLabel(true, it, ko) }
                                        else video?.decodeStatus?.let { videoStatusLabel(it, ko) }
                                    CheckStage.AUDIO_DECODE -> audio?.status?.let { videoStatusLabel(it, ko) }
                                    CheckStage.MOTION_PHOTO -> motion?.verdictStatus()?.let { checkStatusLabel(it, ko) }
                                    CheckStage.GAINMAP -> gainmapChecks?.let { checkStatusLabel(gainmapOverall(it), ko) }
                                }
                                if (verdict != null) Text(verdict, color = AppColors.TextPrimary, fontSize = 11.sp)
                            }
                            if (state.status == CheckRunStatus.RUNNING) {
                                val progress = state.progress
                                if (progress?.total != null && progress.total > 0) {
                                    LinearProgressIndicator(progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth())
                                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                if (progress != null) Text(checkProgressLabel(progress), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(checkStageLabel(selectedStage, ko), color = AppColors.TextPrimary)
                        OutlinedButton(onClick = { selectedTab = ContentTab.CONFORMANCE; manuallySelectedStage = true }) {
                            Text(label("규격 결과", "Rule results"))
                        }
                        if (selectedTab == ContentTab.VIDEO_PACKETS) {
                            OutlinedButton(onClick = { selectedTab = ContentTab.VIDEO_DECODE }) {
                                Text(label("디코딩 결과", "Decode results"))
                            }
                        }
                    }
                    val shownTab = selectedTab
            when (shownTab) {
                ContentTab.CONFORMANCE -> ConformancePanel(conformance, language) { offset, length ->
                    if (offset >= 0 && length > 0 && offset < fileLength && length <= fileLength - offset) {
                        tab.parameterSetHighlightRange = offset..(offset + (length - 1))
                    }
                }
                ContentTab.AUDIO -> AudioCheckPanel(audio, audioError, ko, Modifier.weight(1f))
                ContentTab.STRUCTURE -> if (type == MediaType.AUDIO) {
                    AudioStructurePanel(audioStructure, structureError, ko, fileLength) { offset, length ->
                        tab.parameterSetHighlightRange = offset until (offset + length)
                    }
                } else ImageStructurePanel(s, structureError, ko, fileLength) { offset, length ->
                    tab.parameterSetHighlightRange = offset until (offset + length)
                }
                ContentTab.IMAGE_DECODE -> ImageDecodePanel(imageDecode, ko)
                ContentTab.MOTION_PHOTO -> {
                    OutlinedButton(onClick = onOpenMotionPhotoInspector) {
                        Text(label("모션포토 상세 분석", "Open motion photo inspector"))
                    }
                    val m = motion
                    if (m == null && motionError != null) {
                        Text(label("모션포토 분석 실패: ", "Motion photo analysis failed: ") + motionError, color = AppColors.NeonRed)
                    } else if (m == null) {
                        Text(
                            contentCheckStartHint(ContentTab.MOTION_PHOTO, ko),
                            color = AppColors.TextSecondary,
                        )
                    } else {
                        ContentFindingTable(motionPhotoFindings(m), fileLength, ko, Modifier.fillMaxSize()) { offset, length ->
                            tab.parameterSetHighlightRange = offset until (offset + length)
                        }
                    }
                }
                ContentTab.GAINMAP -> {
                    val checks = gainmapChecks
                    if (checks == null) {
                        Text(label("게인맵 검사 중...", "Checking gain map..."), color = AppColors.TextSecondary)
                    } else {
                        ContentFindingTable(gainmapFindings(checks), fileLength, ko, Modifier.fillMaxSize()) { offset, length ->
                            tab.parameterSetHighlightRange = offset until (offset + length)
                        }
                    }
                }
                ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS -> {
                    if (v == null) {
                        Text(
                            contentCheckStartHint(shownTab, ko),
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
    }
}
