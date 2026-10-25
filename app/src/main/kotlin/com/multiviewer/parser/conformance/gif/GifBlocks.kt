package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** One block after the Logical Screen (Appendix B grammar). */
internal sealed class GifBlock {
    abstract val offset: Long
    abstract val end: Long

    /** 0x21 [label] + data sub-blocks; [first] = the first sub-block's bytes (the fixed-size "Block Size" part). */
    data class Extension(override val offset: Long, override val end: Long, val label: Int, val firstSize: Int, val first: ByteArray, val subBlocks: Int) : GifBlock()

    data class Image(
        override val offset: Long, override val end: Long,
        val left: Int, val top: Int, val width: Int, val height: Int, val packed: Int,
        val lzwMinCodeSize: Int, val dataBytes: Long, val head: ByteArray,
    ) : GifBlock() {
        val hasLct get() = packed and 0x80 != 0
        val sortFlag get() = packed and 0x20 != 0
        val reserved get() = (packed shr 3) and 0x03
        val lctSizeField get() = packed and 0x07
    }

    data class Trailer(override val offset: Long) : GifBlock() { override val end: Long get() = offset + 1 }
}

/**
 * Walk result: [problem] = why the walk stopped before a Trailer (unknown byte, truncation, cap); [cleanEof] = the file
 * simply ended between two blocks (only the Trailer is missing).
 */
internal class GifScan(val blocks: List<GifBlock>, val trailer: GifBlock.Trailer?, val problem: String?, val problemAt: Long?, val cleanEof: Boolean = false, val capped: Boolean = false)

internal class GifContext(val reader: ByteReader) {
    val length: Long = reader.length
    val book = GifRules.book
    val signature: String = if (length >= 3) String(reader.readBytes(0, 3), Charsets.ISO_8859_1) else ""
    val version: String = if (length >= 6) String(reader.readBytes(3, 3), Charsets.ISO_8859_1) else ""
    val hasLsd: Boolean = length >= 13
    val screenWidth: Int = if (hasLsd) u16(6) else 0
    val screenHeight: Int = if (hasLsd) u16(8) else 0
    val lsdPacked: Int = if (hasLsd) reader.readUInt8(10) else 0
    val backgroundIndex: Int = if (hasLsd) reader.readUInt8(11) else 0
    val hasGct: Boolean get() = lsdPacked and 0x80 != 0
    val gctBytes: Long get() = if (hasGct) 3L * (1 shl ((lsdPacked and 7) + 1)) else 0
    val dataStart: Long = 13 + gctBytes
    val scan: GifScan by lazy { if (hasLsd && dataStart <= length) GifBlockScanner.scan(reader, dataStart) else GifScan(emptyList(), null, null, null) }

    val images: List<GifBlock.Image> get() = scan.blocks.filterIsInstance<GifBlock.Image>()
    val extensions: List<GifBlock.Extension> get() = scan.blocks.filterIsInstance<GifBlock.Extension>()

    private fun u16(at: Long) = reader.readUInt8(at) or (reader.readUInt8(at + 1) shl 8)

    fun range(b: GifBlock): LongRange = b.offset until minOf(b.end, length)
}

internal object GifBlockScanner {
    const val MAX_BLOCKS = 1_000_000

    fun scan(reader: ByteReader, start: Long): GifScan {
        val len = reader.length
        val blocks = ArrayList<GifBlock>()
        var pos = start
        fun stop(text: String, cleanEof: Boolean = false) = GifScan(blocks, null, text, pos, cleanEof)
        while (true) {
            if (blocks.size >= MAX_BLOCKS) return GifScan(blocks, null, null, pos, capped = true)
            if (pos >= len) return stop("${hex(pos)}에서 파일 끝 — Trailer 없음 / end of file at ${hex(pos)} before a Trailer", cleanEof = true)
            when (val b = reader.readUInt8(pos)) {
                0x3B -> { val t = GifBlock.Trailer(pos); blocks += t; return GifScan(blocks, t, null, null) }
                0x21 -> {
                    if (pos + 2 > len) return stop("확장 레이블 전에 파일 끝 / end of file before the extension label")
                    val label = reader.readUInt8(pos + 1)
                    val chain = subBlocks(reader, pos + 2, if (label == 0xF9 || label == 0xFF) 16 else 0) ?: return stop("확장 0x%02X @${hex(pos)}의 서브블록이 Block Terminator 전에 잘림 / extension 0x%02X sub-blocks cut off before the Block Terminator".format(label, label))
                    blocks += GifBlock.Extension(pos, chain.end, label, chain.firstSize, chain.head, chain.count)
                    pos = chain.end
                }
                0x2C -> {
                    if (pos + 10 > len) return stop("이미지 설명자 @${hex(pos)}가 잘림 / Image Descriptor cut off")
                    val d = reader.readBytes(pos + 1, 9)
                    fun u16(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
                    val packed = d[8].toInt() and 0xFF
                    val lct = if (packed and 0x80 != 0) 3L * (1 shl ((packed and 7) + 1)) else 0L
                    val lzwAt = pos + 10 + lct
                    if (lzwAt >= len) return stop("이미지 @${hex(pos)}의 색상표/LZW 최소 코드 크기가 잘림 / Local Color Table or LZW Minimum Code Size cut off")
                    val chain = subBlocks(reader, lzwAt + 1, 2) ?: return stop("이미지 @${hex(pos)} 데이터 서브블록이 Block Terminator 전에 잘림 / image data sub-blocks cut off before the Block Terminator")
                    blocks += GifBlock.Image(pos, chain.end, u16(0), u16(2), u16(4), u16(6), packed, reader.readUInt8(lzwAt), chain.dataBytes, chain.head)
                    pos = chain.end
                }
                else -> return stop("${hex(pos)}의 0x%02X는 블록 시작(0x21, 0x2C, 0x3B)이 아님 / byte 0x%02X is not a block start (0x21, 0x2C, 0x3B)".format(b, b))
            }
        }
    }

    /** A data sub-block chain; [head] = the first [headBytes] data bytes across sub-blocks (for 0x2C) or the first sub-block (for 0x21). */
    class Chain(val end: Long, val count: Int, val firstSize: Int, val head: ByteArray, val dataBytes: Long)

    private fun subBlocks(reader: ByteReader, start: Long, headBytes: Int): Chain? {
        val len = reader.length
        var p = start
        var count = 0
        var firstSize = -1
        var data = 0L
        val head = java.io.ByteArrayOutputStream()
        val spanning = headBytes == 2
        while (true) {
            if (p >= len) return null
            val n = reader.readUInt8(p)
            if (firstSize < 0) firstSize = n
            p++
            if (n == 0) return Chain(p, count, firstSize, head.toByteArray(), data)
            if (p + n > len) return null
            if (spanning) { if (head.size() < headBytes) head.write(reader.readBytes(p, minOf(n, headBytes - head.size()))) }
            else if (count == 0 && headBytes > 0) head.write(reader.readBytes(p, minOf(n, headBytes)))
            count++; data += n; p += n
        }
    }
}
