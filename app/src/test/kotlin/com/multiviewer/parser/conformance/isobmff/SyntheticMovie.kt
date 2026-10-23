package com.multiviewer.parser.conformance.isobmff

internal fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
internal fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
internal fun box(type: String, vararg payload: ByteArray): ByteArray {
    val body = payload.fold(ByteArray(0)) { a, b -> a + b }
    return be32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
}
internal fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray) =
    box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()), *payload)

/** A minimal valid one-video-track movie; each field can be overridden to inject exactly one violation. */
internal data class MovieSpec(
    val ftyp: ByteArray? = box("ftyp", "isom".toByteArray(), be32(512), "isommp41".toByteArray()),
    val movieTimescale: Long = 1000,
    val nextTrackId: Long = 2,
    val trackId: Long = 1,
    val mvhdVersion: Int = 0,
    val handler: String = "vide",
    val handlerPreDefined: String = "\u0000\u0000\u0000\u0000",
    val mediaHeader: ByteArray? = fullBox("vmhd", 0, 1, ByteArray(8)),
    val mediaTimescale: Long = 1000,
    val language: Int = 0x55C4, // "und"
    val sampleSizes: List<Int> = listOf(10, 10, 10),
    val sttsCounts: List<Long>? = null,           // default: one entry covering every sample
    val stsc: List<Triple<Long, Long, Long>>? = null, // default: (1, n, 1)
    val chunkOffsetDelta: Long = 0,
    val stss: List<Long>? = listOf(1),
    val ctts: List<Pair<Long, Long>>? = null,      // (sample_count, offset)
    val elst: List<Triple<Long, Long, Int>>? = null, // (segment_duration, media_time, media_rate_integer)
    val drefEntry: ByteArray = fullBox("url ", 0, 1),
    val vmhdInStbl: Boolean = false,               // misplaces vmhd for the parent rule
    val extraTopLevelBefore: List<ByteArray> = emptyList(),
    val extraMoov: List<ByteArray> = emptyList(),
    val trailing: ByteArray = ByteArray(0),
    val dropBox: String? = null,                   // remove the first box of this type
)

internal fun buildMovie(s: MovieSpec = MovieSpec()): ByteArray {
    val n = s.sampleSizes.size
    val mdatPayload = ByteArray(s.sampleSizes.sum()) { 7 }
    val mdat = box("mdat", mdatPayload)
    val head = (s.ftyp ?: ByteArray(0)) + s.extraTopLevelBefore.fold(ByteArray(0)) { a, b -> a + b }
    val firstSampleOffset = head.size + 8L + s.chunkOffsetDelta
    val mediaDuration = 100L * n
    val movieDuration = mediaDuration * s.movieTimescale / s.mediaTimescale
    fun drop(type: String, bytes: ByteArray) = if (s.dropBox == type) ByteArray(0) else bytes

    val mvhd = if (s.mvhdVersion == 0) fullBox("mvhd", 0, 0, be32(0), be32(0), be32(s.movieTimescale), be32(movieDuration),
        be32(0x00010000), be16(0x0100), ByteArray(10), ByteArray(36), ByteArray(24), be32(s.nextTrackId))
    else fullBox("mvhd", s.mvhdVersion, 0, ByteArray(16), be32(s.movieTimescale), ByteArray(8), ByteArray(80), be32(s.nextTrackId))
    val tkhd = fullBox("tkhd", 0, 3, be32(0), be32(0), be32(s.trackId), be32(0), be32(movieDuration), ByteArray(8),
        be16(0), be16(0), be16(0), be16(0), ByteArray(36), be32(64L shl 16), be32(48L shl 16))
    val mdhd = fullBox("mdhd", 0, 0, be32(0), be32(0), be32(s.mediaTimescale), be32(mediaDuration), be16(s.language), be16(0))
    val hdlr = fullBox("hdlr", 0, 0, s.handlerPreDefined.toByteArray(Charsets.ISO_8859_1), s.handler.toByteArray(), ByteArray(12), byteArrayOf(0))
    val stsd = fullBox("stsd", 0, 0, be32(1), box("avc1", ByteArray(78)))
    val sttsCounts = s.sttsCounts ?: listOf(n.toLong())
    val stts = fullBox("stts", 0, 0, be32(sttsCounts.size.toLong()), *sttsCounts.map { be32(it) + be32(100) }.toTypedArray())
    val stscEntries = s.stsc ?: listOf(Triple(1L, n.toLong(), 1L))
    val stsc = fullBox("stsc", 0, 0, be32(stscEntries.size.toLong()), *stscEntries.map { be32(it.first) + be32(it.second) + be32(it.third) }.toTypedArray())
    val stsz = fullBox("stsz", 0, 0, be32(0), be32(n.toLong()), *s.sampleSizes.map { be32(it.toLong()) }.toTypedArray())
    val stco = fullBox("stco", 0, 0, be32(1), be32(firstSampleOffset))
    val stss = s.stss?.let { list -> fullBox("stss", 0, 0, be32(list.size.toLong()), *list.map(::be32).toTypedArray()) } ?: ByteArray(0)
    val ctts = s.ctts?.let { list -> fullBox("ctts", 0, 0, be32(list.size.toLong()), *list.map { be32(it.first) + be32(it.second) }.toTypedArray()) } ?: ByteArray(0)
    val mediaHeader = s.mediaHeader ?: ByteArray(0)
    val stbl = box("stbl", drop("stsd", stsd), drop("stts", stts), ctts, drop("stss", stss), drop("stsc", stsc), drop("stsz", stsz),
        drop("stco", stco), if (s.vmhdInStbl) mediaHeader else ByteArray(0))
    val dinf = box("dinf", fullBox("dref", 0, 0, be32(1), s.drefEntry))
    val minf = box("minf", if (s.vmhdInStbl) ByteArray(0) else mediaHeader, drop("dinf", dinf), drop("stbl", stbl))
    val mdia = box("mdia", drop("mdhd", mdhd), drop("hdlr", hdlr), drop("minf", minf))
    val edts = s.elst?.let { list ->
        box("edts", fullBox("elst", 0, 0, be32(list.size.toLong()),
            *list.map { be32(it.first) + be32(it.second and 0xFFFFFFFFL) + be16(it.third) + be16(0) }.toTypedArray()))
    } ?: ByteArray(0)
    val trak = box("trak", drop("tkhd", tkhd), edts, drop("mdia", mdia))
    val moov = box("moov", drop("mvhd", mvhd), drop("trak", trak), *s.extraMoov.toTypedArray())
    return head + drop("mdat", mdat) + drop("moov", moov) + s.trailing
}
