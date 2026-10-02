package com.multiviewer.parser

data class Av2SequenceHeader(
    val sequenceHeaderId: Int,
    val profile: Int,
    val level: Int,
    val tier: Int,
    val singlePicture: Boolean,
    val stillPicture: Boolean,
    val maxTemporalLayerId: Int,
    val maxEmbeddedLayerId: Int,
    val monotonicOutputOrder: Boolean,
    val chromaFormatIdc: Int,
    val bitDepth: Int,
    val monochrome: Boolean,
    val chromaSubsamplingX: Int,
    val chromaSubsamplingY: Int,
    val maxFrameWidth: Int,
    val maxFrameHeight: Int,
)

// AV2 v1.0.0 §5.4.1 direct sequence-header prefix. Colour description and film-grain presence are
// signalled by later codec structures, so this phase deliberately does not guess them.
fun parseAv2SequenceHeader(payload: ByteArray): Av2SequenceHeader? {
    if (payload.isEmpty()) return null
    return try {
    val r = BitReader(payload)
    val id = r.readUe()
    val profile = r.readBits(5)
    val single = r.readFlag()
    val level = r.readBits(5)
    val tier = if (level > 3 && !single) r.readBits(1) else 0
    val chroma = r.readUe()
    val depthIdc = r.readUe()
    if (chroma !in 0..3 || depthIdc !in 0..1) return null
    val (subX, subY, mono) = when (chroma) { 0 -> Triple(1, 1, false); 1 -> Triple(1, 1, true); 2 -> Triple(0, 0, false); else -> Triple(1, 0, false) }
    val still: Boolean; val maxT: Int; val maxM: Int; val monotonic: Boolean
    if (single) { still = true; maxT = 0; maxM = 0; monotonic = true } else {
        r.readBits(3); still = r.readFlag(); maxT = r.readBits(2); maxM = r.readBits(3)
        if (maxM > 0) r.readBits(32 - Integer.numberOfLeadingZeros(maxM))
        monotonic = r.readFlag()
    }
    val widthBits = r.readBits(4) + 1; val heightBits = r.readBits(4) + 1
    val width = r.readBits(widthBits) + 1; val height = r.readBits(heightBits) + 1
    if (r.readFlag()) repeat(4) { r.readUe() }
    Av2SequenceHeader(id, profile, level, tier, single, still, maxT, maxM, monotonic, chroma, if (depthIdc == 0) 10 else 8, mono, subX, subY, width, height)
    } catch (_: Exception) { null }
}
