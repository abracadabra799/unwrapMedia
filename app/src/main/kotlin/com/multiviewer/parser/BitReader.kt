package com.multiviewer.parser

// This codebase's first bit-level (as opposed to byte-aligned) bitstream reader -- needed because
// H.264 SPS/PPS/slice-header fields are packed as MSB-first fixed-width integers (u(n)) and
// Exp-Golomb codes (ue(v)/se(v)), unlike every existing ByteReader consumer which only reads
// whole bytes. Bit layout and Exp-Golomb decoding verified by hand against real H.264 bytes,
// cross-checked field-by-field with ffmpeg's own `-bsf:v trace_headers` output (see the design
// spec) before this was written.
class BitReader(private val data: ByteArray, startByteOffset: Int = 0) {
    private var bytePos = startByteOffset
    private var bitPos = 0 // 0..7 within the current byte; bit 0 is that byte's MSB.

    fun bitsRemaining(): Int = (data.size - bytePos) * 8 - bitPos

    // u(n): MSB-first fixed-width unsigned integer, count in 0..31 (fits an Int without sign
    // overflow -- 32-bit fields use readBits32 instead).
    fun readBits(count: Int): Int {
        require(count in 0..31) { "readBits count must be 0..31, got $count" }
        var result = 0
        repeat(count) {
            check(bytePos < data.size) { "BitReader ran past the end of its data" }
            val byte = data[bytePos].toInt() and 0xFF
            val bit = (byte shr (7 - bitPos)) and 1
            result = (result shl 1) or bit
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
        }
        return result
    }

    // u(32): assembled from two 16-bit reads rather than one 32-bit accumulation, so a value with
    // its top bit set (e.g. 0xFFFFFFFF) comes back as the correct positive Long instead of
    // overflowing a signed Int.
    fun readBits32(): Long = (readBits(16).toLong() shl 16) or readBits(16).toLong()

    fun readFlag(): Boolean = readBits(1) == 1

    // ue(v), per H.264 spec §9.1: count leading zero bits (the "prefix"), then read that many more
    // bits as the "suffix" -- value = 2^leadingZeroBits - 1 + suffix.
    fun readUe(): Int {
        var leadingZeroBits = 0
        while (readBits(1) == 0) {
            leadingZeroBits++
            check(leadingZeroBits <= 31) { "Exp-Golomb prefix too long -- likely corrupt data" }
        }
        if (leadingZeroBits == 0) return 0
        val suffix = readBits(leadingZeroBits)
        return (1 shl leadingZeroBits) - 1 + suffix
    }

    // se(v), per H.264 spec §9.1.1 table 9-3: maps ue(v)'s unsigned codeNum to a signed value --
    // even codeNum -> -(codeNum/2), odd codeNum -> (codeNum+1)/2.
    fun readSe(): Int {
        val codeNum = readUe()
        return if (codeNum % 2 == 0) -(codeNum / 2) else (codeNum + 1) / 2
    }
}
