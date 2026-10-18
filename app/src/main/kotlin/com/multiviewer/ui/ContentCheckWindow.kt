package com.multiviewer.ui

import com.multiviewer.parser.gainmapOverall
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
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
import com.multiviewer.parser.conformance.HevcBitstreamReport
import com.multiviewer.parser.conformance.AvcBitstreamReport
import com.multiviewer.parser.conformance.Av1BitstreamReport
import com.multiviewer.parser.conformance.Vp9BitstreamReport
import com.multiviewer.parser.conformance.ApvBitstreamReport
import com.multiviewer.parser.conformance.VvcBitstreamReport
import com.multiviewer.parser.conformance.AacBitstreamReport
import com.multiviewer.parser.conformance.OpusBitstreamReport
import com.multiviewer.parser.conformance.FlacBitstreamReport
import com.multiviewer.parser.conformance.analyzeHevcBitstream
import com.multiviewer.parser.conformance.analyzeAvcBitstream
import com.multiviewer.parser.conformance.analyzeAv1Bitstream
import com.multiviewer.parser.conformance.analyzeVp9Bitstream
import com.multiviewer.parser.conformance.analyzeApvBitstream
import com.multiviewer.parser.conformance.analyzeVvcBitstream
import com.multiviewer.parser.conformance.analyzeAacBitstream
import com.multiviewer.parser.conformance.analyzeOpusBitstream
import com.multiviewer.parser.conformance.analyzeFlacBitstream
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
    var hevcBitstream by remember(tab) { mutableStateOf<HevcBitstreamReport?>(null) }
    var avcBitstream by remember(tab) { mutableStateOf<AvcBitstreamReport?>(null) }
    var av1Bitstream by remember(tab) { mutableStateOf<Av1BitstreamReport?>(null) }
    var vp9Bitstream by remember(tab) { mutableStateOf<Vp9BitstreamReport?>(null) }
    var apvBitstream by remember(tab) { mutableStateOf<ApvBitstreamReport?>(null) }
    var vvcBitstream by remember(tab) { mutableStateOf<VvcBitstreamReport?>(null) }
    var aacBitstream by remember(tab) { mutableStateOf<AacBitstreamReport?>(null) }
    var opusBitstream by remember(tab) { mutableStateOf<OpusBitstreamReport?>(null) }
    var flacBitstream by remember(tab) { mutableStateOf<FlacBitstreamReport?>(null) }
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
    var selectedStage by remember(tab) { mutableStateOf(CheckStage.CONTAINER_CONFORMANCE) }
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
    var gainmapChecks by remember(tab) { mutableStateOf<List<IntegrityCheckItem>?>(null) }
    var stageStates by remember(tab) { mutableStateOf<Map<CheckStage, CheckStageState>>(emptyMap()) }
    val aacAvail = aacCodecAvailability(type, tab.root)
    val opusAvail = opusCodecAvailability(type, tab.root)
    val flacAvail = flacCodecAvailability(type, tab.root)
    val videoCodecAvail = videoCodecAvailability(type, tab.root)
    val stageSpecs = contentCheckStages(type, tab.root, motionDetected, gainmapDetected,
        type == MediaType.AUDIO || tab.mediaSummary?.sections?.any { it.title == "Audio" } == true,
        codecRunnerAvailable = (videoCodecAvail == CheckAvailability.AVAILABLE || aacAvail == CheckAvailability.AVAILABLE || opusAvail == CheckAvailability.AVAILABLE || flacAvail == CheckAvailability.AVAILABLE),
        aacCodecAvailable = (aacAvail == CheckAvailability.AVAILABLE),
        opusCodecAvailable = (opusAvail == CheckAvailability.AVAILABLE),
        flacCodecAvailable = (flacAvail == CheckAvailability.AVAILABLE))
    val targetJob = job
    DisposableEffect(tab, targetJob) { onDispose { targetJob?.cancel() } }

    // Publish the current run's results to the tab for the AI prompt. These states are only ever
    // written by the current run (every write is behind the runId/current() guard), so a stale or
    // cancelled run can never reach the tab through here.
    LaunchedEffect(tab, structure, audioStructure, conformance, hevcBitstream, avcBitstream, av1Bitstream, vp9Bitstream, apvBitstream, vvcBitstream, aacBitstream, opusBitstream, flacBitstream, structureError, imageDecode, imageDecodeError, motion, motionError, video, videoError, audio, audioError, running, motionDetected, type, checkedIdentity, stageStates) {
        tab.contentCheck = ContentCheckSnapshot(
            structure, imageDecode, motionDetected, motion, motionError, video, type,
            structureError = structureError, imageDecodeError = imageDecodeError, videoError = videoError, running = running,
            audio = audio, audioError = audioError,
            audioStructure = audioStructure,
            conformance = conformance,
            fileIdentity = checkedIdentity,
            stages = stageStates,
            hevcBitstream = hevcBitstream,
            avcBitstream = avcBitstream,
            av1Bitstream = av1Bitstream,
            vp9Bitstream = vp9Bitstream,
            apvBitstream = apvBitstream,
            vvcBitstream = vvcBitstream,
            aacBitstream = aacBitstream,
            opusBitstream = opusBitstream,
            flacBitstream = flacBitstream,
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
        hevcBitstream = null
        avcBitstream = null
        av1Bitstream = null
        vp9Bitstream = null
        apvBitstream = null
        vvcBitstream = null
        aacBitstream = null
        opusBitstream = null
        flacBitstream = null
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
                if (checkedIdentity == null) {
                    checkedIdentity = identity
                    fileLength = identity.length
                }
                val runner = ContentCheckRunner { stage, progress ->
                    when (stage) {
                        CheckStage.CONTAINER_CONFORMANCE -> {
                            if (structure == null && audioStructure == null) {
                                runStructure(root, identity)
                            } else {
                                conformance = contentConformanceReport(type, structure = structure, audioStructure = audioStructure)
                            }
                        }
                        CheckStage.STRUCTURE -> runStructure(root, identity)
                        CheckStage.CODEC -> {
                            if (type != MediaType.VIDEO && type != MediaType.AUDIO) error("Video or audio track unavailable")
                            val aacAvail = aacCodecAvailability(type, root)
                            val opusAvail = opusCodecAvailability(type, root)
                            val flacAvail = flacCodecAvailability(type, root)
                            val hevcAvail = hevcCodecAvailability(type, root)
                            val avcAvail = avcCodecAvailability(type, root)
                            val av1Avail = av1CodecAvailability(type, root)
                            val vp9Avail = vp9CodecAvailability(type, root)
                            val apvAvail = apvCodecAvailability(type, root)
                            val vvcAvail = vvcCodecAvailability(type, root)
                            when {
                                aacAvail == CheckAvailability.AVAILABLE -> {
                                    aacBitstream = analyzeAacBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                opusAvail == CheckAvailability.AVAILABLE -> {
                                    opusBitstream = analyzeOpusBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                flacAvail == CheckAvailability.AVAILABLE -> {
                                    flacBitstream = analyzeFlacBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                hevcAvail == CheckAvailability.AVAILABLE -> {
                                    hevcBitstream = analyzeHevcBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                avcAvail == CheckAvailability.AVAILABLE -> {
                                    avcBitstream = analyzeAvcBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                av1Avail == CheckAvailability.AVAILABLE -> {
                                    av1Bitstream = analyzeAv1Bitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                vp9Avail == CheckAvailability.AVAILABLE -> {
                                    vp9Bitstream = analyzeVp9Bitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                apvAvail == CheckAvailability.AVAILABLE -> {
                                    apvBitstream = analyzeApvBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                vvcAvail == CheckAvailability.AVAILABLE -> {
                                    vvcBitstream = analyzeVvcBitstream(tab.file, root) { checked, total ->
                                        if (checked % 20 == 0 || checked == total) scope.launch {
                                            if (current() && stageStates[CheckStage.CODEC]?.status == CheckRunStatus.RUNNING) {
                                                progress(CheckProgress(checked.toLong(), total?.toLong(), "samples"))
                                            }
                                        }
                                    }
                                }
                                else -> error("Supported HEVC, AVC, AV1, VP9, APV, VVC, or AAC configuration unavailable")
                            }
                        }
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
                    if (shouldPublishCheckState(myRun == runId, isActive, state.status)) {
                        val codecComplete = hevcBitstream?.complete ?: avcBitstream?.complete ?: av1Bitstream?.complete ?: vp9Bitstream?.complete ?: apvBitstream?.complete ?: vvcBitstream?.complete ?: aacBitstream?.complete ?: opusBitstream?.complete ?: flacBitstream?.complete
                        val shownState = if (stage == CheckStage.CODEC && state.status == CheckRunStatus.COMPLETED && codecComplete == false) {
                            state.copy(status = CheckRunStatus.PARTIAL, reason = "Only indexed, supported samples were checked / 일부 샘플만 검사")
                        } else state
                        stageStates = stageStates + (stage to shownState)
                        if (state.status == CheckRunStatus.RUNNING && state.progress == null) {
                            selectedStage = nextSelectedStage(selectedStage, manuallySelectedStage, stage)
                            if (!manuallySelectedStage) {
                                selectedTab = checkStageDetailTab(selectedStage, type)
                            }
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

    fun exportSnapshot() = ContentCheckSnapshot(
        structure, imageDecode, motionDetected, motion, motionError, video, type,
        structureError = structureError, imageDecodeError = imageDecodeError, videoError = videoError,
        running = running, audio = audio, audioError = audioError, audioStructure = audioStructure,
        conformance = conformance, fileIdentity = checkedIdentity, stages = stageStates,
        hevcBitstream = hevcBitstream,
        avcBitstream = avcBitstream,
        av1Bitstream = av1Bitstream,
        vp9Bitstream = vp9Bitstream,
        apvBitstream = apvBitstream,
        vvcBitstream = vvcBitstream,
        aacBitstream = aacBitstream,
        opusBitstream = opusBitstream,
        flacBitstream = flacBitstream,
    )

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
            val formatText = when (type) {
                MediaType.IMAGE -> s?.format ?: tab.file.extension.uppercase()
                MediaType.AUDIO -> audioStructure?.format ?: tab.file.extension.uppercase()
                else -> conformance?.format ?: tab.file.extension.uppercase()
            }
            val actions = contentCheckActionState(
                type,
                stageSpecs.any { it.availability == CheckAvailability.AVAILABLE },
                true,
                tab.root != null,
                fileChanged,
                running,
                saving,
            )
            val exportReady = actions.canExport && checkedIdentity != null

            // Compact Header: Title · Type · Format on the left, buttons on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        tab.file.name,
                        color = AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("·", color = AppColors.TextMuted, fontSize = 12.sp)
                    Text(
                        label("유형: ", "Type: ") + type.name,
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Text("·", color = AppColors.TextMuted, fontSize = 12.sp)
                    Text(
                        label("형식: ", "Format: ") + formatText,
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = actions.canRun && tab.root != null,
                        onClick = {
                            startChecks(stageSpecs.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage })
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(label("모든 검사 실행", "Run all checks"), fontSize = 11.5.sp)
                    }
                    if (actions.canCancel) {
                        OutlinedButton(
                            onClick = {
                                job?.cancel()
                                running = false
                                message = cancelledMessage()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(label("취소", "Cancel"), fontSize = 11.5.sp)
                        }
                    }
                    Box {
                        OutlinedButton(
                            enabled = exportReady,
                            onClick = { exportMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(label("결과 내보내기", "Export results"), fontSize = 11.5.sp)
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
                                val workflowSnapshot = exportSnapshot()
                                scope.launch {
                                    val copiedText = label("복사됨", "Copied")
                                    message = try {
                                        val json = withContext(Dispatchers.IO) {
                                            if (workflowSnapshot.fileIdentity?.matches(tab.file) != true) throw ContentFileChangedException()
                                            buildCheckJson(
                                                tab.file, collectWarnings(root), videoSnapshot,
                                                imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                                audioSnapshot,
                                                workflow = contentCheckWorkflowJson(workflowSnapshot, tab.file),
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
                            }, enabled = exportReady)
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
                                val workflowSnapshot = exportSnapshot()
                                saving = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            if (workflowSnapshot.fileIdentity?.matches(tab.file) != true) throw ContentFileChangedException()
                                            val caseJson = buildAnalysisCaseJson(
                                                tab.file, collectWarnings(root), buildMediaSummary(root, tab.file),
                                                integrityReport = videoSnapshot,
                                                imageIntegrity = imageJson(structureSnapshot, decodeSnapshot, motionSnapshot, motionErrorSnapshot),
                                                audioIntegrity = audioSnapshot,
                                                workflow = contentCheckWorkflowJson(workflowSnapshot, tab.file),
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
                            }, enabled = exportReady)
                        }
                    }
                    OutlinedButton(
                        enabled = actions.canDiagnose,
                        onClick = onAiDiagnosis,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(label("AI 진단", "AI diagnosis"), fontSize = 11.5.sp)
                    }
                }
            }
            if (message.isNotEmpty()) Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
            val overviewRows = remember(stageSpecs, stageStates, tab.root, tab.mediaSummary, ko, running, fileChanged, conformance, structure, audioStructure, hevcBitstream, avcBitstream, av1Bitstream, vp9Bitstream, apvBitstream, vvcBitstream, aacBitstream, opusBitstream, flacBitstream, imageDecode, video, audio, motion, gainmapChecks) {
                buildContentCheckOverviewRows(
                    specs = stageSpecs,
                    states = stageStates,
                    type = type,
                    root = tab.root,
                    summary = tab.mediaSummary,
                    ko = ko,
                    running = running,
                    fileChanged = fileChanged,
                ) { stage ->
                    when (stage) {
                        CheckStage.CONTAINER_CONFORMANCE -> conformance?.let { report ->
                            when (report.overall) {
                                ConformanceStatus.PASS -> label("규격 통과", "Conformance pass")
                                ConformanceStatus.FAIL -> label("오류 ${report.findings.count { it.status == ConformanceStatus.FAIL }}건", "${report.findings.count { it.status == ConformanceStatus.FAIL }} issues")
                                ConformanceStatus.WARN -> label("경고 ${report.findings.count { it.status == ConformanceStatus.WARN }}건", "${report.findings.count { it.status == ConformanceStatus.WARN }} warnings")
                                ConformanceStatus.NOT_CHECKED -> label("미검사", "Not checked")
                                ConformanceStatus.NOT_APPLICABLE -> label("해당 없음", "Not applicable")
                            }
                        }
                        CheckStage.STRUCTURE -> if (type == MediaType.AUDIO) audioStructure?.overall?.let { checkStatusLabel(it, ko) }
                            else structure?.overall?.let { checkStatusLabel(it, ko) }
                        CheckStage.CODEC -> {
                            val pair = hevcBitstream?.let { it.findings to it.complete }
                                ?: avcBitstream?.let { it.findings to it.complete }
                                ?: av1Bitstream?.let { it.findings to it.complete }
                                ?: vp9Bitstream?.let { it.findings to it.complete }
                                ?: apvBitstream?.let { it.findings to it.complete }
                                ?: vvcBitstream?.let { it.findings to it.complete }
                                ?: aacBitstream?.let { it.findings to it.complete }
                                ?: opusBitstream?.let { it.findings to it.complete }
                                ?: flacBitstream?.let { it.findings to it.complete }
                            pair?.let { (findings, complete) -> when {
                                findings.any { it.status == ConformanceStatus.FAIL } -> label("오류 관측", "Issues observed")
                                !complete -> label("일부 미검증", "Partly unchecked")
                                else -> label("바이트 검사 완료 · 규격 미검증", "Byte check complete · conformance unchecked")
                            } }
                        }
                        CheckStage.VISUAL_DECODE -> if (type == MediaType.IMAGE) imageDecode?.status?.let { headerDecodeLabel(true, it, ko) }
                            else video?.decodeStatus?.let { videoStatusLabel(it, ko) }
                        CheckStage.AUDIO_DECODE -> audio?.status?.let { videoStatusLabel(it, ko) }
                        CheckStage.MOTION_PHOTO -> motion?.verdictStatus()?.let { checkStatusLabel(it, ko) }
                        CheckStage.GAINMAP -> gainmapChecks?.let { checkStatusLabel(gainmapOverall(it), ko) }
                    }
                }
            }

            ContentCheckOverviewTable(
                rows = overviewRows,
                selectedStage = selectedStage,
                onSelectStage = { stage ->
                    selectedStage = stage
                    manuallySelectedStage = true
                    selectedTab = checkStageDetailTab(stage, type)
                },
                onRunStage = { stage ->
                    startChecks(listOf(stage))
                },
                ko = ko,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 220.dp),
            )

            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            checkStageLabel(selectedStage, ko),
                            color = AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (selectedStage == CheckStage.VISUAL_DECODE && type == MediaType.VIDEO) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { selectedTab = ContentTab.VIDEO_DECODE },
                                ) {
                                    Text(
                                        label("프레임 디코딩 결과", "Frame decode results"),
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedTab == ContentTab.VIDEO_DECODE) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedTab == ContentTab.VIDEO_DECODE) AppColors.NeonBlue else AppColors.TextPrimary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { selectedTab = ContentTab.VIDEO_PACKETS },
                                ) {
                                    Text(
                                        label("비디오 패킷 목록", "Video packet list"),
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedTab == ContentTab.VIDEO_PACKETS) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedTab == ContentTab.VIDEO_PACKETS) AppColors.NeonBlue else AppColors.TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                    val shownTab = selectedTab
                    when (shownTab) {
                        ContentTab.CODEC -> {
                            val codecData = when {
                                hevcBitstream != null -> Triple(hevcBitstream!!.checkedTrack, "${hevcBitstream!!.checkedSamples}/${hevcBitstream!!.indexedSamples}", hevcBitstream!!.limitations) to hevcBitstreamFindings(hevcBitstream!!)
                                avcBitstream != null -> Triple(avcBitstream!!.checkedTrack, "${avcBitstream!!.checkedSamples}/${avcBitstream!!.indexedSamples}", avcBitstream!!.limitations) to avcBitstreamFindings(avcBitstream!!)
                                av1Bitstream != null -> Triple(av1Bitstream!!.checkedTrack, "${av1Bitstream!!.checkedSamples}/${av1Bitstream!!.indexedSamples}", av1Bitstream!!.limitations) to av1BitstreamFindings(av1Bitstream!!)
                                vp9Bitstream != null -> Triple(vp9Bitstream!!.checkedTrack, "${vp9Bitstream!!.checkedSamples}/${vp9Bitstream!!.indexedSamples}", vp9Bitstream!!.limitations) to vp9BitstreamFindings(vp9Bitstream!!)
                                apvBitstream != null -> Triple(apvBitstream!!.checkedTrack, "${apvBitstream!!.checkedSamples}/${apvBitstream!!.indexedSamples}", apvBitstream!!.limitations) to apvBitstreamFindings(apvBitstream!!)
                                vvcBitstream != null -> Triple(vvcBitstream!!.checkedTrack, "${vvcBitstream!!.checkedSamples}/${vvcBitstream!!.indexedSamples}", vvcBitstream!!.limitations) to vvcBitstreamFindings(vvcBitstream!!)
                                aacBitstream != null -> Triple(aacBitstream!!.checkedTrack, "${aacBitstream!!.checkedSamples}/${aacBitstream!!.indexedSamples}", aacBitstream!!.limitations) to aacBitstreamFindings(aacBitstream!!)
                                opusBitstream != null -> Triple(opusBitstream!!.checkedTrack, "${opusBitstream!!.checkedSamples}/${opusBitstream!!.indexedSamples}", opusBitstream!!.limitations) to opusBitstreamFindings(opusBitstream!!)
                                flacBitstream != null -> Triple(flacBitstream!!.checkedTrack, "${flacBitstream!!.checkedSamples}/${flacBitstream!!.indexedSamples}", flacBitstream!!.limitations) to flacBitstreamFindings(flacBitstream!!)
                                else -> null
                            }
                            if (codecData == null) Text(label("코덱 검사 결과가 없습니다. 실행을 눌러 시작하세요.", "No codec result yet. Press Run to start."),
                                color = AppColors.TextSecondary)
                            else {
                                val (meta, findings) = codecData
                                val (track, samples, limitations) = meta
                                Text("$track · $samples samples", color = AppColors.TextSecondary)
                                Text(label("구조 관측값입니다. 전체 규격 준수나 MediaCodec 동작을 증명하지 않습니다.",
                                    "Structural observations; these do not establish full conformance or MediaCodec behavior."),
                                    color = AppColors.TextSecondary, fontSize = 12.sp)
                                limitations.take(8).forEach { Text(it, color = AppColors.TextSecondary, fontSize = 11.sp) }
                                ContentFindingTable(findings, fileLength, ko, Modifier.weight(1f)) { offset, length ->
                                    tab.parameterSetHighlightRange = offset until (offset + length)
                                }
                            }
                        }
                        ContentTab.CONFORMANCE -> {
                            val report = conformance
                            if (report == null) {
                                Text(label("규격 검사 준비 중 · 미검증", "Preparing conformance checks · not checked"), color = AppColors.TextMuted)
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${report.format} · ${report.profile}", color = AppColors.TextSecondary, fontSize = 12.sp)
                                        val effStatus = effectiveConformanceStatus(exportSnapshot()) ?: report.overall
                                        val statusText = conformanceStatusLabel(effStatus, ko)
                                        val statusColor = conformanceStatusColor(effStatus)
                                        Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    val findings = conformanceFindings(report)
                                    ContentFindingTable(findings, fileLength, ko, Modifier.weight(1f)) { offset, length ->
                                        if (offset >= 0 && length > 0 && offset < fileLength && length <= fileLength - offset) {
                                            tab.parameterSetHighlightRange = offset..(offset + (length - 1))
                                        }
                                    }
                                }
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
