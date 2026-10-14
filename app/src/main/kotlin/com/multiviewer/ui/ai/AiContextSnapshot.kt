package com.multiviewer.ui.ai

import com.multiviewer.cli.scrubPaths
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.diagram.buildStructureDiagram
import com.multiviewer.ui.ContentCheckSnapshot
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.STALE_CONTENT_CHECK_NOTICE
import com.multiviewer.ui.contentCheckPromptSection
import java.io.File
import java.util.Locale

const val MAX_CONTEXT_FIELDS = 40
const val MAX_CONTEXT_VALUE_CHARS = 200
const val MAX_CONTEXT_CHARS = 24_000
private const val MAX_STRUCTURE_LINES = 40
private const val MAX_PATH_DEPTH = 64

const val REDACTED = "[redacted]"
const val DETERMINISTIC_VERDICT_NOTICE =
    "PASS/FAIL/WARN/NOT_CHECKED 판정은 unwrapMedia가 바이트를 직접 검사해 정한 결과이며, AI의 답변으로 바뀌지 않습니다. 판정과 다른 의견은 가설로 구분해 주세요."

// Location, device identity, owner and free-text fields: never sent, whatever the format calls them.
// Free text (descriptions, comments, authors) can carry names and instructions aimed at the AI.
private val SENSITIVE_FIELD = Regex(
    "(?i)gps|latitude|longitude|altitude|location|serial|imageuniqueid|owner|artist|copyright|imei|uniqueid|" +
        "author|creator|comment|description|hostcomputer|makernote|keyword|subject|title|lens(make|model)?serial|^xp",
)
// EXIF tags the decoder leaves unnamed ("Tag 0x....") that hold people/free text: CameraOwnerName,
// BodySerialNumber, LensSerialNumber, XPTitle..XPSubject, UserComment, ImageDescription, Artist, HostComputer.
private val SENSITIVE_TAG_ID = Regex("(?i)^tag 0x(a430|a431|a435|9c9[b-f]|9286|010e|013b|013c|8298)$")
private val UNNAMED_TAG = Regex("(?i)^tag 0x[0-9a-f]{4}$")
private val NUMERIC_VALUE = Regex("^[0-9 .,:/+\\-x()]*$")

data class AiNodeContext(
    /** Node types from the root down to the selection. */
    val path: List<String>,
    val type: String,
    val offset: Long,
    val size: Long,
    val headerSize: Int,
    val fields: List<Pair<String, String>>,
    val omittedFields: Int,
    val warnings: List<String>,
    val selectedField: String?,
)

data class AiFrameContext(val index: Int, val type: Char, val ptsSeconds: Double, val sizeBytes: Int, val byteOffset: Long?)

/**
 * What one AI message may say about the focused file, captured at send time. Values are bounded, local
 * paths are scrubbed and sensitive fields redacted at capture, so [renderAiContext] can only emit what is here.
 */
data class AiContextSnapshot(
    val fileName: String,
    /** The bytes this context describes; a later change makes it stale ([isCurrent]). */
    val identity: ContentFileIdentity?,
    val mediaType: MediaType,
    val node: AiNodeContext?,
    val frame: AiFrameContext?,
    /** Content Check / conformance text, already bounded and path-scrubbed; null = not run or stale. */
    val contentCheckSection: String?,
    val structureLines: List<String>,
    val omittedStructureLines: Int,
    val warnings: List<String>,
    /** The file is missing or differs from the bytes the tree was parsed from: nothing should be sent. */
    val fileChangedSinceOpen: Boolean = false,
    /** Where the user is looking/pointing in the preview; dropped with the rest when the file changed. */
    val visual: AiVisualContext? = null,
) {
    /** Blocking stat: call off the UI thread. */
    fun isCurrent(file: File): Boolean = identity?.matches(file) == true
}

private fun hex(v: Long) = "0x" + v.toString(16).uppercase()

private fun pathTo(root: BoxNode, target: BoxNode): List<BoxNode>? {
    fun search(node: BoxNode, depth: Int): List<BoxNode>? {
        if (node === target) return listOf(node)
        if (depth >= MAX_PATH_DEPTH) return null
        for (child in node.children) search(child, depth + 1)?.let { return listOf(node) + it }
        return null
    }
    return search(root, 0)
}

private fun safeValue(field: BoxField, file: File): String {
    if (SENSITIVE_FIELD.containsMatchIn(field.name) || SENSITIVE_TAG_ID.matches(field.name)) return REDACTED
    // A raw XMP packet can hold GPS, creator and free text anywhere: say it exists, never send it.
    if (field.name.equals("xmp", ignoreCase = true)) return "[XMP packet, ${field.value.length} chars, not sent]"
    // Unnamed (often maker-note) tags with text values may be serials or names; numbers/dates are kept.
    if (UNNAMED_TAG.matches(field.name) && !NUMERIC_VALUE.matches(field.value.trim())) return REDACTED
    val scrubbed = scrubPaths(field.value, file).replace('\n', ' ').replace('\r', ' ')
    return if (scrubbed.length <= MAX_CONTEXT_VALUE_CHARS) scrubbed else scrubbed.take(MAX_CONTEXT_VALUE_CHARS) + "…"
}

/**
 * Captures the context for one send. Blocking (stats the file): run off the UI thread. A tree parsed from
 * bytes that no longer match the file ([parsedIdentity] ≠ current) contributes no node/structure evidence.
 */
fun captureAiContext(
    file: File,
    root: BoxNode?,
    type: MediaType,
    selected: BoxNode?,
    selectedField: BoxField?,
    frame: FrameInfo?,
    contentCheck: ContentCheckSnapshot?,
    parsedIdentity: ContentFileIdentity?,
    embeddedVideo: EmbeddedVideo?,
    visual: AiVisualContext? = null,
): AiContextSnapshot {
    val current = if (file.isFile) ContentFileIdentity.capture(file) else null
    val warnings = mutableListOf<String>()
    val treeCurrent = current != null && (parsedIdentity == null || parsedIdentity == current)
    if (!treeCurrent) {
        warnings += "The file changed (or is missing) since it was opened; structure, selection and check results are omitted. Reopen the file."
    }
    val usableRoot = root?.takeIf { treeCurrent }

    val node = if (usableRoot != null && selected != null) pathTo(usableRoot, selected)?.let { path ->
        AiNodeContext(
            path = path.map { it.type },
            type = selected.type, offset = selected.offset, size = selected.size, headerSize = selected.headerSize,
            fields = selected.fields.take(MAX_CONTEXT_FIELDS).map { it.name to safeValue(it, file) },
            omittedFields = (selected.fields.size - MAX_CONTEXT_FIELDS).coerceAtLeast(0),
            warnings = selected.warnings.take(10).map { scrubPaths(it, file) },
            selectedField = selectedField?.takeIf { f -> selected.fields.any { it === f } }?.name,
        )
    } else null

    val contentCheckSection = when {
        contentCheck == null -> null
        !treeCurrent || contentCheck.fileIdentity == null || contentCheck.fileIdentity != current -> {
            warnings += STALE_CONTENT_CHECK_NOTICE
            null
        }
        else -> contentCheckPromptSection(contentCheck, file)
    }

    val structure = mutableListOf<String>()
    var omittedStructure = 0
    if (usableRoot != null && current != null) {
        val diagram = buildStructureDiagram(usableRoot, current.length, embeddedVideo.takeIf { type == MediaType.IMAGE })
        val top = diagram.regions.filter { it.depth == 0 }
        top.take(MAX_STRUCTURE_LINES).forEach { r ->
            structure += "${r.label} @ ${r.offset} (${hex(r.offset)}) +${r.length} B · ${r.role.name} · ${r.status.name}"
        }
        omittedStructure = (top.size - MAX_STRUCTURE_LINES).coerceAtLeast(0)
        diagram.overlays.forEach { structure += "overlay ${it.label}: ${it.offset}–${it.offset + it.length}" }
        diagram.links.forEach { structure += "link ${it.meaning.name}: ${it.evidence}" }
        diagram.warnings.take(10).forEach { structure += "structure warning: $it" }
    }

    return AiContextSnapshot(
        fileName = file.name,
        identity = current,
        mediaType = type,
        node = node,
        frame = frame?.takeIf { treeCurrent }?.let { AiFrameContext(it.index, it.type, it.ptsSeconds, it.sizeBytes, it.byteOffset) },
        contentCheckSection = contentCheckSection,
        structureLines = structure,
        omittedStructureLines = omittedStructure,
        warnings = warnings,
        fileChangedSinceOpen = !treeCurrent,
        visual = visual?.takeIf { treeCurrent },
    )
}

/** The exact text sent with a message (also shown in the outgoing preview). Bounded by [MAX_CONTEXT_CHARS]. */
fun renderAiContext(snapshot: AiContextSnapshot, attachedImage: ImageRegion? = null, imageOmittedReason: String? = null): String {
    val sb = StringBuilder()
    sb.appendLine("### [unwrapMedia 컨텍스트 — 앱이 직접 확인한 사실]")
    sb.appendLine("- $DETERMINISTIC_VERDICT_NOTICE")
    sb.appendLine("- 파일: ${snapshot.fileName} (${snapshot.identity?.length?.let { "$it B" } ?: "크기 알 수 없음"}), 유형 ${snapshot.mediaType.name}")
    snapshot.warnings.forEach { sb.appendLine("- 주의: $it") }
    snapshot.node?.let { n ->
        sb.appendLine("### [선택한 구조 노드]")
        sb.appendLine("- 경로: ${n.path.joinToString(" > ")}")
        sb.appendLine("- ${n.type} @ ${n.offset} (${hex(n.offset)}), 크기 ${n.size} B, 헤더 ${n.headerSize} B")
        n.selectedField?.let { sb.appendLine("- 선택한 필드: $it") }
        n.fields.forEach { (name, value) -> sb.appendLine("  • $name = $value") }
        if (n.omittedFields > 0) sb.appendLine("  • … 필드 ${n.omittedFields}개 생략")
        n.warnings.forEach { sb.appendLine("  • 파서 경고: $it") }
    }
    snapshot.frame?.let { f ->
        sb.appendLine("### [선택한 프레임]")
        sb.appendLine(
            "- #${f.index} ${f.type} · PTS ${String.format(Locale.US, "%.3f", f.ptsSeconds)} s · ${f.sizeBytes} B" +
                (f.byteOffset?.let { " @ $it (${hex(it)})" } ?: ""),
        )
    }
    snapshot.visual?.let { sb.append(renderVisualContext(it, attachedImage, imageOmittedReason)) }
    if (snapshot.structureLines.isNotEmpty()) {
        sb.appendLine("### [파일 구조 개요 (실제 파싱 범위)]")
        snapshot.structureLines.forEach { sb.appendLine("- $it") }
        if (snapshot.omittedStructureLines > 0) sb.appendLine("- … 최상위 영역 ${snapshot.omittedStructureLines}개 생략")
    }
    snapshot.contentCheckSection?.let { sb.append(it) } ?: sb.appendLine("- 컨텐츠 검사: 이 컨텍스트에 포함되지 않음 (미실행 또는 무효)")
    val text = sb.toString()
    return if (text.length <= MAX_CONTEXT_CHARS) text
    else text.take(MAX_CONTEXT_CHARS) + "\n… (컨텍스트 ${text.length - MAX_CONTEXT_CHARS}자 생략)\n"
}
