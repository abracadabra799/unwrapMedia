package com.multiviewer.parser

import java.io.File

data class HvcCRawParameterSets(
    val lengthSize: Int,
    val vpsList: List<ByteArray>,
    val spsList: List<ByteArray>,
    val ppsList: List<ByteArray>,
)

private const val HVCC_FIXED_HEADER_SIZE = 23
private const val HEVC_NAL_TYPE_VPS = 32
private const val HEVC_NAL_TYPE_SPS = 33
private const val HEVC_NAL_TYPE_PPS = 34

// Mirrors HvcCBoxDecoder's own walk of this exact box structure (and HeifHevcThumbnail.kt's
// private readHvcCInfo, which walks the same structure for a different purpose -- feeding a HEIF
// image item to ffmpeg as one concatenated Annex-B buffer), but COLLECTS the raw VPS/SPS/PPS
// bytes as three separate lists instead.
fun extractHvcCRawParameterSets(file: File, hvcCNode: BoxNode): HvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = hvcCNode.offset + hvcCNode.headerSize
            val payloadEnd = hvcCNode.offset + hvcCNode.size
            if (payloadEnd - payloadStart < HVCC_FIXED_HEADER_SIZE) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
            val numArrays = reader.readUInt8(payloadStart + 22)

            val vpsList = mutableListOf<ByteArray>()
            val spsList = mutableListOf<ByteArray>()
            val ppsList = mutableListOf<ByteArray>()
            var pos = payloadStart + HVCC_FIXED_HEADER_SIZE
            var arraysWalked = 0
            while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
                val nalType = reader.readUInt8(pos) and 0x3F
                val numNalus = reader.readUInt16(pos + 1)
                pos += 3
                var nalusWalked = 0
                while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                    val nalLength = reader.readUInt16(pos)
                    pos += 2
                    if (pos + nalLength > payloadEnd) break
                    val nalBytes = reader.readBytes(pos, nalLength)
                    when (nalType) {
                        HEVC_NAL_TYPE_VPS -> vpsList.add(nalBytes)
                        HEVC_NAL_TYPE_SPS -> spsList.add(nalBytes)
                        HEVC_NAL_TYPE_PPS -> ppsList.add(nalBytes)
                    }
                    pos += nalLength
                    nalusWalked++
                }
                arraysWalked++
            }
            HvcCRawParameterSets(lengthSize, vpsList, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}

// Reads a specific frame's own length-prefixed sample bytes (FrameInfo.byteOffset/sizeBytes) and
// walks its NALs looking for the first VCL one (nal_unit_type < 32 -- HEVC's non-VCL types start
// at 32, unlike H.264's two explicit type checks), skipping non-VCL NALs (SEI, etc.) that can
// precede it in the same sample. Reads only a small fixed prefix of that NAL --
// first_slice_segment_in_pic_flag/no_output_of_prior_pics_flag/slice_pic_parameter_set_id are all
// within a handful of bits past the 2-byte NAL header and, for any realistic
// single-slice-per-frame encode, comfortably fit in a handful of bytes; 16 bytes is a generous
// safety margin, well short of the full NAL, and this never touches any actual CABAC-coded slice
// data.
fun resolveActiveHevcPicParameterSetId(file: File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int? {
    return try {
        ByteReader.open(file).use { reader ->
            val sampleEnd = byteOffset + sizeBytes
            var pos = byteOffset
            while (pos + lengthSize <= sampleEnd) {
                val nalLength = when (lengthSize) {
                    1 -> reader.readUInt8(pos).toLong()
                    2 -> reader.readUInt16(pos).toLong()
                    4 -> reader.readUInt32(pos)
                    else -> return@use null
                }
                pos += lengthSize
                if (nalLength <= 0 || pos + nalLength > sampleEnd) break
                val nalUnitType = (reader.readUInt8(pos) shr 1) and 0x3F
                if (nalUnitType < 32) {
                    val prefixLength = minOf(nalLength, 16L).toInt()
                    val nalBytes = reader.readBytes(pos, prefixLength)
                    val bitReader = BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)
                    return@use try {
                        bitReader.readFlag() // first_slice_segment_in_pic_flag
                        if (nalUnitType in 16..23) bitReader.readFlag() // no_output_of_prior_pics_flag (IRAP only)
                        bitReader.readUe() // slice_pic_parameter_set_id
                    } catch (e: Exception) {
                        null
                    }
                }
                pos += nalLength
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun resolveActiveHevcParameterSets(
    vpsList: List<HevcVps>,
    spsList: List<HevcSps>,
    ppsList: List<HevcPps>,
    picParameterSetId: Int,
): Triple<HevcVps?, HevcSps, HevcPps>? {
    val pps = ppsList.find { it.ppsId == picParameterSetId } ?: return null
    val sps = spsList.find { it.spsId == pps.spsId } ?: return null
    val vps = vpsList.find { it.vpsId == sps.vpsId }
    return Triple(vps, sps, pps)
}
