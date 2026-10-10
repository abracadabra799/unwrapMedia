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
    var selectedPacket by remember(tab.file) { mutableStateOf<IntegrityPacket?>(null) }
    var expandedDiagnostic by remember(report) { mutableStateOf<Int?>(null) }
    var saveMessage by remember(tab.file) { mutableStateOf("") }
    var saving by remember(tab.file) { mutableStateOf(false) }
    DisposableEffect(tab.file) { onDispose { job?.cancel() } }
    fun statusLabel(value: IntegrityStatus): String = when (value) {
        IntegrityStatus.NOT_RUN -> label("미검사", "Not inspected")
        IntegrityStatus.CLEAN -> label("완료 · 오류 없음", "Completed · no errors")
        IntegrityStatus.ISSUES -> label("완료 · 오류 발견", "Completed · errors found")
        IntegrityStatus.FAILED -> label("검사 실패 · 정상 여부 확인 불가", "Failed · integrity unconfirmed")
    }
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
                    running = true; report = null; selectedPacket = null; saveMessage = ""
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
                    Text(statusLabel(current.decodeStatus) + label(" · 디코딩된 프레임: ", " · Decoded frames: ") + current.decodedFrames,
                        color = AppColors.TextPrimary)
                    Text(label("오류 로그만으로 정확한 패킷 위치를 확정할 수 없습니다. 패킷 탭에서 시간과 바이트 위치를 확인하세요.",
                        "Logs do not establish exact error packet positions. Inspect timestamps and byte positions in the packet tab."),
                        color = AppColors.TextSecondary)
                    if (current.logsTruncated) Text(label("로그 저장 한도에 도달했습니다. 일부 로그는 생략됩니다.", "Log limit reached; some messages omitted."))
                    val diagnostics = remember(current) { current.logs.map(::explainIntegrityLog) }
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(diagnostics) { index, diagnostic ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(diagnostic.message, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary)
                                    Text(label("위치 미확인 · 원인 해설은 추정입니다", "Location unavailable · explanation is a hypothesis"), color = AppColors.TextSecondary)
                                    TextButton(onClick = { expandedDiagnostic = if (expandedDiagnostic == index) null else index }) {
                                        Text(label("오류 해설: ", "Explanation (Korean): ") + diagnostic.explanation.title)
                                    }
                                    if (expandedDiagnostic == index) {
                                        val explanation = diagnostic.explanation
                                        Text(label("해석: ", "Interpretation: ") + explanation.summary, color = AppColors.TextPrimary)
                                        Text(label("가능한 원인: ", "Possible cause: ") + explanation.probableCause, color = AppColors.TextPrimary)
                                        Text(label("가능한 영향: ", "Possible impact: ") + explanation.visualImpact, color = AppColors.TextPrimary)
                                        Text(label("추가 확인: ", "Next checks: ") + explanation.actionableFix, color = AppColors.TextPrimary)
                                        TextButton(onClick = { selectedTab = 1 }) { Text(label("패킷 목록에서 직접 확인", "Inspect packet list manually")) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(statusLabel(current.packetStatus) + " · ${current.packets.size}" + if (current.packetsTruncated) " (limited to 100,000)" else "",
                        color = AppColors.TextPrimary)
                    Text(label("패킷을 선택하면 기존 Hex 뷰어에서 해당 범위를 강조합니다. N/A는 정보 없음입니다.",
                        "Select a packet to highlight its bytes in the main Hex viewer. N/A means unavailable."), color = AppColors.TextSecondary)
                    Text("#     PTS(s)          DTS(s)          Offset         Bytes       Key", fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                    LazyColumn(Modifier.weight(1f)) {
                        items(current.packetLogs) { Text(it, color = AppColors.NeonRed) }
                        items(current.packets, key = { it.index }) { packet ->
                            Text("${packet.index.toString().padEnd(6)}${(packet.pts ?: "N/A").padEnd(16)}${(packet.dts ?: "N/A").padEnd(16)}${(packet.offset?.toString() ?: "N/A").padEnd(15)}${(packet.size?.toString() ?: "N/A").padEnd(12)}${if (packet.keyframe) "K" else ""}",
                                fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary,
                                modifier = Modifier.fillMaxWidth().background(if (selectedPacket == packet) AppColors.Selection else AppColors.Background)
                                    .clickable {
                                        selectedPacket = packet
                                        val offset = packet.offset
                                        val size = packet.size
                                        if (offset != null && size != null && offset < tab.file.length() && size <= tab.file.length() - offset) {
                                            tab.parameterSetHighlightRange = offset until (offset + size)
                                        }
                                    }.padding(vertical = 5.dp))
                        }
                    }
                }
            }
        }
    }
}
