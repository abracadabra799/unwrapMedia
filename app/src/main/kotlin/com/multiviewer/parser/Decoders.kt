package com.multiviewer.parser

private var registered = false

fun registerAllDecoders() {
    if (registered) return
    registered = true

    BoxRegistry.register("ftyp", FtypBoxDecoder)
    BoxRegistry.register("mvhd", MvhdBoxDecoder)
    BoxRegistry.register("tkhd", TkhdBoxDecoder)
    BoxRegistry.register("mdhd", MdhdBoxDecoder)
    BoxRegistry.register("hdlr", HdlrBoxDecoder)
    BoxRegistry.register("ispe", IspeBoxDecoder)

    BoxRegistry.register("stsd", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
    BoxRegistry.register("meta", ContainerBoxDecoder(childOffsetInPayload = 4))
    for (containerType in listOf("moov", "trak", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco")) {
        BoxRegistry.register(containerType, ContainerBoxDecoder())
    }

    BoxRegistry.register("stts", FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4, 4)))
    BoxRegistry.register(
        "stsc",
        FixedWidthTableDecoder(listOf("first_chunk", "samples_per_chunk", "sample_description_index"), listOf(4, 4, 4)),
    )
    BoxRegistry.register("stco", FixedWidthTableDecoder(listOf("chunk_offset"), listOf(4)))
    BoxRegistry.register("co64", FixedWidthTableDecoder(listOf("chunk_offset"), listOf(8)))
    BoxRegistry.register("stss", FixedWidthTableDecoder(listOf("sample_number"), listOf(4)))
    BoxRegistry.register("ctts", FixedWidthTableDecoder(listOf("sample_count", "sample_offset"), listOf(4, 4)))
    BoxRegistry.register("stsz", StszBoxDecoder)
}
