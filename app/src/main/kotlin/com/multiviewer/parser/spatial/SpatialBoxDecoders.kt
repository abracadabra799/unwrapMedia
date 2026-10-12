package com.multiviewer.parser.spatial

import com.multiviewer.parser.BoxDecoder
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

/**
 * L-HEVC (Layered / Multi-View HEVC) Configuration Box (`lhvC`).
 * ISO/IEC 14496-15 Annex E. Same structural header and NAL arrays as `hvcC`.
 */
object LhvcBoxDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 23
    private const val NAL_TYPE_VPS = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PPS = 34

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for lhvC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val configVersion = reader.readUInt8(payloadStart)
        val generalProfileIdc = reader.readUInt8(payloadStart + 1) and 0x1F
        val generalLevelIdc = reader.readUInt8(payloadStart + 12)
        val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
        val numArrays = reader.readUInt8(payloadStart + 22)

        var pos = payloadStart + FIXED_HEADER_SIZE
        var numVps = 0
        var numSps = 0
        var numPps = 0
        var arraysWalked = 0
        while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
            val nalType = reader.readUInt8(pos) and 0x3F
            val numNalus = reader.readUInt16(pos + 1)
            pos += 3
            var nalusWalked = 0
            while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                val nalLength = reader.readUInt16(pos)
                if (pos + 2 + nalLength > payloadEnd) break
                pos += 2 + nalLength
                nalusWalked++
            }
            when (nalType) {
                NAL_TYPE_VPS -> numVps += nalusWalked
                NAL_TYPE_SPS -> numSps += nalusWalked
                NAL_TYPE_PPS -> numPps += nalusWalked
            }
            arraysWalked++
        }
        if (arraysWalked < numArrays) {
            w.add("Declared $numArrays NAL arrays but only found $arraysWalked")
        }

        val fields = listOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("general_profile_idc", "$generalProfileIdc (L-HEVC / MV-HEVC)", payloadStart + 1, 1),
            BoxField("general_level_idc", generalLevelIdc.toString(), payloadStart + 12, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 21, 1),
            BoxField("num_arrays", numArrays.toString(), payloadStart + 22, 1),
            BoxField("num_vps", numVps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_sps", numSps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_pps", numPps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "MV-HEVC profile=$generalProfileIdc, level=$generalLevelIdc, $numSps SPS, $numPps PPS",
        )
    }
}

/**
 * Apple Spatial Video Eye Definition Box (`eyes`).
 */
object EyesBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()

        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short for FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)
        fields.add(BoxField("version", version.toString(), payloadStart, 1))
        fields.add(BoxField("flags", String.format("0x%06X", flags), payloadStart + 1, 3))

        var cur = payloadStart + 4
        var heroEyeName = "Unknown"
        var eyeViewsName = "Stereo (Left+Right)"

        if (cur < payloadEnd) {
            val heroEyeVal = reader.readUInt8(cur)
            heroEyeName = when (heroEyeVal) {
                1 -> "Left Eye"
                2 -> "Right Eye"
                else -> "Both / None ($heroEyeVal)"
            }
            fields.add(BoxField("hero_eye", heroEyeName, cur, 1))
            cur += 1
        }

        if (cur < payloadEnd) {
            val eyeViewsVal = reader.readUInt8(cur)
            eyeViewsName = when (eyeViewsVal) {
                1 -> "Left Eye only"
                2 -> "Right Eye only"
                3 -> "Stereo (Left + Right)"
                else -> "Custom ($eyeViewsVal)"
            }
            fields.add(BoxField("eye_views", eyeViewsName, cur, 1))
            cur += 1
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "Spatial: hero=$heroEyeName, views=$eyeViewsName",
        )
    }
}

/**
 * Hero Eye Indicator Box (`hero`).
 * Indicates the primary eye view for 2D playback.
 */
object HeroBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()

        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short for FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)
        fields.add(BoxField("version", version.toString(), payloadStart, 1))
        fields.add(BoxField("flags", String.format("0x%06X", flags), payloadStart + 1, 3))

        var heroEyeName = "Unspecified"
        if (payloadStart + 4 < payloadEnd) {
            val heroEye = reader.readUInt8(payloadStart + 4)
            heroEyeName = when (heroEye) {
                1 -> "Left Eye"
                2 -> "Right Eye"
                else -> "None / Value $heroEye"
            }
            fields.add(BoxField("hero_eye", heroEyeName, payloadStart + 4, 1))
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "Hero Eye: $heroEyeName",
        )
    }
}

/**
 * Stereoscopic 3D Box (`st3d`).
 * ISO/IEC 23000-22 stereo video layout definition.
 */
object St3dBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()

        if (payloadEnd - payloadStart < 5) {
            w.add("Box too short for st3d (FullBox + stereo_mode)")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)
        val mode = reader.readUInt8(payloadStart + 4)

        val modeName = when (mode) {
            0 -> "Monoscopic"
            1 -> "Stereoscopic (Top-Bottom)"
            2 -> "Stereoscopic (Left-Right)"
            3 -> "Stereoscopic (Side-by-Side)"
            4 -> "Multi-View (MV-HEVC)"
            else -> "Reserved / Custom ($mode)"
        }

        fields.add(BoxField("version", version.toString(), payloadStart, 1))
        fields.add(BoxField("flags", String.format("0x%06X", flags), payloadStart + 1, 3))
        fields.add(BoxField("stereo_mode", "$mode ($modeName)", payloadStart + 4, 1))

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "Stereo Mode: $modeName",
        )
    }
}

/**
 * View Description Box (`vwdi`).
 */
object VwdiBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()

        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short for FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)

        fields.add(BoxField("version", version.toString(), payloadStart, 1))
        fields.add(BoxField("flags", String.format("0x%06X", flags), payloadStart + 1, 3))

        val viewsCount = if (payloadStart + 6 <= payloadEnd) {
            val count = reader.readUInt16(payloadStart + 4)
            fields.add(BoxField("views_count", count.toString(), payloadStart + 4, 2))
            count
        } else 0

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "View Description ($viewsCount view(s))",
        )
    }
}
