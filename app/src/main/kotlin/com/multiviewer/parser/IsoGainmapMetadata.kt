package com.multiviewer.parser

import kotlin.math.pow

/**
 * ISO 21496-1 gain map metadata, the binary form carried in a JPEG gain map image's APP2
 * (`urn:iso:std:iso:ts:21496:-1\0` + payload) and in a HEIF/AVIF `tmap` item (1 version byte + payload).
 * Headrooms and gain map min/max are log2; offsets are linear.
 *
 * Layout verified on the S26 JPEG and HEIC fixtures (see the gain map analyzer spec). The
 * common-denominator and multichannel paths follow the libultrahdr layout and are only covered by
 * hand-built payloads -- no encoder producing them was available.
 */
data class IsoGainmapChannel(
    val gainMapMin: Double,
    val gainMapMax: Double,
    val gamma: Double,
    val baseOffset: Double,
    val alternateOffset: Double,
)

data class IsoGainmapMetadata(
    val minimumVersion: Int,
    val writerVersion: Int,
    val isMultichannel: Boolean,
    val useBaseColourSpace: Boolean,
    val backwardDirection: Boolean,
    val commonDenominator: Boolean,
    val baseHdrHeadroom: Double,
    val alternateHdrHeadroom: Double,
    val channels: List<IsoGainmapChannel>,
) {
    /**
     * The hdrgm-style view of channel 0. Forward direction (base is SDR): capacity min/max are the
     * base/alternate headrooms and offsetSdr/offsetHdr the base/alternate offsets; backward swaps both.
     */
    fun toParsedParameters(): GainmapParsedParameters {
        val c = channels.first()
        val (capMin, capMax) = if (backwardDirection) alternateHdrHeadroom to baseHdrHeadroom else baseHdrHeadroom to alternateHdrHeadroom
        val (offSdr, offHdr) = if (backwardDirection) c.alternateOffset to c.baseOffset else c.baseOffset to c.alternateOffset
        return GainmapParsedParameters(
            version = "ISO 21496-1 (min v$minimumVersion, writer v$writerVersion)",
            gainMapMin = c.gainMapMin,
            gainMapMax = c.gainMapMax,
            gamma = c.gamma,
            offsetSdr = offSdr,
            offsetHdr = offHdr,
            hdrCapacityMin = capMin,
            hdrCapacityMax = capMax,
            baseRenditionIsHdr = backwardDirection,
            stops = c.gainMapMax,
            linearMaxBoost = 2.0.pow(c.gainMapMax),
            linearMinBoost = 2.0.pow(c.gainMapMin),
        )
    }
}

/** The identifier that prefixes the payload inside a JPEG APP2 segment. */
const val ISO_GAINMAP_APP2_URN = "urn:iso:std:iso:ts:21496:-1"

/** A primary JPEG image carries only `minimum_version` + `writer_version` (4 bytes) to advertise ISO support. */
fun isIsoGainmapVersionBlock(bytes: ByteArray): Boolean = bytes.size == 4

/** Null on truncation, a zero denominator, or a `minimum_version` this decoder doesn't know (only 0 exists). */
fun parseIsoGainmapMetadata(bytes: ByteArray): IsoGainmapMetadata? {
    var pos = 0
    fun need(n: Int) = pos + n <= bytes.size
    fun u8(): Int = bytes[pos++].toInt() and 0xFF
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun s32(): Long = u32().toInt().toLong()

    if (!need(5)) return null
    val minimumVersion = u16()
    val writerVersion = u16()
    if (minimumVersion != 0) return null
    val flags = u8()
    val multichannel = flags and 0x80 != 0
    val useBase = flags and 0x40 != 0
    val common = flags and 0x08 != 0
    val backward = flags and 0x04 != 0
    val channelCount = if (multichannel) 3 else 1

    fun ratio(n: Long, d: Long): Double? = if (d == 0L) null else n.toDouble() / d.toDouble()

    return if (common) {
        if (!need(4 * 3 + channelCount * 4 * 5)) return null
        val den = u32()
        val base = ratio(u32(), den) ?: return null
        val alt = ratio(u32(), den) ?: return null
        val channels = List(channelCount) {
            IsoGainmapChannel(
                gainMapMin = ratio(s32(), den) ?: return null,
                gainMapMax = ratio(s32(), den) ?: return null,
                gamma = ratio(u32(), den) ?: return null,
                baseOffset = ratio(s32(), den) ?: return null,
                alternateOffset = ratio(s32(), den) ?: return null,
            )
        }
        IsoGainmapMetadata(minimumVersion, writerVersion, multichannel, useBase, backward, true, base, alt, channels)
    } else {
        if (!need(4 * 4 + channelCount * 4 * 10)) return null
        val base = ratio(u32(), u32()) ?: return null
        val alt = ratio(u32(), u32()) ?: return null
        val channels = List(channelCount) {
            IsoGainmapChannel(
                gainMapMin = ratio(s32(), u32()) ?: return null,
                gainMapMax = ratio(s32(), u32()) ?: return null,
                gamma = ratio(u32(), u32()) ?: return null,
                baseOffset = ratio(s32(), u32()) ?: return null,
                alternateOffset = ratio(s32(), u32()) ?: return null,
            )
        }
        IsoGainmapMetadata(minimumVersion, writerVersion, multichannel, useBase, backward, false, base, alt, channels)
    }
}
