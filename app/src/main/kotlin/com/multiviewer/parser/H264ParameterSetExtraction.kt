package com.multiviewer.parser

import java.io.File

data class AvcCRawParameterSets(val lengthSize: Int, val spsList: List<ByteArray>, val ppsList: List<ByteArray>)

// Mirrors AvcCBoxDecoder's own walk of this exact box structure, but COLLECTS the raw SPS/PPS
// bytes instead of only counting/validating them -- AvcCBoxDecoder deliberately doesn't retain
// them (see docs/superpowers/specs/2026-07-17-box-detail-parsing-design.md).
fun extractAvcCRawParameterSets(file: File, avcCNode: BoxNode): AvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = avcCNode.offset + avcCNode.headerSize
            val payloadEnd = avcCNode.offset + avcCNode.size
            if (payloadEnd - payloadStart < 6) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 4) and 0x03) + 1
            val declaredSps = reader.readUInt8(payloadStart + 5) and 0x1F

            var pos = payloadStart + 6
            val spsList = mutableListOf<ByteArray>()
            while (spsList.size < declaredSps && pos + 2 <= payloadEnd) {
                val spsLength = reader.readUInt16(pos)
                if (pos + 2 + spsLength > payloadEnd) break
                spsList.add(reader.readBytes(pos + 2, spsLength))
                pos += 2 + spsLength
            }

            val ppsList = mutableListOf<ByteArray>()
            if (pos < payloadEnd) {
                val declaredPps = reader.readUInt8(pos)
                pos += 1
                while (ppsList.size < declaredPps && pos + 2 <= payloadEnd) {
                    val ppsLength = reader.readUInt16(pos)
                    if (pos + 2 + ppsLength > payloadEnd) break
                    ppsList.add(reader.readBytes(pos + 2, ppsLength))
                    pos += 2 + ppsLength
                }
            }
            AvcCRawParameterSets(lengthSize, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}

// Reads a specific frame's own length-prefixed sample bytes (FrameInfo.byteOffset/sizeBytes) and
// walks its NALs looking for the first VCL one (nal_unit_type 1 = non-IDR slice, 5 = IDR slice),
// skipping non-VCL NALs (SEI, etc.) that can precede it in the same sample. Reads only a small
// fixed prefix of that NAL -- first_mb_in_slice/slice_type/pic_parameter_set_id are all Exp-Golomb
// and, for any realistic single-slice-per-frame encode, comfortably fit in a handful of bytes;
// 16 bytes is a generous safety margin, well short of the full NAL, and this never touches any
// actual CABAC/CAVLC-coded slice data.
fun resolveActivePicParameterSetId(file: File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int? {
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
                val nalUnitType = reader.readUInt8(pos) and 0x1F
                if (nalUnitType == 1 || nalUnitType == 5) {
                    val prefixLength = minOf(nalLength, 16L).toInt()
                    val nalBytes = reader.readBytes(pos, prefixLength)
                    val bitReader = BitReader(nalBytes, startByteOffset = 1)
                    return@use try {
                        bitReader.readUe() // first_mb_in_slice
                        bitReader.readUe() // slice_type
                        bitReader.readUe() // pic_parameter_set_id
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

fun resolveActiveParameterSets(spsList: List<H264Sps>, ppsList: List<H264Pps>, picParameterSetId: Int): Pair<H264Sps, H264Pps>? {
    val pps = ppsList.find { it.picParameterSetId == picParameterSetId } ?: return null
    val sps = spsList.find { it.seqParameterSetId == pps.seqParameterSetId } ?: return null
    return sps to pps
}
