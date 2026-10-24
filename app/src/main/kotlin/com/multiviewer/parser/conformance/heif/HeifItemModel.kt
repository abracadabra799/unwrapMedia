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
)

/** Count above which an item/property/association/reference table is not read (rules report NOT_CHECKED). */
const val HEIF_MAX_ENTRIES = 1_000_000L

/** Problem prefix used when a table count exceeds [HEIF_MAX_ENTRIES]. */
const val HEIF_COUNT_CAP_PROBLEM = "항목 수 상한 초과"

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
            problems += "${box.type}@${box.offset}: ${e.message}"; null
        } catch (e: IOException) {
            problems += "${box.type}@${box.offset}: 읽기 오류 (${e.message})"; null
        }

        val children = meta.children
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
                    for (infe in infes.filter { it.type == "infe" }) {
                        if (items.size >= HEIF_MAX_ENTRIES) break
                        guarded(infe) { readInfe(reader, infe) }?.let { items += it }
                    }
                    val infeCount = infes.count { it.type == "infe" }
                    if (infeCount.toLong() != count) problems += "iinf@${b.offset}: entry_count($count)와 infe 박스 수($infeCount)가 다름"
                }
            }
        }

        val locations = ArrayList<HeifLocation>()
        var ilocVersion: Int? = null
        first("iloc")?.let { b ->
            guarded(b) { ilocVersion = readIloc(reader, b, locations, problems) }
        }

        val properties = ArrayList<HeifProperty>()
        val associations = ArrayList<HeifAssociation>()
        val ipmaOrder = ArrayList<Long>()
        first("iprp")?.let { iprp ->
            val iprpChildren = childBoxes(reader, iprp.payload, iprp.end, "iprp", iprp.offset, problems)
            val ipcos = iprpChildren.filter { it.type == "ipco" }
            if (ipcos.size > 1) problems += "iprp@${iprp.offset}: ipco 박스가 ${ipcos.size}개"
            ipcos.firstOrNull()?.let { ipco ->
                childBoxes(reader, ipco.payload, ipco.end, "ipco", ipco.offset, problems).forEachIndexed { i, p ->
                    if (i < HEIF_MAX_ENTRIES) properties += HeifProperty(i + 1, p.type, p)
                }
            }
            for (ipma in iprpChildren.filter { it.type == "ipma" }) {
                guarded(ipma) { readIpma(reader, ipma, associations, ipmaOrder, problems) }
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
                    }
                }
            }
        }

        val idat = first("idat")

        return HeifModel(meta, metas.size, handler, primaryId, items, iinfEntryCount, locations, ilocVersion,
            properties, associations, ipmaOrder, references, idat, problems)
    }

    private fun readInfe(reader: ByteReader, b: BmffBox): HeifItemInfo {
        val c = Cursor(reader, b.payload, b.end, "infe@${b.offset}")
        val version = c.u8()
        val flags = (c.u8() shl 16) or (c.u8() shl 8) or c.u8()
        val id: Long
        var type = ""
        when {
            version < 2 -> { id = c.u16().toLong(); c.u16() }
            version == 2 -> { id = c.u16().toLong(); c.u16(); type = c.fourCC() }
            else -> { id = c.u32(); c.u16(); type = c.fourCC() }
        }
        return HeifItemInfo(id, type, version, flags and 1 != 0, b)
    }

    /** Returns the iloc version; appends the entries that parsed completely. */
    private fun readIloc(reader: ByteReader, b: BmffBox, out: MutableList<HeifLocation>, problems: MutableList<String>): Int {
        val c = Cursor(reader, b.payload, b.end, "iloc")
        val version = c.u8(); c.need(3); c.pos += 3
        if (version > 2) { problems += "iloc@${b.offset}: 지원하지 않는 버전 $version"; return version }
        val s1 = c.u8(); val s2 = c.u8()
        val offsetSize = s1 ushr 4; val lengthSize = s1 and 0xF
        val baseOffsetSize = s2 ushr 4; val indexSize = if (version >= 1) s2 and 0xF else 0
        val bad = listOf("offset_size" to offsetSize, "length_size" to lengthSize, "base_offset_size" to baseOffsetSize,
            "index_size" to indexSize).filter { it.second !in setOf(0, 4, 8) }
        if (bad.isNotEmpty()) {
            problems += "iloc@${b.offset}: 허용되지 않는 필드 크기 " + bad.joinToString { "${it.first}=${it.second}" }
            return version
        }
        val count = if (version < 2) c.u16().toLong() else c.u32()
        if (count > HEIF_MAX_ENTRIES) { problems += "iloc@${b.offset}: $HEIF_COUNT_CAP_PROBLEM ($count)"; return version }
        for (i in 0 until count) {
            try {
                val id = if (version < 2) c.u16().toLong() else c.u32()
                val method = if (version >= 1) c.u16() and 0xF else 0
                val dri = c.u16()
                val base = c.uN(baseOffsetSize)
                val extentCount = c.u16()
                val extents = ArrayList<HeifExtent>(minOf(extentCount, 4096))
                repeat(extentCount) {
                    if (indexSize > 0) c.uN(indexSize)
                    val off = c.uN(offsetSize)
                    val len = c.uN(lengthSize)
                    extents += HeifExtent(base + off, len)
                }
                out += HeifLocation(id, method, dri, base, extents)
            } catch (e: Truncated) {
                problems += "iloc@${b.offset}: 항목 ${i + 1}/$count 잘림 (${e.message})"
                break
            }
        }
        if (c.remaining > 0) problems += "iloc@${b.offset}: 항목 뒤 남은 바이트 ${c.remaining}"
        return version
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
        if (c.remaining > 0) problems += "ipma@${b.offset}: 항목 뒤 남은 바이트 ${c.remaining}"
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
