package com.multiviewer.parser

object PixiBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for pixi num_channels")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val numChannels = reader.readUInt8(payloadStart)
        if (payloadEnd - (payloadStart + 1) < numChannels) {
            w.add("pixi declares $numChannels channel(s) but not enough bytes remain")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bits = (0 until numChannels).map { reader.readUInt8(payloadStart + 1 + it) }
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("bits_per_channel", bits.joinToString(", "), payloadStart + 1, numChannels.toLong())),
            warnings = w, summary = bits.joinToString(", ") { "${it}-bit" },
        )
    }
}
