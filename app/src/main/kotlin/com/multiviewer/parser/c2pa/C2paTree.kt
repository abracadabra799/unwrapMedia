package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxDecoder
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.findFirst

private const val BOUNDARY_WARNING = "Spans an APP11 segment boundary; the hex highlight includes continuation-segment header bytes"

fun jumbfToBoxNodes(boxes: List<JumbfBox>, map: C2paOffsetMap, store: ByteArray): List<BoxNode> = boxes.map { box ->
    val fileOffset = map.toFile(box.offset)
    val physicalSize = if (box.size == 0) 0L else map.toFile(box.payloadEnd - 1) + 1 - fileOffset
    val warnings = box.warnings + if (map.crossesSpan(box.offset, box.size)) listOf(BOUNDARY_WARNING) else emptyList()
    val d = box.description
    if (box.type == "jumb" && d != null) {
        val jumdFile = map.toFile(box.payloadStart)
        val fields = buildList {
            add(BoxField("label", d.label ?: "", map.toFile(box.payloadStart + 8 + 17), 0))
            add(BoxField("content_type", d.contentTypeName, map.toFile(box.payloadStart + 8), 16))
            add(BoxField("toggles", "0x%02X".format(d.toggles), map.toFile(box.payloadStart + 8 + 16), 1))
            d.id?.let { add(BoxField("id", it.toString(), jumdFile, 0)) }
            d.privateBoxType?.let { add(BoxField("private_box", it, jumdFile, 0)) }
        }
        val jumdNode = BoxNode(type = "jumd", offset = jumdFile, headerSize = 8, size = descriptionSize(store, box))
        BoxNode(
            type = box.type, offset = fileOffset, headerSize = box.headerSize, size = physicalSize,
            children = listOf(jumdNode) + jumbfToBoxNodes(box.children, map, store),
            fields = fields, warnings = warnings, summary = d.label,
        )
    } else {
        BoxNode(
            type = box.type, offset = fileOffset, headerSize = box.headerSize, size = physicalSize,
            children = jumbfToBoxNodes(box.children, map, store), warnings = warnings, summary = leafSummary(box, store),
        )
    }
}

// The jumd box is the first inner box of a superbox; its LBox gives its size.
private fun descriptionSize(store: ByteArray, superbox: JumbfBox): Long {
    val at = superbox.payloadStart
    if (at + 4 > store.size) return 0
    return ((store[at].toLong() and 0xFF) shl 24) or ((store[at + 1].toLong() and 0xFF) shl 16) or
        ((store[at + 2].toLong() and 0xFF) shl 8) or (store[at + 3].toLong() and 0xFF)
}

private fun leafSummary(box: JumbfBox, store: ByteArray): String? = when (box.type) {
    "cbor" -> try {
        val v = decodeCbor(store, box.payloadStart, box.payloadEnd)
        (v as? CborMap)?.entries?.joinToString(", ") { (k, _) -> k.asText() ?: k.toDiagnostic(8) } ?: v.toDiagnostic(16).take(80)
    } catch (e: CborException) {
        "CBOR decode failed: ${e.message}"
    }
    "bfdb" -> if (box.payloadEnd - box.payloadStart > 1) {
        val start = box.payloadStart + 1
        var nul = start
        while (nul < box.payloadEnd && store[nul] != 0.toByte()) nul++
        String(store, start, nul - start, Charsets.UTF_8)
    } else null
    "json" -> "${box.payloadEnd - box.payloadStart} bytes JSON"
    "bidb" -> "${box.payloadEnd - box.payloadStart} bytes"
    else -> null
}

fun attachC2paToJpegSegments(reader: ByteReader, segments: List<BoxNode>): List<BoxNode> {
    if (segments.none { it.type == "APP11" }) return segments
    return try {
        val withFields = segments.map { seg ->
            if (seg.type != "APP11" || seg.size < 12) return@map seg
            if (reader.readFourCC(seg.offset + 4).substring(0, 2) != "JP") return@map seg
            seg.copy(
                fields = seg.fields + listOf(
                    BoxField("CI", "JP", seg.offset + 4, 2),
                    BoxField("En", reader.readUInt16(seg.offset + 6).toString(), seg.offset + 6, 2),
                    BoxField("Z", reader.readUInt32(seg.offset + 8).toString(), seg.offset + 8, 4),
                ),
            )
        }
        val loc = readJpegC2paStore(reader, withFields.filter { it.type == "APP11" }.map { it.offset }) ?: return withFields
        val anchor = loc.anchorOffsets.first()
        val nodes = jumbfToBoxNodes(parseJumbfBoxes(loc.bytes), loc.offsetMap, loc.bytes)
        withFields.map { seg ->
            if (seg.offset != anchor) seg
            else seg.copy(
                children = nodes,
                summary = "C2PA manifest store (${loc.anchorOffsets.size} segment(s))",
                warnings = seg.warnings + loc.warnings,
            )
        }
    } catch (e: Exception) {
        segments.map { if (it.type == "APP11") it.copy(warnings = it.warnings + "C2PA parsing failed: ${e.message}") else it }
    }
}

fun attachC2paToWebpChunk(reader: ByteReader, chunk: BoxNode): BoxNode {
    if (chunk.type != "C2PA") return chunk
    return try {
        val loc = readWebpC2paStore(reader, chunk.offset, chunk.size - 8) ?: return chunk
        chunk.copy(
            children = jumbfToBoxNodes(parseJumbfBoxes(loc.bytes), loc.offsetMap, loc.bytes),
            summary = "C2PA manifest store",
            warnings = chunk.warnings + loc.warnings,
        )
    } catch (e: Exception) {
        chunk.copy(warnings = chunk.warnings + "C2PA parsing failed: ${e.message}")
    }
}

object C2paUuidBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payload = offset + headerSize
        if (size - headerSize < 16) return BoxNode(type, offset, headerSize, size, warnings = warnings)
        val usertype = reader.readBytes(payload, 16)
        val uuidField = BoxField("usertype", uuidString(usertype), payload, 16)
        if (!usertype.contentEquals(C2PA_UUID)) return BoxNode(type, offset, headerSize, size, fields = listOf(uuidField), warnings = warnings)
        return try {
            val fields = mutableListOf(uuidField)
            if (size - headerSize >= 20) fields += BoxField("version_flags", "%08x".format(reader.readUInt32(payload + 16)), payload + 16, 4)
            var q = payload + 20
            val sb = StringBuilder()
            while (q < offset + size && reader.readUInt8(q) != 0) { sb.append(reader.readUInt8(q).toChar()); q++ }
            val purposeStart = payload + 20
            fields += BoxField("purpose", sb.toString(), purposeStart, (q - purposeStart + 1))
            q++
            if (q + 8 <= offset + size) fields += BoxField("merkle_offset", reader.readUInt64(q).toString(), q, 8)
            val loc = readBmffC2paStore(reader, offset, headerSize, size)
            val children = loc?.let { jumbfToBoxNodes(parseJumbfBoxes(it.bytes), it.offsetMap, it.bytes) } ?: emptyList()
            BoxNode(type, offset, headerSize, size, children = children, fields = fields, warnings = warnings + (loc?.warnings ?: emptyList()), summary = "C2PA (${sb})")
        } catch (e: Exception) {
            BoxNode(type, offset, headerSize, size, fields = listOf(uuidField), warnings = warnings + "C2PA parsing failed: ${e.message}")
        }
    }
}

private fun uuidString(b: ByteArray): String {
    val h = b.joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

fun hasC2paManifestStore(root: BoxNode): Boolean =
    findFirst(root) { n -> n.type == "jumb" && n.fields.any { it.name == "label" && it.value == "c2pa" } } != null
