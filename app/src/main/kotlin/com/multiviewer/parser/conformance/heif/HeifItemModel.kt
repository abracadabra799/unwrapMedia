package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.isobmff.BmffBox
import com.multiviewer.parser.conformance.isobmff.BmffTree
import java.io.IOException

data class HeifItemInfo(val id: Long, val type: String, val infeVersion: Int, val hidden: Boolean, val infe: BmffBox)
/** Absolute file offset for construction method 0, idat-payload-relative for method 1 (base_offset already added). */
data class HeifExtent(val offset: Long, val length: Long)
data class HeifLocation(
    val itemId: Long, val method: Int, val dataReferenceIndex: Int, val baseOffset: Long, val extents: List<HeifExtent>,
)
data class HeifProperty(val index: Int /* 1-based */, val type: String, val box: BmffBox)
data class HeifAssociation(val itemId: Long, val propertyIndex: Int, val essential: Boolean)
data class HeifReference(val type: String, val from: Long, val to: List<Long>, val box: BmffBox)
data class HeifModel(
    val meta: BmffBox?, val metaCount: Int, val handler: String?, val primaryId: Long?,
    val items: List<HeifItemInfo>, val iinfEntryCount: Long?, val locations: List<HeifLocation>, val ilocVersion: Int?,
    val properties: List<HeifProperty>, val associations: List<HeifAssociation>, val ipmaOrder: List<Long>,
    val references: List<HeifReference>, val idat: BmffBox?, val problems: List<String>,
    /** The ipco that was read (first in iprp) and every ipma box of iprp, for ranges and problem attribution. */
    val ipco: BmffBox? = null, val ipmas: List<BmffBox> = emptyList(),
    /** item_IDs in entry order, one list per box of [ipmas] (same order); [ipmaOrder] is their concatenation. */
    val ipmaOrders: List<List<Long>> = emptyList(),
)

/** Count above which an item/property/association/reference table is not read (rules report NOT_CHECKED). */
const val HEIF_MAX_ENTRIES = 1_000_000L

/** Problem prefix used when a table count exceeds [HEIF_MAX_ENTRIES]. */
const val HEIF_COUNT_CAP_PROBLEM = "항목 수 상한 초과"

/** Problem code: the box could not be read (truncated or I/O error) and its content was dropped. */
const val HEIF_UNREADABLE_PROBLEM = "읽지 못함"

/** Detail that follows [HEIF_UNREADABLE_PROBLEM] when the box could not be read because of an I/O error. */
const val HEIF_READ_ERROR = "읽기 오류"

/** Problem code: bytes left over after a table's last entry (entries themselves were read). */
const val HEIF_TRAILING_BYTES_PROBLEM = "항목 뒤 남은 바이트"

/** Problem code: an iloc entry was skipped because an offset/length is not representable; followed by "item_ID N". */
const val HEIF_SKIPPED_ENTRY_PROBLEM = "항목 건너뜀"

/** Text after the "box@offset: " prefix of a model problem. */
private fun problemBody(p: String): String = p.substringAfter(": ", "")

fun isHeifUnreadableProblem(p: String): Boolean = problemBody(p).startsWith(HEIF_UNREADABLE_PROBLEM)
/** An unreadable box whose bytes could not be read (I/O error), as opposed to a box whose content is too short. */
fun isHeifReadErrorProblem(p: String): Boolean = problemBody(p).startsWith("$HEIF_UNREADABLE_PROBLEM — $HEIF_READ_ERROR")
fun isHeifTrailingBytesProblem(p: String): Boolean = problemBody(p).startsWith(HEIF_TRAILING_BYTES_PROBLEM)
fun isHeifCapProblem(p: String): Boolean = problemBody(p).startsWith(HEIF_COUNT_CAP_PROBLEM)

private val SKIPPED_ITEM_ID = Regex("""^${HEIF_SKIPPED_ENTRY_PROBLEM} \(item_ID (\d+)\b""")

/** item_ID of a [HEIF_SKIPPED_ENTRY_PROBLEM] problem, else null. */
fun heifSkippedItemId(p: String): Long? = SKIPPED_ITEM_ID.find(problemBody(p))?.groupValues?.get(1)?.toLongOrNull()

/** meta children that may appear at most once; extra copies are reported (the first is used). */
private val UNIQUE_META_CHILDREN = listOf("hdlr", "pitm", "iinf", "iloc", "iprp", "idat", "iref", "dinf")

/**
 * Reads the HEIF item metadata of the first top-level `meta` box. Bounded and lenient: every malformed piece is
 * recorded in [HeifModel.problems] and skipped; it never throws (except cancellation from the reader).
 */
object HeifItemModel {
    private class Truncated(msg: String) : Exception(msg)

    /** Bounded big-endian cursor over [pos, end). */
    private class Cursor(val reader: ByteReader, var pos: Long, val end: Long, val what: String) {
        fun need(n: Int) { if (n < 0 || pos + n > end) throw Truncated("$what: 박스 끝(@$end)을 넘는 필드 (@$pos, ${n}바이트)") }
        fun u8(): Int { need(1); return reader.readUInt8(pos).also { pos += 1 } }
        fun u16(): Int { need(2); return reader.readUInt16(pos).also { pos += 2 } }
        fun u32(): Long { need(4); return reader.readUInt32(pos).also { pos += 4 } }
        fun u64(): Long { need(8); return reader.readUInt64(pos).also { pos += 8 } }
        fun uN(size: Int): Long = when (size) { 0 -> 0L; 4 -> u32(); 8 -> u64(); else -> throw Truncated("$what: 지원하지 않는 필드 크기 $size") }
        fun fourCC(): String { need(4); return String(reader.readBytes(pos, 4), Charsets.ISO_8859_1).also { pos += 4 } }
        val remaining: Long get() = end - pos
    }

    fun read(tree: BmffTree, reader: ByteReader): HeifModel {
        val problems = ArrayList<String>()
        val metas = tree.top.filter { it.type == "meta" }
        val meta = metas.firstOrNull()
        if (meta == null) {
            return HeifModel(null, 0, null, null, emptyList(), null, emptyList(), null, emptyList(), emptyList(),
                emptyList(), emptyList(), null, problems)
        }
        if (meta.problem != null) problems += "meta@${meta.offset}: ${meta.problem}"

        fun <T> guarded(box: BmffBox, block: () -> T): T? = try {
            block()
        } catch (e: Truncated) {
            problems += "${box.type}@${box.offset}: $HEIF_UNREADABLE_PROBLEM — ${e.message}"; null
        } catch (e: IOException) {
            problems += "${box.type}@${box.offset}: $HEIF_UNREADABLE_PROBLEM — $HEIF_READ_ERROR (${e.message})"; null
        }

        val children = meta.children
        for (type in UNIQUE_META_CHILDREN) {
            val n = children.count { it.type == type }
            if (n > 1) problems += "meta@${meta.offset}: $type 박스가 ${n}개 (첫 번째 사용)"
        }
        fun first(type: String): BmffBox? {
            val all = children.filter { it.type == type }
            all.filter { it.problem != null }.forEach { problems += "$type@${it.offset}: ${it.problem}" }
            return all.firstOrNull { it.problem == null }
        }

        val handler = first("hdlr")?.let { b -> guarded(b) { Cursor(reader, b.payload + 8, b.end, "hdlr").fourCC() } }

        val primaryId = first("pitm")?.let { b ->
            guarded(b) {
                val c = Cursor(reader, b.payload, b.end, "pitm")
                val version = c.u8(); c.need(3); c.pos += 3
                if (version == 0) c.u16().toLong() else c.u32()
            }
        }

        val items = ArrayList<HeifItemInfo>()
        var iinfEntryCount: Long? = null
        first("iinf")?.let { b ->
            guarded(b) {
                val c = Cursor(reader, b.payload, b.end, "iinf")
                val version = c.u8(); c.need(3); c.pos += 3
                val count = if (version == 0) c.u16().toLong() else c.u32()
                iinfEntryCount = count
                if (count > HEIF_MAX_ENTRIES) {
                    problems += "iinf@${b.offset}: $HEIF_COUNT_CAP_PROBLEM ($count)"
                } else {
                    val infes = childBoxes(reader, c.pos, b.end, "iinf", b.offset, problems)
                    infes.filter { it.type != "infe" }.forEach {
                        problems += "iinf@${b.offset}: infe가 아닌 하위 박스 ${it.type}@${it.offset}"
                    }
                    for (infe in infes.filter { it.type == "infe" }) {
                        if (items.size >= HEIF_MAX_ENTRIES) break
                        guarded(infe) { readInfe(reader, infe, problems) }?.let { items += it }
                    }
                    val infeCount = infes.count { it.type == "infe" }
                    if (infeCount.toLong() != count) problems += "iinf@${b.offset}: entry_count($count)와 infe 박스 수($infeCount)가 다름"
                }
            }
        }

        val locations = ArrayList<HeifLocation>()
        var ilocVersion: Int? = null
        first("iloc")?.let { b ->
            guarded(b) { readIloc(reader, b, locations, problems) { ilocVersion = it } }
        }

        val properties = ArrayList<HeifProperty>()
        val associations = ArrayList<HeifAssociation>()
        val ipmaOrder = ArrayList<Long>()
        var ipcoBox: BmffBox? = null
        val ipmaBoxes = ArrayList<BmffBox>()
        val ipmaOrders = ArrayList<List<Long>>()
        first("iprp")?.let { iprp ->
            val iprpChildren = childBoxes(reader, iprp.payload, iprp.end, "iprp", iprp.offset, problems)
            val ipcos = iprpChildren.filter { it.type == "ipco" }
            if (ipcos.size > 1) problems += "iprp@${iprp.offset}: ipco 박스가 ${ipcos.size}개"
            ipcos.firstOrNull()?.let { ipco ->
                ipcoBox = ipco
                childBoxes(reader, ipco.payload, ipco.end, "ipco", ipco.offset, problems).forEachIndexed { i, p ->
                    if (i < HEIF_MAX_ENTRIES) properties += HeifProperty(i + 1, p.type, p)
                }
            }
            for (ipma in iprpChildren.filter { it.type == "ipma" }) {
                ipmaBoxes += ipma
                val order = ArrayList<Long>()
                guarded(ipma) { readIpma(reader, ipma, associations, order, problems) }
                ipmaOrders += order
                ipmaOrder += order
            }
        }

        val references = ArrayList<HeifReference>()
        first("iref")?.let { b ->
            guarded(b) {
                val c = Cursor(reader, b.payload, b.end, "iref")
                val version = c.u8(); c.need(3); c.pos += 3
                for (r in childBoxes(reader, c.pos, b.end, "iref", b.offset, problems)) {
                    if (references.size >= HEIF_MAX_ENTRIES) { problems += "iref@${b.offset}: $HEIF_COUNT_CAP_PROBLEM"; break }
                    guarded(r) {
                        val rc = Cursor(reader, r.payload, r.end, "iref/${r.type}")
                        val from = if (version == 0) rc.u16().toLong() else rc.u32()
                        val n = rc.u16()
                        val to = ArrayList<Long>(minOf(n, 4096))
                        repeat(n) { to += if (version == 0) rc.u16().toLong() else rc.u32() }
                        references += HeifReference(r.type, from, to, r)
                        if (rc.remaining > 0) problems += "${r.type}@${r.offset}: 참조 ID 뒤 남은 바이트 ${rc.remaining}"
                    }
                }
            }
        }

        val idat = first("idat")

        return HeifModel(meta, metas.size, handler, primaryId, items, iinfEntryCount, locations, ilocVersion,
            properties, associations, ipmaOrder, references, idat, problems, ipcoBox, ipmaBoxes, ipmaOrders)
    }

    private fun readInfe(reader: ByteReader, b: BmffBox, problems: MutableList<String>): HeifItemInfo {
        val c = Cursor(reader, b.payload, b.end, "infe")
        val version = c.u8()
        val flags = (c.u8() shl 16) or (c.u8() shl 8) or c.u8()
        val id: Long
        var type = ""
        when {
            version < 2 -> { id = c.u16().toLong(); c.u16() }
            version == 2 -> { id = c.u16().toLong(); c.u16(); type = c.fourCC() }
            else -> { id = c.u32(); c.u16(); type = c.fourCC() }
        }
        if (version >= 2) {
            // item_name, then content_type [+ optional content_encoding] for mime, item_uri_type for uri.
            fun cString(field: String): Boolean {
                while (c.pos < c.end) { if (c.u8() == 0) return true }
                problems += "infe@${b.offset}: $field 문자열에 null 종료가 없음"
                return false
            }
            var ok = cString("item_name")
            if (ok && type == "mime") { ok = cString("content_type"); if (ok && c.remaining > 0) ok = cString("content_encoding") }
            if (ok && type == "uri ") ok = cString("item_uri_type")
            if (ok && c.remaining > 0) problems += "infe@${b.offset}: 필드 뒤 남은 바이트 ${c.remaining}"
        }
        return HeifItemInfo(id, type, version, flags and 1 != 0, b)
    }

    /**
     * Reports the version via [onVersion] as soon as it is read; appends the entries that parsed completely and whose
     * offsets/lengths are representable (no 2^63+ values, no base_offset + extent_offset overflow).
     */
    private fun readIloc(
        reader: ByteReader, b: BmffBox, out: MutableList<HeifLocation>, problems: MutableList<String>, onVersion: (Int) -> Unit,
    ) {
        val c = Cursor(reader, b.payload, b.end, "iloc")
        val version = c.u8(); onVersion(version); c.need(3); c.pos += 3
        if (version > 2) { problems += "iloc@${b.offset}: 지원하지 않는 버전 $version"; return }
        val s1 = c.u8(); val s2 = c.u8()
        val offsetSize = s1 ushr 4; val lengthSize = s1 and 0xF
        val baseOffsetSize = s2 ushr 4; val indexSize = if (version >= 1) s2 and 0xF else 0
        val bad = listOf("offset_size" to offsetSize, "length_size" to lengthSize, "base_offset_size" to baseOffsetSize,
            "index_size" to indexSize).filter { it.second !in setOf(0, 4, 8) }
        if (bad.isNotEmpty()) {
            problems += "iloc@${b.offset}: 허용되지 않는 필드 크기 " + bad.joinToString { "${it.first}=${it.second}" }
            return
        }
        val count = if (version < 2) c.u16().toLong() else c.u32()
        if (count > HEIF_MAX_ENTRIES) { problems += "iloc@${b.offset}: $HEIF_COUNT_CAP_PROBLEM ($count)"; return }
        // Extents may be zero-width (all field sizes 0) and consume no bytes: bound their total across all items.
        var extentBudget = HEIF_MAX_ENTRIES
        for (i in 0 until count) {
            try {
                val id = if (version < 2) c.u16().toLong() else c.u32()
                val method = if (version >= 1) c.u16() and 0xF else 0
                val dri = c.u16()
                val base = c.uN(baseOffsetSize)
                val extentCount = c.u16()
                if (extentCount > extentBudget) {
                    problems += "iloc@${b.offset}: $HEIF_COUNT_CAP_PROBLEM (extent 합계 > $HEIF_MAX_ENTRIES, 항목 ${i + 1}/$count)"
                    return
                }
                extentBudget -= extentCount
                var wrapped: String? = if (base < 0) "base_offset이 2^63 이상" else null
                val extents = ArrayList<HeifExtent>(minOf(extentCount, 4096))
                repeat(extentCount) { e ->
                    if (indexSize > 0) c.uN(indexSize)
                    val off = c.uN(offsetSize)
                    val len = c.uN(lengthSize)
                    if (wrapped != null) return@repeat
                    when {
                        off < 0 -> wrapped = "extent ${e + 1} extent_offset이 2^63 이상"
                        len < 0 -> wrapped = "extent ${e + 1} extent_length가 2^63 이상"
                        base > Long.MAX_VALUE - off -> wrapped = "extent ${e + 1} base_offset + extent_offset 오버플로"
                        else -> extents += HeifExtent(base + off, len)
                    }
                }
                if (wrapped != null) problems += "iloc@${b.offset}: $HEIF_SKIPPED_ENTRY_PROBLEM (item_ID $id, 항목 ${i + 1}/$count) — $wrapped"
                else out += HeifLocation(id, method, dri, base, extents)
            } catch (e: Truncated) {
                problems += "iloc@${b.offset}: 항목 ${i + 1}/$count 잘림 (${e.message})"
                break
            }
        }
        if (c.remaining > 0) problems += "iloc@${b.offset}: $HEIF_TRAILING_BYTES_PROBLEM ${c.remaining}"
    }

    private fun readIpma(
        reader: ByteReader, b: BmffBox, out: MutableList<HeifAssociation>, order: MutableList<Long>, problems: MutableList<String>,
    ) {
        val c = Cursor(reader, b.payload, b.end, "ipma")
        val version = c.u8()
        val flags = (c.u8() shl 16) or (c.u8() shl 8) or c.u8()
        val count = c.u32()
        if (count > HEIF_MAX_ENTRIES) { problems += "ipma@${b.offset}: $HEIF_COUNT_CAP_PROBLEM ($count)"; return }
        for (i in 0 until count) {
            try {
                val id = if (version < 1) c.u16().toLong() else c.u32()
                val n = c.u8()
                val list = ArrayList<HeifAssociation>(n)
                repeat(n) {
                    if (flags and 1 != 0) {
                        val v = c.u16(); list += HeifAssociation(id, v and 0x7FFF, v and 0x8000 != 0)
                    } else {
                        val v = c.u8(); list += HeifAssociation(id, v and 0x7F, v and 0x80 != 0)
                    }
                }
                order += id
                out += list
                if (out.size > HEIF_MAX_ENTRIES) { problems += "ipma@${b.offset}: $HEIF_COUNT_CAP_PROBLEM"; return }
            } catch (e: Truncated) {
                problems += "ipma@${b.offset}: 항목 ${i + 1}/$count 잘림 (${e.message})"
                return
            }
        }
        if (c.remaining > 0) problems += "ipma@${b.offset}: $HEIF_TRAILING_BYTES_PROBLEM ${c.remaining}"
    }

    /** Local bounded box loop for containers the ISO-BMFF walker does not descend into (iinf, iprp, ipco, iref). */
    private fun childBoxes(
        reader: ByteReader, start: Long, end: Long, parent: String, parentOffset: Long, problems: MutableList<String>,
    ): List<BmffBox> {
        val out = ArrayList<BmffBox>()
        var pos = start
        try {
            while (pos + 8 <= end) {
                if (out.size >= HEIF_MAX_ENTRIES) { problems += "$parent@$parentOffset: $HEIF_COUNT_CAP_PROBLEM"; break }
                val raw = reader.readUInt32(pos)
                val type = String(reader.readBytes(pos + 4, 4), Charsets.ISO_8859_1)
                var header = 8
                val size = when (raw) {
                    0L -> end - pos
                    1L -> {
                        if (pos + 16 > end) { problems += "$parent@$parentOffset: $type@$pos largesize 헤더가 범위를 넘음"; return out }
                        header = 16; reader.readUInt64(pos + 8)
                    }
                    else -> raw
                }
                if (size < header || size > end - pos) {
                    problems += "$parent@$parentOffset: 하위 박스 $type@$pos 크기($size)가 잘못됨"
                    return out
                }
                out += BmffBox(type, pos, header, size, raw, null, emptyList(), parent)
                pos += size
            }
            if (pos < end) problems += "$parent@$parentOffset: 하위 박스 뒤 남은 바이트 ${end - pos} (@$pos)"
        } catch (e: IOException) {
            problems += "$parent@$parentOffset: 읽기 오류 (${e.message})"
        }
        return out
    }
}
