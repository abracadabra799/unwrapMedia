package com.multiviewer.parser

class TestBitWriter {
    private val bits = ArrayList<Int>()

    fun u(count: Int, value: Long): TestBitWriter {
        for (i in count - 1 downTo 0) bits += ((value shr i) and 1L).toInt()
        return this
    }

    fun toBytes(): ByteArray {
        val padded = bits + List((8 - bits.size % 8) % 8) { 0 }
        return ByteArray(padded.size / 8) { byteIndex ->
            var b = 0
            for (k in 0 until 8) b = (b shl 1) or padded[byteIndex * 8 + k]
            b.toByte()
        }
    }
}

fun hex(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
