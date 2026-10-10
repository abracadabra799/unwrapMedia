package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.decodeGridItemPayload
import com.multiviewer.parser.extractItemBytes
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findItemProperty
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS

object HeifIntegrity {
    private const val MAX_EXTENT_ITEMS = 20

    fun check(root: BoxNode, reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()

        val ftyp = root.children.firstOrNull()
        items += if (ftyp != null && ftyp.type == "ftyp" && ftyp.size >= 12) {
            IntegrityCheckItem("heif.ftyp", "File type (ftyp)", PASS, "Major brand '${reader.readFourCC(ftyp.offset + 8)}'", ftyp.offset, ftyp.size)
        } else {
            IntegrityCheckItem("heif.ftyp", "File type (ftyp)", FAIL, "The first box is '${ftyp?.type}', expected ftyp", ftyp?.offset, ftyp?.size)
        }

        val meta = root.children.firstOrNull { it.type == "meta" }
        if (meta == null) {
            items += IntegrityCheckItem("heif.meta", "Item metadata (meta)", FAIL, "No top-level meta box: the file declares no image items")
            return FormatCheckResult(items)
        }
        fun child(type: String) = findFirst(meta) { it.type == type }
        val missing = listOf("hdlr", "pitm", "iinf", "iloc").filter { child(it) == null }
        items += if (missing.isEmpty()) {
            IntegrityCheckItem("heif.meta", "Item metadata (meta)", PASS, "hdlr, pitm, iinf and iloc present", meta.offset, meta.size)
        } else {
            IntegrityCheckItem("heif.meta", "Item metadata (meta)", FAIL, "meta is missing: ${missing.joinToString()}", meta.offset, meta.size)
        }
        child("hdlr")?.let { hdlr ->
            val handler = hdlr.field("handler_type")
            if (handler != null && handler != "pict") {
                items += IntegrityCheckItem("heif.hdlr", "Handler (hdlr)", FAIL, "Handler type is '$handler', expected 'pict'", hdlr.offset, hdlr.size)
            }
        }

        val itemTypes: Map<Long, String> = child("iinf")?.children.orEmpty()
            .filter { it.type == "infe" }
            .mapNotNull { n -> n.field("item_ID")?.toLongOrNull()?.let { it to (n.field("item_type") ?: "") } }
            .toMap()
        val primaryId = child("pitm")?.field("primary_item_ID")?.toLongOrNull()
        items += when {
            primaryId == null -> IntegrityCheckItem("heif.primary", "Primary item", FAIL, "pitm is missing or unreadable")
            primaryId !in itemTypes -> IntegrityCheckItem("heif.primary", "Primary item", FAIL, "Primary item $primaryId is not declared in iinf")
            else -> IntegrityCheckItem("heif.primary", "Primary item", PASS, "Primary item $primaryId (${itemTypes[primaryId]})")
        }

        val iloc = child("iloc")
        val idat = child("idat")
        if (iloc != null) items += extentItems(iloc, idat, len)

        val iref = child("iref")
        val idatBase = idat?.let { it.offset + it.headerSize } ?: 0L
        var declared: Pair<Int, Int>? = null
        for (gridId in itemTypes.filterValues { it == "grid" }.keys) {
            val tiles = iref?.children
                ?.firstOrNull { it.type == "dimg" && it.field("from_item_ID")?.toLongOrNull() == gridId }
                ?.fields?.filter { it.name.startsWith("to_item_ID") }?.mapNotNull { it.value.toLongOrNull() }
                .orEmpty()
            val layout = iloc?.let { runCatching { extractItemBytes(reader, it, gridId, idatBase) }.getOrNull() }
                ?.let { decodeGridItemPayload(it) }
            val undeclared = tiles.filter { it !in itemTypes }
            items += when {
                layout == null -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL, "The grid descriptor could not be read")
                tiles.size != layout.rows * layout.columns -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL,
                    "Grid ${layout.rows}x${layout.columns} needs ${layout.rows * layout.columns} tile(s) but dimg references ${tiles.size}")
                undeclared.isNotEmpty() -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL,
                    "Tile item(s) ${undeclared.joinToString()} referenced by dimg are not declared in iinf")
                else -> IntegrityCheckItem("heif.grid", "Grid item $gridId", PASS,
                    "${layout.rows}x${layout.columns} tiles, output ${layout.outputWidth}x${layout.outputHeight}")
            }
            if (gridId == primaryId && layout != null) declared = layout.outputWidth to layout.outputHeight
        }
        if (declared == null && primaryId != null) {
            findItemProperty(meta, primaryId, "ispe")?.let { ispe ->
                val w = ispe.field("image_width")?.toIntOrNull()
                val h = ispe.field("image_height")?.toIntOrNull()
                if (w != null && h != null) declared = w to h
            }
        }
        return FormatCheckResult(items, declared?.first, declared?.second)
    }

    private fun extentItems(iloc: BoxNode, idat: BoxNode?, len: Long): List<IntegrityCheckItem> {
        val bad = mutableListOf<IntegrityCheckItem>()
        var count = 0
        for (item in iloc.children) {
            val itemId = item.type.removePrefix("item_")
            val method = item.field("construction_method")?.toIntOrNull() ?: 0
            for (extent in item.children.filter { it.type == "extent" }) {
                count++
                val length = extent.field("length")?.toLongOrNull() ?: continue
                val absolute = extent.field("offset")?.toLongOrNull()
                when {
                    method == 0 && absolute != null -> {
                        val end = if (length == 0L) len else absolute + length
                        if (absolute > len || end > len) {
                            bad += IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                                "Item $itemId extent [$absolute, $end) exceeds the file size $len (${end - len} byte(s) missing)", extent.offset, extent.size)
                        }
                    }
                    method == 1 -> {
                        // "offset" is already absolute when MetaBoxDecoder resolved it; otherwise resolve the idat-relative value here.
                        val start = absolute ?: extent.field("idat_relative_offset")?.toLongOrNull()
                            ?.let { rel -> idat?.let { it.offset + it.headerSize + rel } }
                        val idatEnd = idat?.let { it.offset + it.size }
                        if (start == null || idatEnd == null || start + length > idatEnd) {
                            bad += IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                                "Item $itemId idat extent of $length byte(s) does not fit inside the idat box", extent.offset, extent.size)
                        }
                    }
                }
            }
        }
        return when {
            bad.isEmpty() -> listOf(IntegrityCheckItem("heif.iloc", "Item locations (iloc)", PASS, "$count extent(s) within the file", iloc.offset, iloc.size))
            bad.size > MAX_EXTENT_ITEMS -> bad.take(MAX_EXTENT_ITEMS) + IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                "…and ${bad.size - MAX_EXTENT_ITEMS} more extent(s) outside the file")
            else -> bad
        }
    }

    private fun BoxNode.field(name: String): String? = fields.firstOrNull { it.name == name }?.value
}
