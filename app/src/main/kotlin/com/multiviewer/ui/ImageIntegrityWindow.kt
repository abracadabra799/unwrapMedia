package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.buildImageIntegrityCaseJson
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
private fun checkStatusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> AppColors.NeonGreen
    CheckStatus.INFO -> AppColors.NeonBlue
    CheckStatus.WARN -> AppColors.NeonYellow
    CheckStatus.FAIL -> AppColors.NeonRed
    CheckStatus.SKIP -> AppColors.TextMuted
}

@Composable
private fun decodeStatusColor(status: ImageDecodeStatus): Color = when (status) {
    ImageDecodeStatus.NOT_RUN -> AppColors.TextMuted
    ImageDecodeStatus.CLEAN -> AppColors.NeonGreen
    ImageDecodeStatus.ISSUES -> AppColors.NeonYellow
    ImageDecodeStatus.FAILED -> AppColors.NeonRed
}

private fun checkStatusLabel(status: CheckStatus, ko: Boolean): String = when (status) {
    CheckStatus.PASS -> if (ko) "정상" else "Pass"
    CheckStatus.INFO -> if (ko) "참고" else "Info"
    CheckStatus.WARN -> if (ko) "경고" else "Warning"
    CheckStatus.FAIL -> if (ko) "실패" else "Fail"
    CheckStatus.SKIP -> if (ko) "해당 없음" else "Skipped"
}

private fun decodeStatusLabel(status: ImageDecodeStatus, ko: Boolean): String = when (status) {
    ImageDecodeStatus.NOT_RUN -> if (ko) "미검사" else "Not run"
    ImageDecodeStatus.CLEAN -> if (ko) "완료 · 오류 없음" else "Completed · no errors"
    ImageDecodeStatus.ISSUES -> if (ko) "완료 · 오류 발견" else "Completed · errors found"
    ImageDecodeStatus.FAILED -> if (ko) "실패 · 정상 여부 확인 불가" else "Failed · integrity unconfirmed"
}

/** A finished run that ended NOT_RUN (e.g. RAW without a preview) is "not verifiable", not "never started". */
internal fun headerDecodeLabel(reportPresent: Boolean, status: ImageDecodeStatus, ko: Boolean): String =
    if (reportPresent && status == ImageDecodeStatus.NOT_RUN) { if (ko) "검증 불가" else "Not verifiable" }
    else decodeStatusLabel(status, ko)

@Composable
fun ImageIntegrityWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    var structure by remember(tab.file) { mutableStateOf<ImageStructureReport?>(null) }
    var structureError by remember(tab.file) { mutableStateOf<String?>(null) }
    var decode by remember(tab.file) { mutableStateOf<ImageDecodeReport?>(null) }
    var job by remember(tab.file) { mutableStateOf<Job?>(null) }
    var running by remember(tab.file) { mutableStateOf(false) }
    var runId by remember(tab.file) { mutableStateOf(0) }
    fun cancelledMessage() = label("중단 · 검사 완료되지 않음", "Cancelled · inspection incomplete")
    var message by remember(tab.file) { mutableStateOf("") }
    var motion by remember(tab.file) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }
    var motionError by remember(tab.file) { mutableStateOf<String?>(null) }
    val motionDetected = remember(tab.root) { tab.root?.let { hasMotionPhotoData(it) } ?: false }
    var selectedTab by remember { mutableStateOf(0) }
    DisposableEffect(tab.file) { onDispose { job?.cancel() } }

    // Keyed on tab.root too: the window can open before the file's structure tree finishes loading.
    LaunchedEffect(tab.file, tab.root) {
        val root = tab.root
        if (root == null) {
            structureError = label("파일 구조가 아직 로드되지 않았습니다", "The file structure is not loaded yet")
            return@LaunchedEffect
        }
        structureError = null
        try {
            structure = withContext(Dispatchers.IO) { ImageIntegrityChecker.check(tab.file, root) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            structureError = e.message ?: e.toString()
        }
    }

    Window(
        onCloseRequest = { job?.cancel(); onCloseRequest() },
        state = rememberWindowState(width = 1000.dp, height = 740.dp),
        title = label("이미지 무결성 검사", "Image Integrity") + " — ${tab.file.name}",
    ) {
        Column(
            Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val s = structure
            Text(tab.file.name, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label("형식: ", "Format: ") + (s?.format ?: "…"), color = AppColors.TextSecondary)
                Text(
                    label("구조: ", "Structure: ") + (s?.overall?.let { checkStatusLabel(it, ko) } ?: "…"),
                    color = s?.overall?.let { checkStatusColor(it) } ?: AppColors.TextMuted,
                )
                val ds = decode?.status ?: ImageDecodeStatus.NOT_RUN
                Text(label("디코딩: ", "Decode: ") + headerDecodeLabel(decode != null, ds, ko), color = decodeStatusColor(ds))
                if (motionDetected) {
                    val m = motion
                    val mStatus = m?.verdictStatus()
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !running && s != null, onClick = {
                    val snapshot = s ?: return@Button
                    val myRun = ++runId
                    running = true
                    decode = null
                    motion = null
                    motionError = null
                    message = if (motionDetected) label("디코딩·모션포토 검사 중…", "Decoding and checking motion photo…") else label("디코딩 중…", "Decoding…")
                    job = scope.launch {
                        // A cancelled or superseded run must never write results/state for the current one.
                        fun current() = isActive && myRun == runId
                        try {
                            val decoded = inspectImageDecode(tab.file, snapshot)
                            if (!current()) return@launch
                            decode = decoded
                            val root = tab.root
                            if (motionDetected && root != null) {
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
                                        message = label("모션포토 분석 실패: ", "Motion photo analysis failed: ") + motionError
                                    }
                                }
                            }
                            if (!current()) return@launch
                            if (motionError == null) message = ""
                            selectedTab = 1
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
                OutlinedButton(enabled = s != null && !running, onClick = {
                    val snapshot = s ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, label("분석 케이스 저장", "Save analysis case"), FileDialog.SAVE)
                    dialog.file = "${tab.file.nameWithoutExtension}-image-integrity.json"
                    dialog.isVisible = true
                    val name = dialog.file ?: return@OutlinedButton
                    val target = File(dialog.directory, name)
                    val decodeSnapshot = decode
                    val motionSnapshot = motion
                    val motionErrorSnapshot = motionError
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) {
                                if (!target.createNewFile()) error(label("이미 존재하는 파일입니다", "File already exists"))
                                target.writeText(buildImageIntegrityCaseJson(tab.file, snapshot, decodeSnapshot, motionSnapshot, motionDetected, motionErrorSnapshot), Charsets.UTF_8)
                            }
                            label("저장됨: ", "Saved: ") + target.name
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            label("저장 실패: ", "Save failed: ") + (e.message ?: e.toString())
                        }
                    }
                }) { Text(label("분석 케이스 저장", "Save analysis case")) }
                Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(label("구조 검사", "Structure")) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(label("디코딩 검사", "Decode")) })
                if (motionDetected) {
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(label("모션포토", "Motion photo")) })
                }
            }
            when (selectedTab) {
                0 -> StructurePanel(s, structureError, ko) { item ->
                    val offset = item.offset ?: return@StructurePanel
                    tab.parameterSetHighlightRange = offset until offset + maxOf(1L, item.length ?: 1L)
                }
                1 -> DecodePanel(decode, ko)
                else -> {
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
            }
        }
    }
}

@Composable
private fun StructurePanel(report: ImageStructureReport?, error: String?, ko: Boolean, onSelect: (IntegrityCheckItem) -> Unit) {
    when {
        error != null -> Text(error, color = AppColors.NeonRed)
        report == null -> Text(if (ko) "구조 검사 중…" else "Checking structure…", color = AppColors.TextSecondary)
        else -> Column {
            Text(
                if (ko) "오프셋이 있는 항목을 클릭하면 Hex 뷰에서 해당 범위를 강조합니다." else "Click a row with an offset to highlight it in the Hex view.",
                color = AppColors.TextMuted, fontSize = 12.sp,
            )
            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(report.items) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.offset != null) { onSelect(item) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(checkStatusLabel(item.status, ko), color = checkStatusColor(item.status), modifier = Modifier.width(72.dp), fontSize = 13.sp)
                        Text(item.title, color = AppColors.TextPrimary, modifier = Modifier.width(220.dp), fontSize = 13.sp)
                        Text(
                            item.offset?.let { "0x%X".format(it) + (item.length?.let { l -> " +$l" } ?: "") } ?: "—",
                            color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.width(150.dp), fontSize = 12.sp,
                        )
                        Text(item.detail, color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodePanel(report: ImageDecodeReport?, ko: Boolean) {
    if (report == null) {
        Text(
            if (ko) "'검사 시작'을 눌러 FFmpeg/Skia 디코딩 검사를 실행하세요." else "Press 'Start inspection' to run the FFmpeg/Skia decode check.",
            color = AppColors.TextSecondary,
        )
        return
    }
    fun size(w: Int?, h: Int?) = if (w != null && h != null) "${w}x$h" else "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (ko) "대상: " else "Source: ") + report.source, color = AppColors.TextPrimary, fontSize = 13.sp)
        Text(
            "FFmpeg ${report.ffmpegVersion ?: "?"}: ${decodeStatusLabel(report.ffmpegStatus, ko)} · " +
                (if (ko) "프레임 " else "frames ") + report.decodedFrames + " · " + size(report.decodedWidth, report.decodedHeight),
            color = decodeStatusColor(report.ffmpegStatus), fontSize = 13.sp,
        )
        val skiaColor = when {
            !report.skia.attempted -> AppColors.TextMuted
            report.skia.ok -> AppColors.NeonGreen
            else -> AppColors.NeonYellow
        }
        Text("Skia: ${report.skia.detail}", color = skiaColor, fontSize = 13.sp)
        Text(
            (if (ko) "해상도: 선언 " else "Resolution: declared ") + size(report.declaredWidth, report.declaredHeight) +
                (if (ko) " / 디코딩 " else " / decoded ") + size(report.decodedWidth, report.decodedHeight) +
                " — " + checkStatusLabel(report.resolutionStatus, ko),
            color = checkStatusColor(report.resolutionStatus), fontSize = 13.sp,
        )
        Text(
            (if (ko) "오류 로그" else "Error log") + " (${report.logs.size}${if (report.logsTruncated) "+" else ""})",
            color = AppColors.TextSecondary, fontSize = 12.sp,
        )
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(AppColors.Panel).padding(8.dp)) {
                items(report.logs) { line -> Text(line, color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}
