package com.multiviewer.parser

interface BoxDecoder {
    fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode
}

object LeafBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode = BoxNode(type = type, offset = offset, headerSize = headerSize, size = size, warnings = warnings)
}

object BoxRegistry {
    private val decoders = mutableMapOf<String, BoxDecoder>()

    fun register(type: String, decoder: BoxDecoder) {
        decoders[type] = decoder
    }

    fun decoderFor(type: String): BoxDecoder = decoders[type] ?: LeafBoxDecoder
}
