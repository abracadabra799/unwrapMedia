package com.multiviewer.ui.ai

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.diagram.buildStructureDiagram
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.FrameInfo
import java.io.File
import java.util.Locale

const val TOOL_PREFIX = "@@unwrapmedia"
const val MAX_TOOL_ROUNDS = 3
const val MAX_TOOL_REQUESTS_PER_ROUND = 5
const val MAX_TOOL_HEX_BYTES = 1024
const val MAX_TOOL_RESULT_CHARS = 8_000
private const val MAX_STRUCTURE_LINES = 60

/** Report kinds the model may name; which ones apply (and in what state) is decided per file at send time. */
val TOOL_REPORT_KINDS: Set<String> = AiReportKind.entries.map { it.key }.toSet()

/**
 * The only things the model can ask the app for. Every request is read-only, bounded, and answered from the
 * tab the question was sent from; none names a file, path, or command, so it cannot reach another file or
 * change anything.
 */
sealed interface AiToolRequest {
    data class Hex(val offset: Long, val length: Long) : AiToolRequest
    data class Node(val offset: Long) : AiToolRequest
    data object Structure : AiToolRequest
    data object Check : AiToolRequest
    data class Frame(val index: Int) : AiToolRequest
    data class Report(val kind: String) : AiToolRequest
}

/** One request line; [request] is null when it was refused ([reason] says why). */
data class ParsedToolRequest(val line: String, val request: AiToolRequest?, val reason: String? = null)

data class AiToolResult(val line: String, val ok: Boolean, val text: String)

/** What the bridge may read, fixed when the question was sent. [identity] = the bytes the tree describes. */
data class AiToolContext(
    val file: File,
    val root: BoxNode?,
    val identity: ContentFileIdentity?,
    val contentCheckText: String?,
    val frames: List<FrameInfo>?,
    /** Kinds that apply to this file, from [aiReportSources]; a missing kind does not apply. */
    val reports: Map<AiReportKind, AiReportSource>,
)

/** Tells the model how to ask; part of every prompt. */
val TOOL_PROTOCOL_HELP = """
    |### [앱에 추가 자료 요청하기]
    |답하기에 자료가 부족하면, 답 대신 아래 형식의 줄만 써서 요청하세요 (한 번에 최대 $MAX_TOOL_REQUESTS_PER_ROUND 줄, 최대 $MAX_TOOL_ROUNDS 회). 앱이 읽기 전용으로 결과를 돌려줍니다.
    |$TOOL_PREFIX hex <offset> <length>   (최대 $MAX_TOOL_HEX_BYTES 바이트, 0x 또는 10진수)
    |$TOOL_PREFIX node <offset>           (그 오프셋을 포함하는 가장 깊은 구조 노드와 필드)
    |$TOOL_PREFIX structure               (파일 구조 개요)
    |$TOOL_PREFIX check                   (컨텐츠 검사·규격 결과)
    |$TOOL_PREFIX frame <index>           (프레임 정보)
    |$TOOL_PREFIX report <${TOOL_REPORT_KINDS.joinToString("|")}>
    |다른 파일, 경로, 명령, 쓰기는 요청할 수 없습니다.
""".trimMargin()

private fun number(token: String): Long? =
    if (token.startsWith("0x", ignoreCase = true)) token.drop(2).toLongOrNull(16) else token.toLongOrNull()

fun parseToolRequests(answer: String): List<ParsedToolRequest> = answer.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith(TOOL_PREFIX) }
    .map { line ->
        val parts = line.removePrefix(TOOL_PREFIX).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cmd = parts.firstOrNull()?.lowercase()
        val args = parts.drop(1)
        fun refuse(reason: String) = ParsedToolRequest(line, null, reason)
        when {
            cmd == "hex" && args.size == 2 -> {
                val off = number(args[0]); val len = number(args[1])
                if (off != null && len != null) ParsedToolRequest(line, AiToolRequest.Hex(off, len)) else refuse("hex needs <offset> <length> numbers")
            }
            cmd == "node" && args.size == 1 -> number(args[0])?.let { ParsedToolRequest(line, AiToolRequest.Node(it)) } ?: refuse("node needs an offset")
            cmd == "structure" && args.isEmpty() -> ParsedToolRequest(line, AiToolRequest.Structure)
            cmd == "check" && args.isEmpty() -> ParsedToolRequest(line, AiToolRequest.Check)
            cmd == "frame" && args.size == 1 -> args[0].toIntOrNull()?.let { ParsedToolRequest(line, AiToolRequest.Frame(it)) } ?: refuse("frame needs an index")
            cmd == "report" && args.size == 1 && args[0].lowercase() in TOOL_REPORT_KINDS -> ParsedToolRequest(line, AiToolRequest.Report(args[0].lowercase()))
            else -> refuse("unknown or not allowed request")
        }
    }
    .toList()

private fun hex(v: Long) = "0x" + v.toString(16).uppercase()

private fun bounded(text: String) =
    if (text.length <= MAX_TOOL_RESULT_CHARS) text else text.take(MAX_TOOL_RESULT_CHARS) + "\n… (결과 일부 생략)"

/** The deepest node whose byte range contains [offset] (the root if no child does). */
fun deepestNodeAt(root: BoxNode, offset: Long): BoxNode? {
    if (offset < root.offset || offset >= root.offset + root.size) return null
    var node = root
    var depth = 0
    while (depth++ < 64) {
        node = node.children.firstOrNull { offset >= it.offset && offset < it.offset + it.size } ?: break
    }
    return node
}

/** Blocking (file reads): run off the UI thread. */
fun executeToolRequest(request: AiToolRequest, ctx: AiToolContext): AiToolResult {
    val line = request.toString()
    fun fail(text: String) = AiToolResult(line, false, text)
    val identity = ctx.identity
    val needsBytes = request is AiToolRequest.Hex || request is AiToolRequest.Node || request is AiToolRequest.Structure
    if (needsBytes && (identity == null || !identity.matches(ctx.file))) {
        return fail("The file changed (or is missing) since it was opened; byte and structure requests are refused.")
    }
    return when (request) {
        is AiToolRequest.Hex -> {
            // Without a tree nothing says which bytes are safe to show (e.g. while the file is still loading).
            val root = ctx.root ?: return fail("The structure tree is not loaded yet; raw bytes are not available.")
            val size = identity!!.length
            if (request.offset < 0 || request.offset >= size || request.length <= 0) {
                return fail("Range ${request.offset}+${request.length} is outside the file (size $size B).")
            }
            val len = minOf(request.length, MAX_TOOL_HEX_BYTES.toLong(), size - request.offset).toInt()
            val bytes = ByteReader.open(ctx.file).use { it.readBytes(request.offset, len) }
            // Raw bytes must not get around field redaction: location/people/free-text bytes print as "--".
            // Bytes no top-level node accounts for (unparsed tails, gaps) are withheld too: nothing vouches for them.
            val covered = root.children.filter { it.size > 0 }.map { it.offset until it.offset + it.size }
            val uncovered = buildList {
                var cursor = request.offset
                for (r in covered.sortedBy { it.first }) {
                    if (r.first > cursor) add(cursor until minOf(r.first, request.offset + len))
                    cursor = maxOf(cursor, r.last + 1)
                }
                if (cursor < request.offset + len) add(cursor until request.offset + len)
            }
            val masked = (sensitiveRanges(root) + uncovered)
                .filter { it.last >= request.offset && it.first < request.offset + len }
            var anyMasked = false
            val text = buildString {
                if (len < request.length) appendLine("(requested ${request.length} B; returned $len B — limit $MAX_TOOL_HEX_BYTES B or end of file)")
                bytes.toList().chunked(16).forEachIndexed { i, row ->
                    append(hex(request.offset + i * 16L).padEnd(12)).append(": ")
                    appendLine(row.mapIndexed { j, b ->
                        val at = request.offset + i * 16L + j
                        if (masked.any { at in it }) { anyMasked = true; "--" } else String.format(Locale.US, "%02X", b.toInt() and 0xFF)
                    }.joinToString(" "))
                }
                if (anyMasked) appendLine("(-- = withheld: location, people, free-text or unparsed bytes)")
            }
            AiToolResult(line, true, bounded(text))
        }
        is AiToolRequest.Node -> {
            val root = ctx.root ?: return fail("The structure tree is not loaded.")
            val node = deepestNodeAt(root, request.offset) ?: return fail("Offset ${request.offset} is outside the parsed tree.")
            val path = pathTo(root, node)?.joinToString(" > ") { it.type } ?: node.type
            val text = buildString {
                appendLine("$path @ ${node.offset} (${hex(node.offset)}) +${node.size} B, header ${node.headerSize} B")
                val withheld = sensitiveRanges(root)
                node.fields.take(MAX_CONTEXT_FIELDS).forEach { appendLine("  ${it.name} = ${safeValue(it, ctx.file, node, withheld)}") }
                if (node.fields.size > MAX_CONTEXT_FIELDS) appendLine("  … ${node.fields.size - MAX_CONTEXT_FIELDS} more fields")
                node.warnings.take(10).forEach { appendLine("  parser warning: ${com.multiviewer.cli.scrubPaths(it, ctx.file)}") }
            }
            AiToolResult(line, true, bounded(text))
        }
        AiToolRequest.Structure -> {
            val root = ctx.root ?: return fail("The structure tree is not loaded.")
            val diagram = buildStructureDiagram(root, identity!!.length, null)
            val rows = diagram.regions.filter { it.depth <= 1 }
            val text = buildString {
                rows.take(MAX_STRUCTURE_LINES).forEach { r ->
                    appendLine("${"  ".repeat(r.depth)}${r.label} @ ${r.offset} (${hex(r.offset)}) +${r.length} B · ${r.role.name} · ${r.status.name}")
                }
                if (rows.size > MAX_STRUCTURE_LINES) appendLine("… ${rows.size - MAX_STRUCTURE_LINES} more regions")
                diagram.warnings.take(10).forEach { appendLine("warning: $it") }
            }
            AiToolResult(line, true, bounded(text))
        }
        AiToolRequest.Check -> ctx.contentCheckText?.let { AiToolResult(line, true, bounded(it)) }
            ?: fail("Content Check has not run for this file (or its results are stale). Ask the user to run it.")
        is AiToolRequest.Frame -> ctx.frames?.getOrNull(request.index)?.let { f ->
            AiToolResult(line, true, "#${f.index} ${f.type} · PTS ${String.format(Locale.US, "%.3f", f.ptsSeconds)} s · ${f.sizeBytes} B" +
                (f.byteOffset?.let { " @ $it (${hex(it)})" } ?: ""))
        } ?: fail("Frame ${request.index} is not available (frame analysis has ${ctx.frames?.size ?: 0} frames).")
        is AiToolRequest.Report -> when (val source = AiReportKind.of(request.kind)?.let { ctx.reports[it] }) {
            null -> fail("The ${request.kind} report does not apply to this file.")
            is AiReportSource.Ready -> {
                if (identity == null || !identity.matches(ctx.file)) {
                    return fail("The file changed (or is missing) since it was opened; the ${request.kind} report is refused.")
                }
                AiToolResult(line, true, bounded(source.text))
            }
            is AiReportSource.Unavailable -> fail("The ${request.kind} report is not available: ${source.reason}")
            is AiReportSource.OnRequest -> {
                // On-request analyses read the file at the tree's offsets: never against changed bytes.
                if (identity == null || !identity.matches(ctx.file)) {
                    return fail("The file changed (or is missing) since it was opened; the ${request.kind} report is refused.")
                }
                // Blocking: the caller is already off the UI thread; interrupting it stops the analysis.
                val (ok, text) = runReportWithTimeout(source.run, ctx.file)
                AiToolResult(line, ok, if (ok) bounded(text) else text)
            }
        }
    }
}
