package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.diagram.RegionStatus
import com.multiviewer.parser.diagram.StructureDiagram
import com.multiviewer.parser.diagram.buildStructureDiagram
import com.multiviewer.parser.diagram.nodeAtPath
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

private class RenderedDiagram(val layout: DiagramLayout, val image: BufferedImage, val bitmap: ImageBitmap)

/**
 * Analyze → 파일 구조도 / File Structure Diagram: the opened file's actual byte layout as a
 * not-to-scale diagram and table, exportable as PNG/SVG/Markdown. Pinned to the tab it was opened for;
 * clicking a row selects that node in the structure tree.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StructureDiagramWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    var depth by remember(tab) { mutableStateOf(1) }
    var showTable by remember(tab) { mutableStateOf(false) }
    var message by remember(tab) { mutableStateOf("") }
    var saving by remember(tab) { mutableStateOf(false) }
    // The diagram describes the tree parsed at open time: once the file differs, exports would be stale.
    var fileChanged by remember(tab) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tab, tab.parsedIdentity) {
        val parsed = tab.parsedIdentity ?: return@LaunchedEffect
        while (true) {
            fileChanged = withContext(Dispatchers.IO) { !tab.file.isFile || ContentFileIdentity.capture(tab.file) != parsed }
            kotlinx.coroutines.delay(2_000)
        }
    }
    val root = tab.root
    // Only image tabs carry a motion-photo video range; other types' embeddedVideo is not a still+video pair.
    val video = if (tab.type == MediaType.IMAGE) tab.embeddedVideo else null
    // File name only: shared output never carries the local path.
    val title = tab.file.name

    // A build/render failure (even an Error such as OutOfMemoryError on a hostile file) stays in this window.
    var failure by remember(tab) { mutableStateOf<String?>(null) }
    val diagram by produceState<StructureDiagram?>(null, tab, root, video) {
        value = null
        val r = root ?: return@produceState
        // The tree's own size (= parsed file size) avoids a stat on the UI thread.
        val fileSize = tab.parsedIdentity?.length ?: r.size
        try {
            value = withContext(Dispatchers.Default) { buildStructureDiagram(r, fileSize, video) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e.toString()
        }
    }
    val rendered by produceState<RenderedDiagram?>(null, diagram, depth) {
        value = null
        val d = diagram ?: return@produceState
        try {
            value = withContext(Dispatchers.Default) {
                val layout = layoutStructureDiagram(d, depth, title)
                val image = renderStructurePng(layout)
                RenderedDiagram(layout, image, image.toComposeImageBitmap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e.toString()
        }
    }
    val selectedRegionId = remember(diagram, tab.selected, root) {
        val d = diagram
        val selected = tab.selected
        if (d == null || selected == null || root == null) null
        else d.regions.firstOrNull { r -> r.nodePath?.let { nodeAtPath(root, it) } === selected }?.id
    }
    fun select(regionId: String) {
        val r = root ?: return
        val path = diagram?.regions?.firstOrNull { it.id == regionId }?.nodePath ?: return
        nodeAtPath(r, path)?.let { tab.selected = it }
    }

    fun save(extension: String, write: (File) -> Unit) {
        val dialog = FileDialog(null as Frame?, label("구조도 저장", "Save structure diagram"), FileDialog.SAVE)
        dialog.file = "${tab.file.nameWithoutExtension}-structure.$extension"
        dialog.isVisible = true
        val output = dialog.file?.let { File(dialog.directory, it) }
        dialog.dispose()
        if (output == null) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { write(output) }
                message = label("저장됨: ", "Saved: ") + output.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                message = label("저장하지 않음: 같은 이름의 파일이 이미 있습니다 (덮어쓰지 않음). 다른 이름을 고르세요.",
                    "Not saved: a file with that name already exists (never overwritten). Choose another name.")
            } catch (e: Exception) {
                message = label("저장 실패: ", "Save failed: ") + (e.message ?: e.toString())
            } finally {
                saving = false
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(width = 1080.dp, height = 820.dp),
        title = label("파일 구조도", "File Structure Diagram") + " — ${tab.file.name}",
    ) {
        Column(
            Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((value, name) in listOf(0 to label("개요", "Overview"), 1 to label("상세", "Detail"), 2 to label("세부", "Deep"))) {
                    if (depth == value && !showTable) Button(onClick = {}) { Text(name) }
                    else OutlinedButton(onClick = { depth = value; showTable = false }) { Text(name) }
                }
                if (showTable) Button(onClick = {}) { Text(label("표", "Table")) }
                else OutlinedButton(onClick = { showTable = true }) { Text(label("표", "Table")) }
                val r = rendered
                val d = diagram
                OutlinedButton(enabled = r != null && !fileChanged, onClick = {
                    val image = r?.image ?: return@OutlinedButton
                    message = if (ClipboardUtil.copyImage(image)) label("이미지를 클립보드에 복사했습니다", "Image copied to the clipboard")
                    else label("클립보드에 복사하지 못했습니다 (다른 앱이 사용 중일 수 있음)", "Could not copy to the clipboard (another app may be using it)")
                }) { Text(label("이미지 복사", "Copy image")) }
                OutlinedButton(enabled = d != null && !fileChanged, onClick = {
                    val table = d?.let(::structureDiagramMarkdownTable) ?: return@OutlinedButton
                    message = if (ClipboardUtil.copyToClipboard(table)) label("Markdown 표를 복사했습니다", "Markdown table copied")
                    else label("클립보드에 복사하지 못했습니다 (다른 앱이 사용 중일 수 있음)", "Could not copy to the clipboard (another app may be using it)")
                }) { Text(label("표 복사 (Markdown)", "Copy table (Markdown)")) }
                OutlinedButton(enabled = r != null && !saving && !fileChanged, onClick = {
                    val svg = r?.let { renderStructureSvg(it.layout) } ?: return@OutlinedButton
                    // CREATE_NEW: never overwrite an existing file.
                    save("svg") { Files.writeString(it.toPath(), svg, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
                }) { Text(label("SVG 저장", "Save SVG")) }
                OutlinedButton(enabled = r != null && !saving && !fileChanged, onClick = {
                    val image = r?.image ?: return@OutlinedButton
                    save("png") { file ->
                        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                            check(ImageIO.write(image, "png", out)) { "No PNG writer" }
                        }
                    }
                }) { Text(label("PNG 저장", "Save PNG")) }
            }
            Text(
                label(
                    "실제 파싱된 바이트 범위로 그린 비율 무관 구조도입니다. 점선은 어떤 노드도 설명하지 않는 바이트, 주황 테두리는 경고입니다. 행을 누르면 구조 트리에서 선택됩니다.",
                    "Not-to-scale map of the actual parsed byte ranges. Dashed rows are bytes no node accounts for; orange borders are warnings. Click a row to select it in the structure tree.",
                ),
                color = AppColors.TextSecondary, fontSize = 12.sp,
            )
            if (fileChanged) {
                Text(label("파일이 열린 뒤 변경되었습니다. 이 구조도는 이전 내용이므로 복사·저장을 막았습니다 — 파일을 다시 열어 주세요.",
                    "The file changed after it was opened. This diagram is out of date, so copy/save are disabled — reopen the file."),
                    color = AppColors.NeonOrange, fontSize = 12.sp)
            }
            val d = diagram
            if (d != null && d.warnings.isNotEmpty()) {
                Text(label("경고 ${d.warnings.size}개 — 아래 표/이미지 하단 참고", "${d.warnings.size} warning(s) — see the table or the image footer"),
                    color = AppColors.NeonOrange, fontSize = 12.sp)
            }
            if (message.isNotEmpty()) Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)

            Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.Panel)) {
                when {
                    failure != null -> Text(label("구조도를 만들지 못했습니다: ", "Could not build the diagram: ") + failure,
                        color = AppColors.NeonRed, modifier = Modifier.padding(16.dp))
                    root == null -> Text(label("파일 구조가 아직 로드되지 않았습니다", "The file structure is not loaded yet"),
                        color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                    d == null -> Text(label("구조도 생성 중…", "Building diagram…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                    showTable -> DiagramTable(d, selectedRegionId, ko, ::select)
                    else -> rendered?.let { DiagramImage(it, selectedRegionId, ::select) }
                        ?: Text(label("구조도 그리는 중…", "Rendering…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagramImage(rendered: RenderedDiagram, selectedRegionId: String?, onSelect: (String) -> Unit) {
    val layout = rendered.layout
    val density = LocalDensity.current
    // One image pixel per screen pixel, so tap offsets map straight onto layout coordinates.
    val width = with(density) { layout.width.toDp() }
    val height = with(density) { layout.height.toDp() }
    val selectionColor = AppColors.NeonBlue
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Box(
            Modifier.requiredSize(width, height).pointerInput(layout) {
                detectTapGestures { offset ->
                    layout.boxes.firstOrNull { b ->
                        offset.x >= b.x && offset.x <= b.x + b.w && offset.y >= b.y && offset.y <= b.y + b.h
                    }?.let { onSelect(it.regionId) }
                }
            },
        ) {
            Image(rendered.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
            val selected = layout.boxes.firstOrNull { it.regionId == selectedRegionId }
            if (selected != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        selectionColor,
                        topLeft = Offset(selected.x - 2f, selected.y - 2f),
                        size = Size(selected.w + 4f, selected.h + 4f),
                        style = Stroke(width = 3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagramTable(diagram: StructureDiagram, selectedRegionId: String?, ko: Boolean, onSelect: (String) -> Unit) {
    val rows = remember(diagram) { regionsInTreeOrder(diagram) }
    val mono = FontFamily.Monospace
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for ((text, w) in listOf(
                    (if (ko) "구성 요소" else "Component") to 300, (if (ko) "오프셋" else "Offset") to 120, "Hex" to 110,
                    (if (ko) "길이 (B)" else "Length (B)") to 120, (if (ko) "역할" else "Role") to 120, (if (ko) "상태" else "Status") to 100,
                )) Text(text, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(w.dp))
            }
        }
        items(rows, key = { it.id }) { r ->
            val color = when (r.status) {
                RegionStatus.PARSED -> AppColors.TextPrimary
                RegionStatus.WARNING -> AppColors.NeonOrange
                RegionStatus.UNPARSED -> AppColors.TextMuted
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (r.id == selectedRegionId) AppColors.Selection else AppColors.Panel)
                    .clickable(enabled = r.nodePath != null) { onSelect(r.id) }
                    .padding(vertical = 3.dp),
            ) {
                val indent = if (r.depth == 0) "" else "  ".repeat(r.depth - 1) + "↳ "
                Text(indent + r.label, color = color, fontSize = 12.sp, modifier = Modifier.width(300.dp))
                Text(r.offset.toString(), color = color, fontSize = 12.sp, fontFamily = mono, modifier = Modifier.width(120.dp))
                Text("0x" + r.offset.toString(16).uppercase(), color = color, fontSize = 12.sp, fontFamily = mono, modifier = Modifier.width(110.dp))
                Text(r.length.toString(), color = color, fontSize = 12.sp, fontFamily = mono, modifier = Modifier.width(120.dp))
                Text(r.role.name, color = color, fontSize = 12.sp, modifier = Modifier.width(120.dp))
                Text(r.status.name, color = color, fontSize = 12.sp, modifier = Modifier.width(100.dp))
            }
        }
        if (diagram.overlays.isNotEmpty() || diagram.warnings.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val labels = diagram.regions.associate { it.id to it.label }
                    for (o in diagram.overlays) {
                        Text("${o.label}: ${o.offset}–${o.offset + o.length} (${o.length} B)", color = AppColors.NeonPurple, fontSize = 12.sp)
                    }
                    for (l in diagram.links) {
                        Text("${labels[l.fromId] ?: l.fromId} → ${l.meaning.name}: ${l.evidence}", color = AppColors.NeonPurple, fontSize = 12.sp)
                    }
                    for (w in diagram.warnings) Text((if (ko) "경고: " else "Warning: ") + w, color = AppColors.NeonOrange, fontSize = 12.sp)
                }
            }
        }
    }
}
