package com.multiviewer.parser

// Task 3 gives this registered decoder AV2-specific structural parsing. Keeping the dedicated
// type from the registration step prevents an AV2 configuration box from silently using the
// generic fallback in the meantime.
object Av2CBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode = BoxNode(type, offset, headerSize, size, warnings = warnings)
}
