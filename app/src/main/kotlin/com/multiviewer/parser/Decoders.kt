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
    BoxRegistry.register("avc1", VisualSampleEntryDecoder)
    BoxRegistry.register("hvc1", VisualSampleEntryDecoder)
    BoxRegistry.register("av01", VisualSampleEntryDecoder)
    BoxRegistry.register("mp4a", AudioSampleEntryDecoder)
    BoxRegistry.register("avcC", AvcCBoxDecoder)
    BoxRegistry.register("hvcC", HvcCBoxDecoder)
    BoxRegistry.register("elst", ElstBoxDecoder)
    BoxRegistry.register("dref", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
    BoxRegistry.register("url ", UrlBoxDecoder)
    BoxRegistry.register("urn ", UrnBoxDecoder)
    BoxRegistry.register("colr", ColrBoxDecoder)
    BoxRegistry.register("pasp", PaspBoxDecoder)
    BoxRegistry.register("iinf", IinfBoxDecoder)
    BoxRegistry.register("infe", InfeBoxDecoder)
    BoxRegistry.register("pitm", PitmBoxDecoder)
    BoxRegistry.register("ipma", IpmaBoxDecoder)

    BoxRegistry.register("stsd", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
    BoxRegistry.register("meta", MetaBoxDecoder)
    BoxRegistry.register("iref", IrefBoxDecoder)
    for (containerType in listOf("moov", "trak", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco", "mpvd")) {
        BoxRegistry.register(containerType, ContainerBoxDecoder())
    }
    BoxRegistry.register("sefd", SefdBoxDecoder)
    BoxRegistry.register("iloc", IlocBoxDecoder)

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
