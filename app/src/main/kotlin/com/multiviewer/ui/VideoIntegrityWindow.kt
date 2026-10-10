package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.CheckResult
import com.multiviewer.cli.checkFile
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Composable
fun VideoIntegrityWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    var job by remember(tab.file) { mutableStateOf<Job?>(null) }
    var running by remember(tab.file) { mutableStateOf(false) }
    var report by remember(tab.file) { mutableStateOf<VideoIntegrityReport?>(null) }
    var status by remember(tab.file) { mutableStateOf(label("미검사", "Not inspected")) }
    var selectedTab by remember { mutableStateOf(0) }
    var saveMessage by remember(tab.file) { mutableStateOf("") }
    var saving by remember(tab.file) { mutableStateOf(false) }
    DisposableEffect(tab.file) { onDispose { job?.cancel() } }
    Window(onCloseRequest = { job?.cancel(); onCloseRequest() },
        state = rememberWindowState(width = 1080.dp, height = 760.dp),
        title = label("영상 무결성 검사", "Video Integrity") + " — ${tab.file.name}") {
        Column(Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tab.file.name, color = AppColors.TextPrimary)
            Text(label("첫 번째 영상 트랙을 소프트웨어로 디코딩합니다. 오디오 검사는 포함하지 않습니다.",
                "Software decoding of the first video track. Audio is not inspected."), color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running && !saving, onClick = {
                    running = true; report = null; saveMessage = ""
                    job = scope.launch {
                        try {
                            report = inspectVideoIntegrity(tab.file) { phase, count ->
                                scope.launch progress@{
                                    if (!running || job?.isActive != true) return@progress
                                    status = if (phase == "packets") label("패킷 읽는 중: $count", "Reading packets: $count")
                                        else label("디코딩 중: $count 프레임", "Decoding: $count frames")
                                }
                            }
                            status = label("검사 종료 — 아래 단계별 결과를 확인하세요", "Inspection finished — see results below")
                        } catch (e: CancellationException) {
                            status = label("중단 · 검사 완료되지 않음", "Cancelled · inspection incomplete")
                            throw e
                        } catch (e: Exception) {
                            status = label("검사 실패: ", "Inspection failed: ") + e.message
                        } finally { running = false }
                    }
                }) { Text(label("검사 시작", "Start inspection")) }
                OutlinedButton(enabled = running, onClick = { job?.cancel() }) { Text(label("취소", "Cancel")) }
                OutlinedButton(enabled = report != null && !running && !saving, onClick = {
                    val snapshot = report ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, label("분석 케이스 저장", "Save analysis case"), FileDialog.SAVE)
                    dialog.file = "${tab.file.nameWithoutExtension}-case.json"
                    dialog.isVisible = true
                    val output = dialog.file?.let { File(dialog.directory, it) }
                    dialog.dispose()
                    if (output != null) scope.launch {
                        saving = true
                        try {
                            withContext(Dispatchers.IO) {
                                val result = checkFile(tab.file, includeCase = true, integrityReport = snapshot)
                                check(result is CheckResult.Success) { (result as CheckResult.Failure).message }
                                Files.writeString(output.toPath(), result.analysisCaseJson!!,
                                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                            }
                            saveMessage = label("저장됨: ", "Saved: ") + output.name
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { saveMessage = label("저장 실패 (기존 파일 덮어쓰기 불가): ", "Save failed (existing files are not overwritten): ") + e.message
                        } finally { saving = false }
                    }
                }) { Text(label("분석 케이스 저장", "Save analysis case")) }
            }
            if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, color = AppColors.TextPrimary)
            if (saveMessage.isNotEmpty()) Text(saveMessage, color = AppColors.TextSecondary)
            TabRow(selectedTabIndex = selectedTab) {
                listOf(label("디코딩 검사", "Decode inspection"), label("패킷 매핑", "Packet mapping")).forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                }
            }
            val current = report
            if (current != null) {
                if (selectedTab == 0) {
                    VideoDecodePanel(current, ko, onShowPackets = { selectedTab = 1 }, modifier = Modifier.weight(1f))
                } else {
                    VideoPacketPanel(current, tab, ko, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
