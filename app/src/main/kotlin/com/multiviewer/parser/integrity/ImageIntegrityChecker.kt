package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.collectWarnings
import java.io.File

object ImageIntegrityChecker {
    fun check(file: File, root: BoxNode): ImageStructureReport =
        ByteReader.open(file).use { check(root, it) }

    fun check(root: BoxNode, reader: ByteReader): ImageStructureReport {
        val format = detectImageFormat(reader)
        val result = try {
            when (format) {
                "JPEG" -> JpegIntegrity.check(root, reader)
                "PNG" -> PngIntegrity.check(reader)
                else -> FormatCheckResult(
                    listOf(IntegrityCheckItem("format", "Format", CheckStatus.SKIP, "Unrecognized image format; structure checks are not available")),
                )
            }
        } catch (e: Exception) {
            FormatCheckResult(
                listOf(
                    IntegrityCheckItem(
                        "structure.error", "Structure interpretation", CheckStatus.FAIL,
                        "The structure could not be interpreted: ${e.message ?: e.toString()}",
                    ),
                ),
            )
        }
        return ImageStructureReport(format, result.items + parserWarningItems(root), result.declaredWidth, result.declaredHeight)
    }

    internal fun parserWarningItems(root: BoxNode): List<IntegrityCheckItem> {
        val warnings = collectWarnings(root)
        if (warnings.isEmpty()) {
            return listOf(IntegrityCheckItem("parser.warnings", "Parser warnings", CheckStatus.PASS, "No parser warnings"))
        }
        return warnings.map { w ->
            IntegrityCheckItem("parser.warning", "Parser warning (${w.node.type})", CheckStatus.WARN, w.warning, w.node.offset, w.node.size)
        }
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Magic-byte detection, same precedence as parseFile. */
fun detectImageFormat(reader: ByteReader): String {
    val len = reader.length
    if (len < 4) return "UNKNOWN"
    val head = reader.readBytes(0, minOf(len, 12L).toInt())
    fun ascii(from: Int, n: Int) = if (head.size >= from + n) String(head, from, n, Charsets.US_ASCII) else ""
    return when {
        head.u8(0) == 0xFF && head.u8(1) == 0xD8 -> "JPEG"
        head.size >= 8 && head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "PNG"
        ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a" -> "GIF"
        ascii(0, 2) == "BM" -> "BMP"
        (head.u8(0) == 0x49 && head.u8(1) == 0x49 && head.u8(2) == 0x2A && head.u8(3) == 0) ||
            (head.u8(0) == 0x4D && head.u8(1) == 0x4D && head.u8(2) == 0 && head.u8(3) == 0x2A) -> "TIFF"
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "WEBP"
        ascii(4, 4) == "ftyp" -> "HEIF"
        else -> "UNKNOWN"
    }
}
