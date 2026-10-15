package com.multiviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionItem
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionReport
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionSection
import com.multiviewer.parser.motionphoto.MotionPhotoInspectorEngine
import com.multiviewer.parser.parseFile
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MotionPhotoInspectorWindow(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()

    var report by remember(tab) { mutableStateOf<MotionPhotoInspectionReport?>(null) }
    var isRunning by remember(tab) { mutableStateOf(false) }
    var statusMessage by remember(tab) { mutableStateOf("") }
    val expandedSections = remember(tab) { mutableStateMapOf<String, Boolean>() }

    fun runInspection() {
        isRunning = true
        statusMessage = label("모션포토 종합 검사 중...", "Analyzing motion photo...")
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val root = tab.root ?: try { parseFile(tab.file) } catch (_: Exception) { null }
                if (root != null) {
                    MotionPhotoInspectorEngine.inspect(tab.file, root)
                } else null
            }
            report = res
            isRunning = false
            statusMessage = if (res != null) {
                // Initialize default expansion: expand sections with WARN or FAIL
                res.allSections.forEach { sec ->
                    if (expandedSections[sec.id] == null) {
                        expandedSections[sec.id] = (sec.status == CheckStatus.WARN || sec.status == CheckStatus.FAIL)
                    }
                }
                label("검사 완료", "Inspection completed")
            } else {
                label("파일 분석 실패", "Failed to parse file")
            }
        }
    }

    LaunchedEffect(tab.file) {
        runInspection()
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(width = 1120.dp, height = 780.dp),
        title = label("모션포토 종합 검사", "Motion Photo Inspector") + " — ${tab.file.name}",
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Compact Header Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // File Name & Format
                Text(
                    tab.file.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )

                report?.let { rep ->
                    Box(
                        modifier = Modifier
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            rep.formatLabel,
                            color = AppColors.NeonBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // Overall status badge
                    val overallColor = checkStatusColor(rep.overallStatus)
                    val overallText = when (rep.overallStatus) {
                        CheckStatus.PASS -> label("ALL PASS", "ALL PASS")
                        CheckStatus.WARN -> label("WARNING", "WARNING")
                        CheckStatus.FAIL -> label("CRITICAL / FAIL", "CRITICAL / FAIL")
                        else -> label("INFO", "INFO")
                    }
                    Box(
                        modifier = Modifier
                            .background(overallColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(1.dp, overallColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            overallText,
                            color = overallColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Compact AssistChip Action Buttons
                AssistChip(
                    onClick = { runInspection() },
                    enabled = !isRunning,
                    label = {
                        Text(
                            label("⟳ 재검사", "⟳ Re-check"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.NeonBlue,
                        )
                    },
                    modifier = Modifier.height(28.dp),
                )

                AssistChip(
                    onClick = {
                        val currentReport = report ?: return@AssistChip
                        val json = buildMotionPhotoInspectionJson(tab.file, currentReport)
                        if (ClipboardUtil.copyToClipboard(json)) {
                            statusMessage = label("JSON을 클립보드에 복사했습니다", "JSON copied to clipboard")
                            scope.launch {
                                delay(2000)
                                if (statusMessage.startsWith("JSON")) statusMessage = ""
                            }
                        }
                    },
                    enabled = report != null,
                    label = {
                        Text(
                            label("📋 JSON 복사", "📋 Copy JSON"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary,
                        )
                    },
                    modifier = Modifier.height(28.dp),
                )

                if (tab.embeddedVideo != null) {
                    AssistChip(
                        onClick = {
                            tab.isPrimaryImagePopupOpen = true
                        },
                        label = {
                            Text(
                                label("🎬 동영상 보기", "🎬 Play Video"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.NeonGreen,
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }

            // Progress bar and status line
            if (isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, color = AppColors.TextSecondary, fontSize = 11.sp)
            }

            // 5 Accordion Cards
            val currentReport = report
            if (currentReport != null) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(currentReport.allSections, key = { it.id }) { section ->
                        val isExpanded = expandedSections[section.id] ?: (section.status == CheckStatus.WARN || section.status == CheckStatus.FAIL)
                        InspectionAccordionCard(
                            section = section,
                            isExpanded = isExpanded,
                            onToggle = { expandedSections[section.id] = !isExpanded },
                            ko = ko,
                            onSelectRange = { offset, length ->
                                tab.parameterSetHighlightRange = offset until (offset + length)
                                statusMessage = label(
                                    "Hex 뷰어에서 0x${offset.toString(16)} (길이: $length) 바이트 범위를 선택했습니다",
                                    "Selected 0x${offset.toString(16)} (length: $length) in Hex viewer",
                                )
                            },
                        )
                    }
                }
            } else if (!isRunning) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label("모션포토 분석 데이터가 없습니다", "No motion photo analysis data"),
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectionAccordionCard(
    section: MotionPhotoInspectionSection,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    ko: Boolean,
    onSelectRange: (Long, Long) -> Unit,
) {
    val statusColor = checkStatusColor(section.status)
    val statusLabel = when (section.status) {
        CheckStatus.PASS -> if (ko) "PASS" else "PASS"
        CheckStatus.WARN -> if (ko) "WARN" else "WARN"
        CheckStatus.FAIL -> if (ko) "FAIL" else "FAIL"
        CheckStatus.INFO -> if (ko) "INFO" else "INFO"
        CheckStatus.SKIP -> if (ko) "SKIP" else "SKIP"
    }

    val icon = when (section.id) {
        "still_image" -> "🖼️"
        "format_xmp" -> "📜"
        "sef_integrity" -> "📁"
        "video_codec" -> "🎬"
        "av_sync" -> "⏱️"
        else -> "🔍"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, if (section.status == CheckStatus.FAIL) AppColors.NeonRed.copy(alpha = 0.5f) else AppColors.Panel, RoundedCornerShape(6.dp)),
    ) {
        // Section Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status Badge
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                    .border(1.dp, statusColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            // Title
            Text(
                "$icon ${if (ko) section.titleKo else section.titleEn}",
                color = AppColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.weight(1f))

            // Pass count summary
            Text(
                "${section.passCount}/${section.totalCount} ${if (ko) "통과" else "passed"}",
                color = if (section.passCount == section.totalCount) AppColors.NeonGreen else AppColors.TextSecondary,
                fontSize = 11.sp,
            )

            // Chevron
            Text(
                if (isExpanded) "▲" else "▼",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        // Expanded Table Content
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Background.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (ko) "상태" else "Status", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                    Text(if (ko) "검사 항목" else "Check Item", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(210.dp))
                    Text(if (ko) "스펙 / 기대치" else "Expected", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(180.dp))
                    Text(if (ko) "실제 파일값" else "Actual", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(180.dp))
                    Text(if (ko) "세부 내용" else "Detail", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }

                // Table Items
                section.items.forEachIndexed { idx, item ->
                    val rowBg = if (idx % 2 == 1) AppColors.Surface.copy(alpha = 0.4f) else Color.Transparent
                    val itemColor = checkStatusColor(item.status)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(vertical = 5.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Status
                        Text(
                            when (item.status) {
                                CheckStatus.PASS -> "PASS"
                                CheckStatus.WARN -> "WARN"
                                CheckStatus.FAIL -> "FAIL"
                                CheckStatus.INFO -> "INFO"
                                CheckStatus.SKIP -> "SKIP"
                            },
                            color = itemColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp),
                        )

                        // Label
                        Text(
                            item.label,
                            color = AppColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(210.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Expected
                        Text(
                            item.expected ?: "—",
                            color = AppColors.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.width(180.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Actual
                        Text(
                            item.actual ?: "—",
                            color = if (item.status == CheckStatus.PASS) AppColors.TextPrimary else itemColor,
                            fontSize = 11.sp,
                            modifier = Modifier.width(180.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Detail & Byte Range link
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.detail,
                                color = AppColors.TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            if (item.offset != null && item.length != null) {
                                Text(
                                    " [0x${item.offset.toString(16)}]",
                                    color = AppColors.NeonBlue,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clickable { onSelectRange(item.offset, item.length) }
                                        .padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun buildMotionPhotoInspectionJson(file: File, report: MotionPhotoInspectionReport): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"file\": \"").append(file.name).append("\",\n")
    sb.append("  \"format\": \"").append(report.formatLabel).append("\",\n")
    sb.append("  \"overallStatus\": \"").append(report.overallStatus.name).append("\",\n")
    sb.append("  \"sections\": [\n")
    report.allSections.forEachIndexed { sIdx, sec ->
        sb.append("    {\n")
        sb.append("      \"id\": \"").append(sec.id).append("\",\n")
        sb.append("      \"title\": \"").append(sec.titleEn).append("\",\n")
        sb.append("      \"status\": \"").append(sec.status.name).append("\",\n")
        sb.append("      \"items\": [\n")
        sec.items.forEachIndexed { iIdx, item ->
            sb.append("        {\n")
            sb.append("          \"id\": \"").append(item.id).append("\",\n")
            sb.append("          \"status\": \"").append(item.status.name).append("\",\n")
            sb.append("          \"label\": \"").append(item.label.replace("\"", "\\\"")).append("\",\n")
            sb.append("          \"expected\": ").append(item.expected?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null").append(",\n")
            sb.append("          \"actual\": ").append(item.actual?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null").append(",\n")
            sb.append("          \"detail\": \"").append(item.detail.replace("\"", "\\\"")).append("\",\n")
            sb.append("          \"offset\": ").append(item.offset ?: "null").append(",\n")
            sb.append("          \"length\": ").append(item.length ?: "null").append("\n")
            sb.append("        }").append(if (iIdx < sec.items.size - 1) "," else "").append("\n")
        }
        sb.append("      ]\n")
        sb.append("    }").append(if (sIdx < report.allSections.size - 1) "," else "").append("\n")
    }
    sb.append("  ]\n")
    sb.append("}")
    return sb.toString()
}
