package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.conformance.isobmff.be16
import com.multiviewer.parser.conformance.isobmff.be32
import com.multiviewer.parser.conformance.isobmff.box
import com.multiviewer.parser.conformance.isobmff.fullBox

internal data class HeifItem(
    val id: Long, val type: String, val hidden: Boolean = false,
    val data: ByteArray? = ByteArray(16) { 5 }, val method: Int = 0, val infeVersion: Int = 2,
)

internal fun hvcC() = box("hvcC", ByteArray(23))
internal fun av1C() = box("av1C", byteArrayOf(0x81.toByte(), 0, 0, 0))
internal fun ispe(w: Long, h: Long) = fullBox("ispe", 0, 0, be32(w), be32(h))
internal fun pixi(vararg bits: Int) = fullBox("pixi", 0, 0, byteArrayOf(bits.size.toByte()), bits.map { it.toByte() }.toByteArray())
internal fun irot(angle: Int) = box("irot", byteArrayOf(angle.toByte()))
internal fun pasp(h: Long, v: Long) = box("pasp", be32(h), be32(v))
internal fun auxC(urn: String) = fullBox("auxC", 0, 0, urn.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
internal fun gridData(rows: Int, cols: Int, w: Int, h: Int) =
    byteArrayOf(0, 0, (rows - 1).toByte(), (cols - 1).toByte()) + be16(w) + be16(h)

/** A minimal valid single-item HEIC; every field can be overridden to inject exactly one violation. */
internal data class HeifSpec(
    val major: String = "heic",
    val compatible: List<String> = listOf("mif1", "heic"),
    val items: List<HeifItem> = listOf(HeifItem(1, "hvc1")),
    val primary: Long = 1,
    val props: List<ByteArray> = listOf(hvcC(), ispe(64, 48)),
    val assoc: List<Pair<Long, List<Pair<Int, Boolean>>>> = listOf(1L to listOf(1 to true, 2 to false)), // (1-based index, essential)
    val refs: List<Triple<String, Long, List<Long>>> = emptyList(),
    val handler: String = "pict",
    val dropMetaChild: String? = null,       // "hdlr" | "pitm" | "iinf" | "iloc" | "iprp"
    val ilocVersion: Int = 1,
    val ilocExtentDelta: Long = 0,            // added to every method-0 extent offset
    val extraMetaChildren: List<ByteArray> = emptyList(),
    val extraTop: List<ByteArray> = emptyList(),
)

internal fun buildHeif(s: HeifSpec = HeifSpec()): ByteArray {
    val ftyp = box("ftyp", s.major.toByteArray(), be32(0), *s.compatible.map { it.toByteArray() }.toTypedArray())
    val idatItems = s.items.filter { it.method == 1 && it.data != null }
    val idatPayload = idatItems.fold(ByteArray(0)) { a, it -> a + it.data!! }
    val mdatItems = s.items.filter { it.method == 0 && it.data != null }
    val mdatPayload = mdatItems.fold(ByteArray(0)) { a, it -> a + it.data!! }

    fun meta(mdatStart: Long): ByteArray {
        val hdlr = fullBox("hdlr", 0, 0, be32(0), s.handler.toByteArray(), ByteArray(12), byteArrayOf(0))
        val pitm = fullBox("pitm", 0, 0, be16(s.primary.toInt()))
        val infes = s.items.map { item ->
            fullBox("infe", item.infeVersion, if (item.hidden) 1 else 0, be16(item.id.toInt()), be16(0), item.type.toByteArray(), byteArrayOf(0))
        }
        val iinf = fullBox("iinf", 0, 0, be16(s.items.size), *infes.toTypedArray())
        var mdatCursor = mdatStart
        var idatCursor = 0L
        val located = s.items.filter { it.data != null }
        val ilocEntries = located.map { item ->
            val offset = if (item.method == 0) (mdatCursor + s.ilocExtentDelta).also { mdatCursor += item.data!!.size } else idatCursor.also { idatCursor += item.data!!.size }
            be16(item.id.toInt()) + (if (s.ilocVersion >= 1) be16(item.method) else ByteArray(0)) + be16(0) + be16(1) + be32(offset) + be32(item.data!!.size.toLong())
        }
        val iloc = fullBox("iloc", s.ilocVersion, 0, byteArrayOf(0x44, 0x00), be16(located.size), *ilocEntries.toTypedArray())
        val ipco = box("ipco", *s.props.toTypedArray())
        val ipmaEntries = s.assoc.map { (id, list) ->
            be16(id.toInt()) + byteArrayOf(list.size.toByte()) + list.map { (idx, ess) -> ((if (ess) 0x80 else 0) or idx).toByte() }.toByteArray()
        }
        val ipma = fullBox("ipma", 0, 0, be32(s.assoc.size.toLong()), *ipmaEntries.toTypedArray())
        val iprp = box("iprp", ipco, ipma)
        val iref = if (s.refs.isEmpty()) ByteArray(0) else fullBox("iref", 0, 0, *s.refs.map { (type, from, to) ->
            box(type, be16(from.toInt()), be16(to.size), *to.map { be16(it.toInt()) }.toTypedArray())
        }.toTypedArray())
        val idat = if (idatItems.isEmpty()) ByteArray(0) else box("idat", idatPayload)
        fun keep(name: String, b: ByteArray) = if (s.dropMetaChild == name) ByteArray(0) else b
        return fullBox("meta", 0, 0, keep("hdlr", hdlr), keep("pitm", pitm), keep("iinf", iinf), keep("iloc", iloc),
            keep("iprp", iprp), iref, idat, *s.extraMetaChildren.toTypedArray())
    }
    val head = ftyp + s.extraTop.fold(ByteArray(0)) { a, b -> a + b }
    val probe = meta(0)
    val metaBytes = meta(head.size + probe.size + 8L)
    return head + metaBytes + box("mdat", mdatPayload)
}
