package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.INFO
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN

/** Uses the JpegWalker tree: top-level nodes are markers ("SOI", "SOF0", "DQT", "SOS", "EOI", ...);
 *  after EOI the walker emits secondary images (SOI...), "sefd", "EmbeddedVideoData" or "?" (unknown). */
object JpegIntegrity {
    // Huffman-coded frames; SOF9-15 are arithmetic-coded and need no DHT.
    private val HUFFMAN_SOF = setOf("SOF0", "SOF1", "SOF2", "SOF3", "SOF5", "SOF6", "SOF7")

    fun check(root: BoxNode, reader: ByteReader): FormatCheckResult {
        val nodes = root.children
        val fileLength = reader.length
        val items = mutableListOf<IntegrityCheckItem>()

        items += if (nodes.firstOrNull()?.type == "SOI") {
            IntegrityCheckItem("jpeg.soi", "Start of image (SOI)", PASS, "SOI at offset 0", 0, 2)
        } else {
            IntegrityCheckItem("jpeg.soi", "Start of image (SOI)", FAIL, "The file does not start with an SOI marker", 0, 2)
        }

        val eoiIndex = nodes.indexOfFirst { it.type == "EOI" }
        val primary = if (eoiIndex >= 0) nodes.subList(0, eoiIndex) else nodes
        val sof = primary.firstOrNull { it.type.startsWith("SOF") }
        val sos = primary.firstOrNull { it.type == "SOS" }
        items += when {
            sof == null -> IntegrityCheckItem("jpeg.frame", "Frame and scan headers", FAIL, "No SOF (frame header) segment in the primary image")
            sos == null -> IntegrityCheckItem("jpeg.frame", "Frame and scan headers", FAIL, "No SOS (scan) segment in the primary image")
            sos.offset < sof.offset -> IntegrityCheckItem(
                "jpeg.frame", "Frame and scan headers", FAIL,
                "SOS at offset ${sos.offset} precedes ${sof.type} at offset ${sof.offset}", sos.offset, sos.size,
            )
            else -> IntegrityCheckItem(
                "jpeg.frame", "Frame and scan headers", PASS,
                "${sof.type} at offset ${sof.offset}, first SOS at offset ${sos.offset}", sof.offset, sof.size,
            )
        }

        if (sos != null) {
            val beforeScan = primary.filter { it.offset < sos.offset }
            items += if (beforeScan.any { it.type == "DQT" }) {
                IntegrityCheckItem("jpeg.dqt", "Quantization tables (DQT)", PASS, "DQT present before the first scan")
            } else {
                IntegrityCheckItem("jpeg.dqt", "Quantization tables (DQT)", FAIL, "No DQT segment before the first SOS", sos.offset, sos.size)
            }
            items += when {
                sof == null || sof.type !in HUFFMAN_SOF ->
                    IntegrityCheckItem("jpeg.dht", "Huffman tables (DHT)", SKIP, "Arithmetic coding: DHT not required")
                beforeScan.any { it.type == "DHT" } ->
                    IntegrityCheckItem("jpeg.dht", "Huffman tables (DHT)", PASS, "DHT present before the first scan")
                else -> IntegrityCheckItem(
                    "jpeg.dht", "Huffman tables (DHT)", WARN,
                    "No DHT before the first SOS; decoders must fall back to the standard (MJPEG) tables", sos.offset, sos.size,
                )
            }
        }

        if (eoiIndex < 0) {
            val last = nodes.lastOrNull()
            items += IntegrityCheckItem(
                "jpeg.eoi", "End of image (EOI)", FAIL,
                "No EOI marker: the data ends at offset $fileLength without terminating the image (truncated)",
                last?.offset, last?.size,
            )
        } else {
            val eoi = nodes[eoiIndex]
            items += IntegrityCheckItem("jpeg.eoi", "End of image (EOI)", PASS, "EOI at offset ${eoi.offset}", eoi.offset, eoi.size)
            items += trailingItem(nodes.drop(eoiIndex + 1), eoi.offset + eoi.size, fileLength)
        }

        // SOF layout: FF Cx, length(2), precision(1), height(2), width(2)
        val size = sof?.takeIf { it.size >= 9 }?.let { node ->
            val b = reader.readBytes(node.offset + 5, 4)
            b.u16be(2) to b.u16be(0)
        }
        return FormatCheckResult(items, size?.first, size?.second)
    }

    private fun trailingItem(trailing: List<BoxNode>, eoiEnd: Long, fileLength: Long): IntegrityCheckItem {
        if (trailing.isEmpty()) return IntegrityCheckItem("jpeg.trailing", "Data after EOI", PASS, "No data after EOI")
        val unknown = trailing.firstOrNull { it.type == "?" }
        if (unknown != null) {
            return IntegrityCheckItem(
                "jpeg.trailing", "Data after EOI", WARN,
                "${fileLength - unknown.offset} unrecognized byte(s) after the image at offset ${unknown.offset}",
                unknown.offset, fileLength - unknown.offset,
            )
        }
        val kinds = buildList {
            if (trailing.any { it.type == "SOI" }) add("embedded JPEG image(s) (MPF/secondary)")
            if (trailing.any { it.type == "sefd" }) add("Samsung SEF trailer")
            if (trailing.any { it.type == "EmbeddedVideoData" }) add("embedded motion photo video")
        }.ifEmpty { listOf("additional JPEG segments") }
        return IntegrityCheckItem(
            "jpeg.trailing", "Data after EOI", INFO,
            "Recognized data after EOI: ${kinds.joinToString(", ")}", eoiEnd, fileLength - eoiEnd,
        )
    }
}
