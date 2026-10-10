package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN
import kotlin.math.abs

object BmpIntegrity {
    private val UNCOMPRESSED = setOf(0L, 3L, 6L) // BI_RGB, BI_BITFIELDS, BI_ALPHABITFIELDS

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        if (len < 26) {
            return FormatCheckResult(listOf(IntegrityCheckItem("bmp.header", "Headers", FAIL,
                "The file is too short ($len bytes) for BMP headers", 0, len)))
        }
        val h = reader.readBytes(0, minOf(len, 54L).toInt())
        val bfSize = h.u32le(2)
        val offBits = h.u32le(10)
        val dibSize = h.u32le(14)
        val core = dibSize == 12L
        val items = mutableListOf<IntegrityCheckItem>()

        items += when {
            bfSize == len -> IntegrityCheckItem("bmp.size", "Declared file size", PASS, "bfSize matches the file size ($len)", 2, 4)
            bfSize == 0L -> IntegrityCheckItem("bmp.size", "Declared file size", WARN, "bfSize is 0 (not filled in by the writer)", 2, 4)
            bfSize < len -> IntegrityCheckItem("bmp.size", "Declared file size", WARN,
                "bfSize $bfSize is smaller than the file ($len): ${len - bfSize} extra byte(s)", 2, 4)
            else -> IntegrityCheckItem("bmp.size", "Declared file size", FAIL,
                "bfSize $bfSize exceeds the file size $len (truncated by ${bfSize - len} byte(s))", 2, 4)
        }
        val headersEnd = 14 + dibSize
        items += if (offBits in headersEnd until len) {
            IntegrityCheckItem("bmp.offset", "Pixel data offset", PASS, "Pixel data starts at offset $offBits", 10, 4)
        } else {
            IntegrityCheckItem("bmp.offset", "Pixel data offset", FAIL, "bfOffBits $offBits is outside the valid range [$headersEnd, $len)", 10, 4)
        }
        if (!core && h.size < 34) {
            items += IntegrityCheckItem("bmp.header", "Headers", FAIL, "The DIB header is truncated", 14, len - 14)
            return FormatCheckResult(items)
        }
        val width = if (core) h.u16le(18) else h.u32le(18).toInt()
        val height = abs(if (core) h.u16le(20).toShort().toInt() else h.u32le(22).toInt())
        val bpp = if (core) h.u16le(24) else h.u16le(28)
        val compression = if (core) 0L else h.u32le(30)
        items += if (compression in UNCOMPRESSED) {
            val stride = ((width.toLong() * bpp + 31) / 32) * 4
            val needed = stride * height
            val available = (len - offBits).coerceAtLeast(0)
            if (available >= needed) {
                IntegrityCheckItem("bmp.pixels", "Pixel array size", PASS, "$needed byte(s) needed, $available available", offBits, needed)
            } else {
                IntegrityCheckItem("bmp.pixels", "Pixel array size", FAIL,
                    "The pixel array needs $needed byte(s) (${width}x$height, $bpp bpp) but only $available remain (truncated)", offBits, available)
            }
        } else {
            IntegrityCheckItem("bmp.pixels", "Pixel array size", SKIP, "Compressed pixel data (compression=$compression): size not computable")
        }
        return FormatCheckResult(items, width, height)
    }
}
