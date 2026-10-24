package com.multiviewer.parser

private var registered = false

fun registerAllDecoders() {
    BoxRegistry.register("uuid", com.multiviewer.parser.c2pa.C2paUuidBoxDecoder)
    if (registered) return
    registered = true

    for (type in listOf("vmhd", "smhd", "btrt", "fiel")) {
        BoxRegistry.register(type, BasicMediaBoxDecoder)
    }
    BoxRegistry.register("esds", EsdsBoxDecoder)
    for (type in listOf("mfhd", "trex", "tfhd", "tfdt", "trun")) {
        BoxRegistry.register(type, FragmentBoxDetailsDecoder)
    }
    for (type in listOf("moof", "traf", "mvex", "mfra")) {
        BoxRegistry.register(type, ContainerBoxDecoder())
    }
    BoxRegistry.register("ftyp", FtypBoxDecoder)
    BoxRegistry.register("mvhd", MvhdBoxDecoder)
    BoxRegistry.register("tkhd", TkhdBoxDecoder)
    BoxRegistry.register("mdhd", MdhdBoxDecoder)
    BoxRegistry.register("hdlr", HdlrBoxDecoder)
    BoxRegistry.register("ispe", IspeBoxDecoder)
    BoxRegistry.register("avc1", VisualSampleEntryDecoder)
    BoxRegistry.register("hvc1", VisualSampleEntryDecoder)
    BoxRegistry.register("hev1", VisualSampleEntryDecoder)
    BoxRegistry.register("avc3", VisualSampleEntryDecoder)
    BoxRegistry.register("av01", VisualSampleEntryDecoder)
    BoxRegistry.register("av02", VisualSampleEntryDecoder)
    BoxRegistry.register("apv1", VisualSampleEntryDecoder)
    BoxRegistry.register("vp09", VisualSampleEntryDecoder)
    BoxRegistry.register("vvc1", VisualSampleEntryDecoder)
    BoxRegistry.register("vvi1", VisualSampleEntryDecoder)
    BoxRegistry.register("vvcC", VvcCBoxDecoder)
    BoxRegistry.register("mp4a", AudioSampleEntryDecoder)
    for (type in listOf("lpcm", "sowt", "twos", "fl32", "fl64", "in24", "in32", "alaw", "ulaw"))
        BoxRegistry.register(type, AudioSampleEntryDecoder)
    for (type in listOf("chan", "frma", "enda")) BoxRegistry.register(type, QuickTimeSoundExtensionDecoder)
    BoxRegistry.register("Opus", AudioSampleEntryDecoder)
    BoxRegistry.register("dOps", DopsBoxDecoder)
    BoxRegistry.register("fLaC", AudioSampleEntryDecoder)
    BoxRegistry.register("dfLa", DflaBoxDecoder)
    BoxRegistry.register("wave", QuickTimeWaveBoxDecoder)
    BoxRegistry.register("avcC", AvcCBoxDecoder)
    BoxRegistry.register("hvcC", HvcCBoxDecoder)
    BoxRegistry.register("lhvC", com.multiviewer.parser.spatial.LhvcBoxDecoder)
    BoxRegistry.register("eyes", com.multiviewer.parser.spatial.EyesBoxDecoder)
    BoxRegistry.register("hero", com.multiviewer.parser.spatial.HeroBoxDecoder)
    BoxRegistry.register("st3d", com.multiviewer.parser.spatial.St3dBoxDecoder)
    BoxRegistry.register("vwdi", com.multiviewer.parser.spatial.VwdiBoxDecoder)
    BoxRegistry.register("av1C", Av1CBoxDecoder)
    BoxRegistry.register("av2C", Av2CBoxDecoder)
    BoxRegistry.register("apvC", ApvCBoxDecoder)
    BoxRegistry.register("vpcC", VpcCBoxDecoder)
    BoxRegistry.register("dvcC", DolbyVisionConfigDecoder)
    BoxRegistry.register("dvvC", DolbyVisionConfigDecoder)
    BoxRegistry.register("elst", ElstBoxDecoder)
    BoxRegistry.register("dref", CountedBoxDetailsDecoder)
    BoxRegistry.register("url ", UrlBoxDecoder)
    BoxRegistry.register("urn ", UrnBoxDecoder)
    BoxRegistry.register("colr", ColrBoxDecoder)
    BoxRegistry.register("mdcv", MdcvBoxDecoder)
    BoxRegistry.register("clli", ClliBoxDecoder)
    BoxRegistry.register("pasp", PaspBoxDecoder)
    BoxRegistry.register("irot", IrotBoxDecoder)
    BoxRegistry.register("imir", ImirBoxDecoder)
    BoxRegistry.register("pixi", PixiBoxDecoder)
    BoxRegistry.register("auxC", AuxCBoxDecoder)
    BoxRegistry.register("iinf", IinfBoxDecoder)
    BoxRegistry.register("infe", InfeBoxDecoder)
    BoxRegistry.register("pitm", PitmBoxDecoder)
    BoxRegistry.register("ipma", IpmaBoxDecoder)

    BoxRegistry.register("stsd", CountedBoxDetailsDecoder)
    BoxRegistry.register("sbgp", CountedBoxDetailsDecoder)
    BoxRegistry.register("sdtp", SampleDetailsDecoder)
    BoxRegistry.register("sgpd", SampleDetailsDecoder)
    BoxRegistry.register("meta", MetaBoxDecoder)
    BoxRegistry.register("iref", IrefBoxDecoder)
    for (type in listOf("nam", "swr", "cpy", "ART", "alb", "day", "cmt", "gen", "wrt", "too", "arg", "ark", "cok", "com", "dir", "fmt", "inf", "isr", "lab", "lal", "mak", "mal", "nak", "pdk", "phg", "prd", "prf", "prk", "prl", "req", "snk", "snm", "src", "swf", "swk"))
        BoxRegistry.register("\u00a9$type", QuickTimeLegacyTextDecoder)
    BoxRegistry.register("keys", QuickTimeKeysBoxDecoder)
    BoxRegistry.register("ilst", QuickTimeIlstBoxDecoder)
    BoxRegistry.register("data", QuickTimeDataBoxDecoder)
    BoxRegistry.register("trak", TrakBoxDecoder)
    for (containerType in listOf("moov", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco", "mpvd")) {
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
