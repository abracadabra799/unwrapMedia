package com.multiviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
import com.multiviewer.parser.diagram.spec.SpecDiagramDispatcher
import com.multiviewer.ui.diagram.spec.SpecDiagramExporter
import com.multiviewer.ui.diagram.spec.SpecDiagramLayoutEngine
import com.multiviewer.ui.diagram.spec.SpecLayout
import com.multiviewer.parser.diagram.hierarchical.HierarchicalDiagram
import com.multiviewer.parser.diagram.hierarchical.HierarchicalDiagramBuilder
import com.multiviewer.ui.diagram.hierarchical.HierarchicalDiagramExporter
import com.multiviewer.ui.diagram.hierarchical.HierarchicalLayout
import com.multiviewer.ui.diagram.hierarchical.HierarchicalLayoutEngine

// Only the on-screen bitmap is kept: exports re-render the PNG from the layout on demand, so a large diagram is
// never held twice (BufferedImage + Skia copy) for as long as the window is open.
private class RenderedDiagram(val layout: DiagramLayout, val bitmap: ImageBitmap)
private class RenderedSpec(val layout: SpecLayout, val bitmap: ImageBitmap)
private class RenderedHierarchical(val diagram: HierarchicalDiagram, val layout: HierarchicalLayout, val bitmap: ImageBitmap)

private const val HIERARCHICAL_PNG_SCALE = 2.0f

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
    var showSpec by remember(tab) { mutableStateOf(tab.file.name.endsWith(".bmp", ignoreCase = true)) }
    var showHierarchical by remember(tab) { mutableStateOf(!tab.file.name.endsWith(".bmp", ignoreCase = true)) }
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
    val renderedHier by produceState<RenderedHierarchical?>(null, root, tab.file.name, showHierarchical) {
        // Cleared before the early return too: a hidden view must not keep its image.
        value = null
        if (!showHierarchical) return@produceState
        val r = root ?: return@produceState
        val fileSize = tab.parsedIdentity?.length ?: r.size
        try {
            value = withContext(Dispatchers.Default) {
                val diag = HierarchicalDiagramBuilder.build(r, fileSize, tab.file.name)
                val layout = HierarchicalLayoutEngine.layout(diag)
                val image = HierarchicalDiagramExporter.renderPng(layout, scale = HIERARCHICAL_PNG_SCALE)
                RenderedHierarchical(diag, layout, image.toComposeImageBitmap())
            }
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
                RenderedDiagram(layout, image.toComposeImageBitmap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e.toString()
        }
    }
    val renderedSpec by produceState<RenderedSpec?>(null, root, tab.file.name, showSpec) {
        value = null
        if (!showSpec) return@produceState
        val r = root ?: return@produceState
        val fileSize = tab.parsedIdentity?.length ?: r.size
        try {
            value = withContext(Dispatchers.Default) {
                val spec = SpecDiagramDispatcher.build(r, fileSize, tab.file.name)
                val layout = SpecDiagramLayoutEngine.layout(spec)
                val image = SpecDiagramExporter.renderPng(layout)
                RenderedSpec(layout, image.toComposeImageBitmap())
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

    // The shown view's PNG, re-rendered from its layout (call the returned function off the UI thread).
    fun currentPngRenderer(): (() -> BufferedImage)? = when {
        showHierarchical -> renderedHier?.layout?.let { l -> { HierarchicalDiagramExporter.renderPng(l, scale = HIERARCHICAL_PNG_SCALE) } }
        showSpec -> renderedSpec?.layout?.let { l -> { SpecDiagramExporter.renderPng(l) } }
        else -> rendered?.layout?.let { l -> { renderStructurePng(l) } }
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
            } catch (e: Exception) {
                message = label("저장 실패: ", "Save failed: ") + (e.message ?: e.toString())
            } finally {
                saving = false
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(width = 1120.dp, height = 840.dp),
        title = label("파일 구조도", "File Structure Diagram") + " — ${tab.file.name}",
    ) {
        Column(
            Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showHierarchical) Button(onClick = {}) { Text(label("계층 구조도", "Hierarchical")) }
                else OutlinedButton(onClick = { showHierarchical = true; showSpec = false; showTable = false }) { Text(label("계층 구조도", "Hierarchical")) }

                if (showSpec) Button(onClick = {}) { Text(label("규격도", "Spec Diagram")) }
                else OutlinedButton(onClick = { showSpec = true; showHierarchical = false; showTable = false }) { Text(label("규격도", "Spec Diagram")) }

                for ((value, name) in listOf(0 to label("개요", "Overview"), 1 to label("상세", "Detail"), 2 to label("세부", "Deep"))) {
                    if (depth == value && !showTable && !showSpec && !showHierarchical) Button(onClick = {}) { Text(name) }
                    else OutlinedButton(onClick = { depth = value; showTable = false; showSpec = false; showHierarchical = false }) { Text(name) }
                }

                if (showTable) Button(onClick = {}) { Text(label("표", "Table")) }
                else OutlinedButton(onClick = { showTable = true; showSpec = false; showHierarchical = false }) { Text(label("표", "Table")) }

                val hasImage = when {
                    showHierarchical -> renderedHier != null
                    showSpec -> renderedSpec != null
                    else -> rendered != null
                }
                val d = diagram

                OutlinedButton(enabled = hasImage && !fileChanged, onClick = {
                    val render = currentPngRenderer() ?: return@OutlinedButton
                    scope.launch {
                        val image = try {
                            withContext(Dispatchers.Default) { render() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            message = label("이미지를 만들지 못했습니다: ", "Could not render the image: ") + e
                            return@launch
                        }
                        message = if (ClipboardUtil.copyImage(image)) label("이미지를 클립보드에 복사했습니다", "Image copied to the clipboard")
                        else label("클립보드에 복사하지 못했습니다 (다른 앱이 사용 중일 수 있음)", "Could not copy to the clipboard (another app may be using it)")
                    }
                }) { Text(label("이미지 복사", "Copy image")) }

                OutlinedButton(enabled = hasImage && !saving && !fileChanged, onClick = {
                    val render = currentPngRenderer() ?: return@OutlinedButton

                    val userHome = System.getProperty("user.home") ?: "."
                    val downloadsDir = File(userHome, "Downloads")
                    val targetDir = if (downloadsDir.exists() && downloadsDir.isDirectory) downloadsDir else File(userHome)
                    val outFile = File(targetDir, "${tab.file.nameWithoutExtension}-structure.png")
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val image = render()
                                Files.newOutputStream(outFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                                    check(ImageIO.write(image, "png", out)) { "No PNG writer" }
                                }
                            }
                            message = label("Downloads 폴더에 저장되었습니다: ${outFile.name}", "Saved to Downloads: ${outFile.name}")
                        } catch (e: Exception) {
                            message = label("다운로드 실패: ${e.message}", "Download failed: ${e.message}")
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(label("📥 빠른 다운로드", "📥 Quick Download")) }

                OutlinedButton(enabled = hasImage && !saving && !fileChanged, onClick = {
                    val render = currentPngRenderer() ?: return@OutlinedButton
                    save("png") { file ->
                        val image = render()
                        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                            check(ImageIO.write(image, "png", out)) { "No PNG writer" }
                        }
                    }
                }) { Text(label("PNG 저장", "Save PNG")) }

                OutlinedButton(enabled = hasImage && !saving && !fileChanged, onClick = {
                    val svg = when {
                        showHierarchical -> renderedHier?.let { HierarchicalDiagramExporter.renderSvg(it.layout) }
                        showSpec -> renderedSpec?.let { SpecDiagramExporter.renderSvg(it.layout) }
                        else -> rendered?.let { renderStructureSvg(it.layout) }
                    } ?: return@OutlinedButton
                    save("svg") { file ->
                        Files.writeString(file.toPath(), svg, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    }
                }) { Text(label("SVG 저장", "Save SVG")) }

                OutlinedButton(enabled = d != null && !fileChanged, onClick = {
                    val table = d?.let(::structureDiagramMarkdownTable) ?: return@OutlinedButton
                    message = if (ClipboardUtil.copyToClipboard(table)) label("Markdown 표를 복사했습니다", "Markdown table copied")
                    else label("클립보드에 복사하지 못했습니다 (다른 앱이 사용 중일 수 있음)", "Could not copy to the clipboard (another app may be using it)")
                }) { Text(label("표 복사 (Markdown)", "Copy table (Markdown)")) }
            }
            Text(
                when {
                    showHierarchical -> label(
                        "표준 규격서(JEITA Exif / ISO / ITU-T) 스타일의 다중 열 계층 구조도입니다. 컨테이너 세그먼트에서 하위 박스로의 프로젝션(확장)과 상세 메타데이터가 시각화됩니다.",
                        "Multi-column hierarchical specification architecture diagram (JEITA / ISO / ITU-T spec style) with projection expansions from container segments to internal structures."
                    )
                    showSpec -> label(
                        "BMP/미디어 규격에 맞춘 4바이트 정렬 기술 다이어그램입니다. 헤더 배너, 오프셋 화살표, 2D 행/패딩 구조가 정확한 바이트로 시각화됩니다.",
                        "Spec-grade 4-byte aligned diagram matching canonical format specifications, with header banners, offset arrows, and 2D row/padding layout."
                    )
                    else -> label(
                        "실제 파싱된 바이트 범위로 그린 비율 무관 구조도입니다. 점선은 어떤 노드도 설명하지 않는 바이트, 주황 테두리는 경고입니다. 행을 누르면 구조 트리에서 선택됩니다.",
                        "Not-to-scale map of the actual parsed byte ranges. Dashed rows are bytes no node accounts for; orange borders are warnings. Click a row to select it in the structure tree."
                    )
                },
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
                    showHierarchical -> renderedHier?.let { HierarchicalDiagramImage(it, tab, ko) }
                        ?: Text(label("계층 구조도 생성 중…", "Building hierarchical diagram…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                    showSpec -> renderedSpec?.let { SpecDiagramImage(it) }
                        ?: Text(label("규격도 생성 중…", "Building spec diagram…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                    showTable -> d?.let { DiagramTable(it, selectedRegionId, ko, ::select) }
                        ?: Text(label("구조도 생성 중…", "Building diagram…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                    else -> rendered?.let { DiagramImage(it, selectedRegionId, ko, ::select) }
                        ?: Text(label("구조도 그리는 중…", "Rendering…"), color = AppColors.TextMuted, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DiagramImage(
    rendered: RenderedDiagram,
    selectedRegionId: String?,
    ko: Boolean,
    onSelect: (String) -> Unit,
) {
    val layout = rendered.layout
    val density = LocalDensity.current
    val width = with(density) { layout.width.toDp() }
    val height = with(density) { layout.height.toDp() }
    val selectionColor = AppColors.NeonBlue

    var scale by remember(rendered) { mutableStateOf(1f) }
    var offset by remember(rendered) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(AppColors.Panel)
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val cursorPosition = change.position
                val scrollDeltaY = change.scrollDelta.y
                val factor = if (scrollDeltaY < 0) 1.15f else 0.85f
                val newScale = (scale * factor).coerceIn(0.2f, 10f)
                val newOffset = Offset(
                    cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
                    cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
                )
                scale = newScale
                offset = newOffset
                event.changes.forEach { it.consume() }
            }
            .pointerInput(rendered, scale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset += dragAmount
                }
            }
            .pointerInput(rendered, scale, offset) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    onTap = { clickPos ->
                        val layoutX = (clickPos.x - offset.x) / scale
                        val layoutY = (clickPos.y - offset.y) / scale
                        layout.boxes.firstOrNull { b ->
                            layoutX >= b.x && layoutX <= b.x + b.w && layoutY >= b.y && layoutY <= b.y + b.h
                        }?.let { onSelect(it.regionId) }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            Image(rendered.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
            val selected = layout.boxes.firstOrNull { it.regionId == selectedRegionId }
            if (selected != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        selectionColor,
                        topLeft = Offset(selected.x - 2f, selected.y - 2f),
                        size = Size(selected.w + 4f, selected.h + 4f),
                        style = Stroke(width = (3f / scale).coerceIn(1f, 6f)),
                    )
                }
            }
        }

        // Floating zoom HUD (zoom in/out, percent, reset)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, AppColors.Border),
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(
                    onClick = { scale = (scale * 0.85f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("−", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                Text(
                    "${(scale * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.clickable {
                        scale = 1f
                        offset = Offset.Zero
                    }.padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { scale = (scale * 1.15f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("+", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                if (scale != 1f || offset != Offset.Zero) {
                    Text(
                        if (ko) "초기화" else "Reset",
                        fontSize = 11.sp,
                        color = AppColors.NeonPurple,
                        modifier = Modifier.clickable {
                            scale = 1f
                            offset = Offset.Zero
                        }.padding(start = 2.dp, end = 6.dp),
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SpecDiagramImage(rendered: RenderedSpec) {
    val layout = rendered.layout
    val density = LocalDensity.current
    val width = with(density) { layout.width.toDp() }
    val height = with(density) { layout.height.toDp() }

    var scale by remember(rendered) { mutableStateOf(1f) }
    var offset by remember(rendered) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(AppColors.Panel)
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val cursorPosition = change.position
                val scrollDeltaY = change.scrollDelta.y
                val factor = if (scrollDeltaY < 0) 1.15f else 0.85f
                val newScale = (scale * factor).coerceIn(0.2f, 10f)
                val newOffset = Offset(
                    cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
                    cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
                )
                scale = newScale
                offset = newOffset
                event.changes.forEach { it.consume() }
            }
            .pointerInput(rendered, scale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset += dragAmount
                }
            }
            .pointerInput(rendered, scale, offset) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
    ) {
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            Image(rendered.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }

        // Floating zoom HUD (zoom in/out, percent, reset)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, AppColors.Border),
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(
                    onClick = { scale = (scale * 0.85f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("−", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                Text(
                    "${(scale * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.clickable {
                        scale = 1f
                        offset = Offset.Zero
                    }.padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { scale = (scale * 1.15f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("+", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                if (scale != 1f || offset != Offset.Zero) {
                    Text(
                        "초기화",
                        fontSize = 11.sp,
                        color = AppColors.NeonPurple,
                        modifier = Modifier.clickable {
                            scale = 1f
                            offset = Offset.Zero
                        }.padding(start = 2.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HierarchicalDiagramImage(
    rendered: RenderedHierarchical,
    tab: TabState,
    ko: Boolean,
) {
    val layout = rendered.layout
    val density = LocalDensity.current
    val width = with(density) { layout.width.toDp() }
    val height = with(density) { layout.height.toDp() }

    var scale by remember(rendered) { mutableStateOf(1f) }
    var offset by remember(rendered) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(AppColors.Panel)
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val cursorPosition = change.position
                val scrollDeltaY = change.scrollDelta.y
                val factor = if (scrollDeltaY < 0) 1.15f else 0.85f
                val newScale = (scale * factor).coerceIn(0.2f, 10f)
                val newOffset = Offset(
                    cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
                    cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
                )
                scale = newScale
                offset = newOffset
                event.changes.forEach { it.consume() }
            }
            .pointerInput(rendered, scale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset += dragAmount
                }
            }
            .pointerInput(rendered, scale, offset) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    onTap = { clickPos ->
                        val layoutX = (clickPos.x - offset.x) / scale
                        val layoutY = (clickPos.y - offset.y) / scale
                        val r = tab.root ?: return@detectTapGestures
                        for (col in layout.columns) {
                            for (sec in col.rows) {
                                if (layoutX >= sec.x && layoutX <= sec.x + sec.w &&
                                    layoutY >= sec.y && layoutY <= sec.y + sec.h
                                ) {
                                    val match = findNodeByMarker(r, sec.marker)
                                    if (match != null) {
                                        tab.selected = match
                                    }
                                    return@detectTapGestures
                                }
                            }
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            Image(rendered.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }

        // Floating zoom HUD (zoom in/out, percent, reset)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, AppColors.Border),
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(
                    onClick = { scale = (scale * 0.85f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("−", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                Text(
                    "${(scale * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.clickable {
                        scale = 1f
                        offset = Offset.Zero
                    }.padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { scale = (scale * 1.15f).coerceIn(0.2f, 10f) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("+", fontSize = 14.sp, color = AppColors.TextPrimary)
                }
                if (scale != 1f || offset != Offset.Zero) {
                    Text(
                        if (ko) "초기화" else "Reset",
                        fontSize = 11.sp,
                        color = AppColors.NeonPurple,
                        modifier = Modifier.clickable {
                            scale = 1f
                            offset = Offset.Zero
                        }.padding(start = 2.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}

private fun findNodeByMarker(root: com.multiviewer.parser.BoxNode, marker: String): com.multiviewer.parser.BoxNode? {
    val clean = marker.trim('[', ']', '(', ')')
    if (root.type.equals(clean, ignoreCase = true) || root.type.startsWith(clean, ignoreCase = true)) return root
    for (child in root.children) {
        val found = findNodeByMarker(child, clean)
        if (found != null) return found
    }
    return null
}


