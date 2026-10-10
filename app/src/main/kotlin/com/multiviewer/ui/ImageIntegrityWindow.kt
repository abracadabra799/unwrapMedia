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
                0 -> ImageStructurePanel(s, structureError, ko) { item ->
                    val offset = item.offset ?: return@ImageStructurePanel
                    tab.parameterSetHighlightRange = offset until offset + maxOf(1L, item.length ?: 1L)
                }
                1 -> ImageDecodePanel(decode, ko)
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
