package com.multiviewer.parser

const val MAX_SEI_PAYLOAD_BYTES = 64 * 1024

// payload holds at most maxPayloadBytes of RBSP (emulation prevention removed); payloadSize is the
// declared size. Compare payload contents with toList() -- ByteArray has identity equality.
data class SeiMessage(
    val payloadType: Int,
    val payloadSize: Int,
    val payload: ByteArray,
    val malformedReason: String? = null,
)

// Splits one SEI NAL (H.264 7.3.2.3 / HEVC 7.3.2.4) into sei_message()s. payloadType and
// payloadSize are each a run of 0xFF bytes (255 each) ended by one byte < 0xFF. Stops at
// rbsp_trailing_bits (0x80 followed only by zero bytes) or at the first size that runs past the NAL.
fun splitSeiMessages(nal: ByteArray, nalHeaderSize: Int, maxPayloadBytes: Int = MAX_SEI_PAYLOAD_BYTES): List<SeiMessage> {
    if (nal.size <= nalHeaderSize) return emptyList()
    val rbsp = removeEmulationPreventionBytes(nal.copyOfRange(nalHeaderSize, nal.size))
    val messages = mutableListOf<SeiMessage>()
    var pos = 0
    while (moreRbspData(rbsp, pos)) {
        var type = 0
        while (pos < rbsp.size && rbsp[pos] == 0xFF.toByte()) { type += 255; pos++ }
        if (pos >= rbsp.size) { messages += SeiMessage(type, 0, ByteArray(0), "payloadType runs past the NAL end"); break }
        type += rbsp[pos++].toInt() and 0xFF
        var size = 0
        while (pos < rbsp.size && rbsp[pos] == 0xFF.toByte()) { size += 255; pos++ }
        if (pos >= rbsp.size) { messages += SeiMessage(type, 0, ByteArray(0), "payloadSize runs past the NAL end"); break }
        size += rbsp[pos++].toInt() and 0xFF
        val available = rbsp.size - pos
        if (size > available) {
            messages += SeiMessage(
                type, size, rbsp.copyOfRange(pos, pos + minOf(available, maxPayloadBytes)),
                "payloadSize $size exceeds the $available bytes left in the NAL",
            )
            break
        }
        messages += SeiMessage(type, size, rbsp.copyOfRange(pos, pos + minOf(size, maxPayloadBytes)))
        pos += size
    }
    return messages
}

private fun moreRbspData(rbsp: ByteArray, pos: Int): Boolean {
    if (pos >= rbsp.size) return false
    if (rbsp[pos] != 0x80.toByte()) return true
    for (i in pos + 1 until rbsp.size) if (rbsp[i] != 0.toByte()) return true
    return false
}
