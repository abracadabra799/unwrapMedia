package com.multiviewer.parser

private const val TAG_EXIF_IFD_POINTER = 0x8769
private const val TAG_GPS_IFD_POINTER = 0x8825
private const val TAG_INTEROP_IFD_POINTER = 0xA005
private const val TAG_MAKER_NOTE = 0x927C
private const val TAG_JPEG_INTERCHANGE_FORMAT = 0x0201
private const val TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202
// TIFF/EP SubIFDs (0x014A) -- camera RAW formats (CR2/NEF/ARW/DNG are all TIFF/EP-based) commonly
// store one or more additional image resolutions (a full-size preview, sometimes the raw sensor
// data itself) as SubIFDs hung off IFD0, each with the same tag vocabulary as a normal IFD. Count
// can be >1 (one offset per SubIFD); NOT verified against a real RAW file -- see the camera-RAW
// support design notes.
private const val TAG_SUB_IFDS = 0x014A

private val TIFF_TYPE_SIZES = mapOf(
    1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
)

// TIFF 6.0 baseline tags plus Adobe DNG 1.6's private tag range (0xC612-0xC761) -- DNG stores its
// tags directly in IFD0 alongside standard TIFF tags (not a separate pointer-based sub-IFD), so
// they belong in this same map rather than a parallel one threaded through decodeIfd separately.
private val TAG_NAMES_IFD0 = mapOf(
    0x00FE to "NewSubfileType",
    0x0100 to "ImageWidth",
    0x0101 to "ImageLength",
    0x0102 to "BitsPerSample",
    0x0103 to "Compression",
    0x0106 to "PhotometricInterpretation",
    0x010E to "ImageDescription",
    0x010F to "Make",
    0x0110 to "Model",
    0x0111 to "StripOffsets",
    0x0112 to "Orientation",
    0x0115 to "SamplesPerPixel",
    0x0116 to "RowsPerStrip",
    0x0117 to "StripByteCounts",
    0x011A to "XResolution",
    0x011B to "YResolution",
    0x011C to "PlanarConfiguration",
    0x0128 to "ResolutionUnit",
    0x0131 to "Software",
    0x0132 to "DateTime",
    0x013B to "Artist",
    0x013E to "WhitePoint",
    0x013F to "PrimaryChromaticities",
    0x0211 to "YCbCrCoefficients",
    0x0212 to "YCbCrSubSampling",
    0x0213 to "YCbCrPositioning",
    0x0214 to "ReferenceBlackWhite",
    0x8298 to "Copyright",
    0x828D to "CFARepeatPatternDim",
    0x828E to "CFAPattern",
    // -- DNG private tags (Adobe DNG 1.6 spec) --
    0xC612 to "DNGVersion",
    0xC613 to "DNGBackwardVersion",
    0xC614 to "UniqueCameraModel",
    0xC615 to "LocalizedCameraModel",
    0xC616 to "CFAPlaneColor",
    0xC617 to "CFALayout",
    0xC618 to "LinearizationTable",
    0xC619 to "BlackLevelRepeatDim",
    0xC61A to "BlackLevel",
    0xC61B to "BlackLevelDeltaH",
    0xC61C to "BlackLevelDeltaV",
    0xC61D to "WhiteLevel",
    0xC61E to "DefaultScale",
    0xC61F to "DefaultCropOrigin",
    0xC620 to "DefaultCropSize",
    0xC621 to "ColorMatrix1",
    0xC622 to "ColorMatrix2",
    0xC623 to "CameraCalibration1",
    0xC624 to "CameraCalibration2",
    0xC627 to "AnalogBalance",
    0xC628 to "AsShotNeutral",
    0xC629 to "AsShotWhiteXY",
    0xC62A to "BaselineExposure",
    0xC62B to "BaselineNoise",
    0xC62C to "BaselineSharpness",
    0xC62D to "BayerGreenSplit",
    0xC62E to "LinearResponseLimit",
    0xC62F to "CameraSerialNumber",
    0xC630 to "LensInfo",
    0xC633 to "ShadowScale",
    0xC635 to "MakerNoteSafety",
    0xC65A to "CalibrationIlluminant1",
    0xC65B to "CalibrationIlluminant2",
    0xC65C to "BestQualityScale",
    0xC65D to "RawDataUniqueID",
    0xC68B to "OriginalRawFileName",
    0xC6BF to "ColorimetricReference",
    0xC6F8 to "ProfileName",
    0xC6FD to "ProfileEmbedPolicy",
    0xC6FE to "ProfileCopyright",
    0xC714 to "ForwardMatrix1",
    0xC715 to "ForwardMatrix2",
    0xC761 to "NoiseProfile",
)

private val TAG_NAMES_EXIF = mapOf(
    0x829A to "ExposureTime",
    0x829D to "FNumber",
    0x8822 to "ExposureProgram",
    0x8827 to "ISOSpeedRatings",
    0x8830 to "SensitivityType",
    0x8831 to "StandardOutputSensitivity",
    0x8832 to "RecommendedExposureIndex",
    0x9000 to "ExifVersion",
    0x9003 to "DateTimeOriginal",
    0x9004 to "DateTimeDigitized",
    0x9010 to "OffsetTime",
    0x9011 to "OffsetTimeOriginal",
    0x9101 to "ComponentsConfiguration",
    0x9102 to "CompressedBitsPerPixel",
    0x9201 to "ShutterSpeedValue",
    0x9202 to "ApertureValue",
    0x9203 to "BrightnessValue",
    0x9204 to "ExposureBiasValue",
    0x9205 to "MaxApertureValue",
    0x9206 to "SubjectDistance",
    0x9207 to "MeteringMode",
    0x9208 to "LightSource",
    0x9209 to "Flash",
    0x920A to "FocalLength",
    0x9214 to "SubjectArea",
    0x9286 to "UserComment",
    0x9290 to "SubSecTime",
    0x9291 to "SubSecTimeOriginal",
    0x9292 to "SubSecTimeDigitized",
    0xA000 to "FlashpixVersion",
    0xA001 to "ColorSpace",
    0xA002 to "PixelXDimension",
    0xA003 to "PixelYDimension",
    0xA20B to "FlashEnergy",
    0xA20E to "FocalPlaneXResolution",
    0xA20F to "FocalPlaneYResolution",
    0xA210 to "FocalPlaneResolutionUnit",
    0xA214 to "SubjectLocation",
    0xA215 to "ExposureIndex",
    0xA217 to "SensingMethod",
    0xA300 to "FileSource",
    0xA301 to "SceneType",
    0xA302 to "CFAPattern",
    0xA401 to "CustomRendered",
    0xA402 to "ExposureMode",
    0xA403 to "WhiteBalance",
    0xA404 to "DigitalZoomRatio",
    0xA405 to "FocalLengthIn35mmFilm",
    0xA406 to "SceneCaptureType",
    0xA407 to "GainControl",
    0xA408 to "Contrast",
    0xA409 to "Saturation",
    0xA40A to "Sharpness",
    0xA40C to "SubjectDistanceRange",
    0xA420 to "ImageUniqueID",
    0xA431 to "BodySerialNumber",
    0xA432 to "LensSpecification",
    0xA433 to "LensMake",
    0xA434 to "LensModel",
    0xA435 to "LensSerialNumber",
    0xA460 to "CompositeImage",
)

private val TAG_NAMES_GPS = mapOf(
    0x0000 to "GPSVersionID",
    0x0001 to "GPSLatitudeRef",
    0x0002 to "GPSLatitude",
    0x0003 to "GPSLongitudeRef",
    0x0004 to "GPSLongitude",
    0x0005 to "GPSAltitudeRef",
    0x0006 to "GPSAltitude",
    0x0007 to "GPSTimeStamp",
    0x0008 to "GPSSatellites",
    0x0009 to "GPSStatus",
    0x000A to "GPSMeasureMode",
    0x000B to "GPSDOP",
    0x000C to "GPSSpeedRef",
    0x000D to "GPSSpeed",
    0x000E to "GPSTrackRef",
    0x000F to "GPSTrack",
    0x0010 to "GPSImgDirectionRef",
    0x0011 to "GPSImgDirection",
    0x0012 to "GPSMapDatum",
    0x0013 to "GPSDestLatitudeRef",
    0x0014 to "GPSDestLatitude",
    0x0015 to "GPSDestLongitudeRef",
    0x0016 to "GPSDestLongitude",
    0x0017 to "GPSDestBearingRef",
    0x0018 to "GPSDestBearing",
    0x0019 to "GPSDestDistanceRef",
    0x001A to "GPSDestDistance",
    0x001B to "GPSProcessingMethod",
    0x001C to "GPSAreaInformation",
    0x001D to "GPSDateStamp",
    0x001E to "GPSHPositioningError",
)

// Samsung's "Type2" MakerNote (the EXIF-format one used by newer Samsung phones/cameras -- see
// decodeMakerNote's dispatch comment for the other two formats Samsung has used and why they
// aren't handled here). Tag IDs and names per
// https://exiftool.sourceforge.net/TagNames/Samsung.html -- a handful there (RawData, Distortion,
// ChromaticAberration, Vignetting, VignettingCorrection, VignettingSetting at 0xa048/0xa050-0xa054)
// are marked with "?" by ExifTool itself as unconfirmed guesses; included anyway since an
// unconfirmed-but-plausible name is still more useful here than a bare hex tag number.
private val TAG_NAMES_MAKERNOTE_SAMSUNG = mapOf(
    0x0001 to "MakerNoteVersion",
    0x0002 to "DeviceType",
    0x0003 to "SamsungModelID",
    0x0011 to "OrientationInfo",
    0x0020 to "SmartAlbumColor",
    0x0021 to "PictureWizard",
    0x0030 to "LocalLocationName",
    0x0031 to "LocationName",
    0x0035 to "PreviewIFD",
    0x0040 to "RawDataByteOrder",
    0x0041 to "WhiteBalanceSetup",
    0x0043 to "CameraTemperature",
    0x0050 to "RawDataCFAPattern",
    0x0100 to "FaceDetect",
    0x0120 to "FaceRecognition",
    0x0123 to "FaceName",
    0xA001 to "FirmwareName",
    0xA002 to "SerialNumber",
    0xA003 to "LensType",
    0xA004 to "LensFirmware",
    0xA005 to "InternalLensSerialNumber",
    0xA010 to "SensorAreas",
    0xA011 to "ColorSpace",
    0xA012 to "SmartRange",
    0xA013 to "ExposureCompensation",
    0xA014 to "ISO",
    0xA018 to "ExposureTime",
    0xA019 to "FNumber",
    0xA01A to "FocalLengthIn35mmFormat",
    0xA020 to "EncryptionKey",
    0xA021 to "WB_RGGBLevelsUncorrected",
    0xA022 to "WB_RGGBLevelsAuto",
    0xA023 to "WB_RGGBLevelsIlluminator1",
    0xA024 to "WB_RGGBLevelsIlluminator2",
    0xA025 to "HighlightLinearityLimit",
    0xA028 to "WB_RGGBLevelsBlack",
    0xA030 to "ColorMatrix",
    0xA031 to "ColorMatrixSRGB",
    0xA032 to "ColorMatrixAdobeRGB",
    0xA033 to "CbCrMatrixDefault",
    0xA034 to "CbCrMatrix",
    0xA035 to "CbCrGainDefault",
    0xA036 to "CbCrGain",
    0xA040 to "ToneCurveSRGBDefault",
    0xA041 to "ToneCurveAdobeRGBDefault",
    0xA042 to "ToneCurveSRGB",
    0xA043 to "ToneCurveAdobeRGB",
    0xA048 to "RawData",
    0xA050 to "Distortion",
    0xA051 to "ChromaticAberration",
    0xA052 to "Vignetting",
    0xA053 to "VignettingCorrection",
    0xA054 to "VignettingSetting",
)

// Enum/flag tags whose raw integer value is translated to a label ExifTool would also show --
// keyed by (IFD group label, tag ID) so the same numeric tag ID under a different group (e.g. a
// vendor MakerNote reusing a low tag number) is never misinterpreted. Only single-value SHORT/
// LONG/SSHORT/SLONG fields are looked up here (see interpretedDisplay) -- ASCII ref tags like
// GPSLatitudeRef ("N"/"S") are already human-readable as raw text and never reach this table.
private val TAG_VALUE_LABELS: Map<Pair<String, Int>, Map<Int, String>> = mapOf(
    ("IFD0" to 0x0112) to mapOf(
        1 to "Horizontal (normal)", 2 to "Mirror horizontal", 3 to "Rotate 180",
        4 to "Mirror vertical", 5 to "Mirror horizontal and rotate 270 CW", 6 to "Rotate 90 CW",
        7 to "Mirror horizontal and rotate 90 CW", 8 to "Rotate 270 CW",
    ),
    ("IFD0" to 0x0128) to mapOf(1 to "None", 2 to "inches", 3 to "cm"),
    ("IFD0" to 0x0213) to mapOf(1 to "Centered", 2 to "Co-sited"),
    ("IFD0" to 0x0106) to mapOf(0 to "WhiteIsZero", 1 to "BlackIsZero", 2 to "RGB", 6 to "YCbCr"),
    ("IFD0" to 0x0103) to mapOf(1 to "Uncompressed", 6 to "JPEG (old-style)", 7 to "JPEG", 8 to "Adobe Deflate"),
    ("Exif" to 0x8822) to mapOf(
        0 to "Not defined", 1 to "Manual", 2 to "Normal program", 3 to "Aperture priority",
        4 to "Shutter priority", 5 to "Creative program", 6 to "Action program",
        7 to "Portrait mode", 8 to "Landscape mode",
    ),
    ("Exif" to 0x9207) to mapOf(
        0 to "Unknown", 1 to "Average", 2 to "Center-weighted average", 3 to "Spot",
        4 to "Multi-spot", 5 to "Pattern", 6 to "Partial", 255 to "Other",
    ),
    // ExifTool's published Flash bitmask table -- the low bits mean "fired", higher bits encode
    // return-light detection and flash mode, so this is a lookup table of known composite values
    // rather than a formula.
    ("Exif" to 0x9209) to mapOf(
        0x0 to "No Flash", 0x1 to "Fired", 0x5 to "Fired, Return not detected",
        0x7 to "Fired, Return detected", 0x8 to "Did not fire, compulsory",
        0x9 to "Fired, compulsory", 0x10 to "Did not fire, auto mode",
        0x18 to "Did not fire, auto mode", 0x19 to "Fired, auto mode",
        0x1D to "Fired, auto mode, Return not detected", 0x1F to "Fired, auto mode, Return detected",
        0x20 to "No flash function", 0x41 to "Fired, red-eye reduction",
        0x45 to "Fired, red-eye reduction, Return not detected",
        0x47 to "Fired, red-eye reduction, Return detected",
        0x49 to "Fired, compulsory, red-eye reduction",
        0x4D to "Fired, compulsory, red-eye reduction, Return not detected",
        0x4F to "Fired, compulsory, red-eye reduction, Return detected",
        0x59 to "Fired, auto, red-eye reduction",
    ),
    ("Exif" to 0xA403) to mapOf(0 to "Auto", 1 to "Manual"),
    ("Exif" to 0xA001) to mapOf(1 to "sRGB", 65535 to "Uncalibrated"),
    ("Exif" to 0xA402) to mapOf(0 to "Auto", 1 to "Manual", 2 to "Auto bracket"),
    ("Exif" to 0xA406) to mapOf(0 to "Standard", 1 to "Landscape", 2 to "Portrait", 3 to "Night scene"),
    ("Exif" to 0x9208) to mapOf(
        0 to "Unknown", 1 to "Daylight", 2 to "Fluorescent", 3 to "Tungsten", 4 to "Flash",
        9 to "Fine weather", 10 to "Cloudy", 11 to "Shade", 17 to "Standard light A",
        18 to "Standard light B", 19 to "Standard light C", 24 to "ISO studio tungsten", 255 to "Other",
    ),
    ("Exif" to 0xA217) to mapOf(
        1 to "Not defined", 2 to "One-chip color area", 3 to "Two-chip color area",
        4 to "Three-chip color area", 5 to "Color sequential area", 7 to "Trilinear",
        8 to "Color sequential linear",
    ),
    ("Exif" to 0xA300) to mapOf(3 to "Digital Camera"),
    ("Exif" to 0xA301) to mapOf(1 to "Directly photographed"),
    ("Exif" to 0xA401) to mapOf(0 to "Normal", 1 to "Custom"),
    ("Exif" to 0xA407) to mapOf(
        0 to "None", 1 to "Low gain up", 2 to "High gain up", 3 to "Low gain down", 4 to "High gain down",
    ),
    ("Exif" to 0xA408) to mapOf(0 to "Normal", 1 to "Soft", 2 to "Hard"),
    ("Exif" to 0xA409) to mapOf(0 to "Normal", 1 to "Low saturation", 2 to "High saturation"),
    ("Exif" to 0xA40A) to mapOf(0 to "Normal", 1 to "Soft", 2 to "Hard"),
    ("Exif" to 0xA40C) to mapOf(0 to "Unknown", 1 to "Macro", 2 to "Close", 3 to "Distant"),
)

// Direct-unit rational tags (the stored fraction IS the displayed unit, e.g. FNumber's "28/10"
// literally means f/2.8) -- deliberately excludes APEX-encoded tags like ApertureValue/
// ShutterSpeedValue/BrightnessValue, whose displayed value requires a 2^(APEX/n) conversion, not
// just a nicer fraction format; those still show as a raw rational until that conversion is added.
private fun formatExposureTime(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    if (num == 0L) return "0s"
    return if (num < den) "$num/${den}s" else "%.1fs".format(num.toDouble() / den)
}

private fun formatFNumber(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    return "f/%.1f".format(num.toDouble() / den)
}

private fun formatFocalLength(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    return "%.1fmm".format(num.toDouble() / den)
}

private val RATIONAL_FORMATTERS: Map<Pair<String, Int>, (Long, Long) -> String> = mapOf(
    ("Exif" to 0x829A) to ::formatExposureTime,
    ("Exif" to 0x829D) to ::formatFNumber,
    ("Exif" to 0x920A) to ::formatFocalLength,
)

// Returns a human-readable override for a single-value SHORT/LONG/SSHORT/SLONG (enum/flag lookup)
// or RATIONAL/SRATIONAL (unit formatter) field when this task's tables have an entry for
// (group, tag); null otherwise, in which case the caller falls back to formatTiffValue's raw
// output exactly as before. Multi-value fields (count != 1) are never interpreted -- an "average"
// or "list" reading of an enum table wouldn't be meaningful.
private fun interpretedDisplay(group: String, tag: Int, fieldType: Int, count: Int, reader: ByteReader, valuePos: Long, littleEndian: Boolean): String? {
    if (count != 1) return null
    return when (fieldType) {
        3 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt16Endian(reader, valuePos, littleEndian))
        8 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt16Endian(reader, valuePos, littleEndian).toShort().toInt())
        4 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt32Endian(reader, valuePos, littleEndian).toInt())
        9 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt32Endian(reader, valuePos, littleEndian).toInt())
        5 -> {
            val num = readUInt32Endian(reader, valuePos, littleEndian)
            val den = readUInt32Endian(reader, valuePos + 4, littleEndian)
            RATIONAL_FORMATTERS[group to tag]?.invoke(num, den)
        }
        10 -> {
            val num = readUInt32Endian(reader, valuePos, littleEndian).toInt().toLong()
            val den = readUInt32Endian(reader, valuePos + 4, littleEndian).toInt().toLong()
            RATIONAL_FORMATTERS[group to tag]?.invoke(num, den)
        }
        else -> null
    }
}

fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
    if (itemEnd - itemStart < 4) return emptyList()
    val tiffHeaderOffsetField = reader.readUInt32(itemStart)
    val tiffStart = itemStart + 4 + tiffHeaderOffsetField
    return decodeTiff(reader, tiffStart, itemEnd)
}

fun decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode> {
    if (tiffStart + 8 > itemEnd) return emptyList()
    val byteOrderBytes = reader.readBytes(tiffStart, 2)
    val littleEndian = byteOrderBytes[0] == 'I'.code.toByte() && byteOrderBytes[1] == 'I'.code.toByte()
    val ifd0Offset = readUInt32Endian(reader, tiffStart + 4, littleEndian)
    val ifd0AbsoluteOffset = tiffStart + ifd0Offset
    // MakerNote tag numbering is entirely vendor-specific -- the same numeric tag ID means a
    // different thing (or nothing) for each manufacturer, so which name table applies depends on
    // IFD0's Make field. Peeked separately, before the real walk, since Make and the
    // Exif-sub-IFD-embedded MakerNote can appear in either order within IFD0's entries.
    val make = peekMakeValue(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian)
    val makerNoteTagNames = if (make?.contains("SAMSUNG", ignoreCase = true) == true) {
        TAG_NAMES_MAKERNOTE_SAMSUNG
    } else {
        emptyMap()
    }
    val visitedOffsets = mutableSetOf<Long>()
    val ifd0Node = decodeIfd(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian, "IFD0", TAG_NAMES_IFD0, makerNoteTagNames, visitedOffsets)

    val ifds = mutableListOf(ifd0Node)
    val nextIfdOffsetPos = ifd0Node.offset + ifd0Node.size
    if (nextIfdOffsetPos + 4 <= itemEnd) {
        val nextIfdOffset = readUInt32Endian(reader, nextIfdOffsetPos, littleEndian)
        if (nextIfdOffset != 0L) {
            val ifd1AbsoluteOffset = tiffStart + nextIfdOffset
            ifds.add(decodeIfd(reader, tiffStart, ifd1AbsoluteOffset, itemEnd, littleEndian, "IFD1", TAG_NAMES_IFD0, makerNoteTagNames, visitedOffsets))
        }
    }
    return ifds
}

// Minimal scan of IFD0's entries for tag 0x010F (Make) only -- doesn't recurse into sub-IFDs or
// build BoxNode/BoxField output, just enough to answer "which manufacturer" before the real walk
// (see decodeTiff) decides which MakerNote tag table to use.
private fun peekMakeValue(reader: ByteReader, tiffStart: Long, ifdOffset: Long, itemEnd: Long, littleEndian: Boolean): String? {
    if (ifdOffset + 2 > itemEnd) return null
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        if (tag == 0x010F) {
            val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
            val count = readUInt32Endian(reader, pos + 4, littleEndian)
            val valueOffsetPos = pos + 8
            val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
            val totalSize = typeSize * count
            val valueAbsolutePos = if (totalSize <= 4) {
                valueOffsetPos
            } else {
                tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
            }
            if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) return null
            return formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian).trim()
        }
        pos += 12
    }
    return null
}

private fun decodeIfd(
    reader: ByteReader,
    tiffStart: Long,
    ifdOffset: Long,
    itemEnd: Long,
    littleEndian: Boolean,
    label: String,
    tagNames: Map<Int, String>,
    makerNoteTagNames: Map<Int, String>,
    visitedOffsets: MutableSet<Long>,
): BoxNode {
    if (!visitedOffsets.add(ifdOffset)) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("Circular or duplicate IFD reference detected, skipping"))
    }
    if (ifdOffset + 2 > itemEnd) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("IFD too short to contain entry_count"))
    }
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    var jpegThumbnailOffset: Long? = null
    var jpegThumbnailLength: Long? = null
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }

        when (tag) {
            TAG_EXIF_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Exif", TAG_NAMES_EXIF, makerNoteTagNames, visitedOffsets),
                )
            }
            TAG_GPS_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "GPS", TAG_NAMES_GPS, makerNoteTagNames, visitedOffsets),
                )
            }
            TAG_INTEROP_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Interop", TAG_NAMES_EXIF, makerNoteTagNames, visitedOffsets),
                )
            }
            TAG_MAKER_NOTE -> {
                if (valueAbsolutePos >= 0 && valueAbsolutePos + count <= itemEnd) {
                    children.add(decodeMakerNote(reader, tiffStart, valueAbsolutePos, count.toInt(), littleEndian, itemEnd, makerNoteTagNames))
                }
            }
            TAG_SUB_IFDS -> {
                for (i in 0 until count.toInt()) {
                    val entryPos = valueAbsolutePos + i * 4L
                    if (entryPos + 4 > itemEnd) break
                    val subIfdOffset = tiffStart + readUInt32Endian(reader, entryPos, littleEndian)
                    children.add(
                        decodeIfd(reader, tiffStart, subIfdOffset, itemEnd, littleEndian, "SubIFD$i", TAG_NAMES_IFD0, makerNoteTagNames, visitedOffsets),
                    )
                }
            }
            TAG_JPEG_INTERCHANGE_FORMAT -> {
                jpegThumbnailOffset = readUInt32Endian(reader, valueOffsetPos, littleEndian)
            }
            TAG_JPEG_INTERCHANGE_FORMAT_LENGTH -> {
                jpegThumbnailLength = readUInt32Endian(reader, valueOffsetPos, littleEndian)
            }
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = interpretedDisplay(label, tag, fieldType, count.toInt(), reader, valueAbsolutePos, littleEndian)
                        ?: formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
        }
        pos += 12
    }

    val thumbnailOffset = jpegThumbnailOffset
    val thumbnailLength = jpegThumbnailLength
    if (thumbnailOffset != null && thumbnailLength != null && thumbnailLength > 0) {
        val thumbnailAbsoluteOffset = tiffStart + thumbnailOffset
        if (thumbnailAbsoluteOffset >= 0 && thumbnailAbsoluteOffset + thumbnailLength <= itemEnd) {
            children.add(
                BoxNode(type = "ThumbnailImage", offset = thumbnailAbsoluteOffset, headerSize = 0, size = thumbnailLength, summary = "$thumbnailLength bytes"),
            )
        }
    }

    return BoxNode(
        type = label, offset = ifdOffset, headerSize = 2, size = pos - ifdOffset,
        fields = fields, children = children,
    )
}

// This assumes a standard-IFD MakerNote body (2-byte entry_count + 12-byte entries, same shape as
// a regular IFD) starting right at the tag's own value offset -- true for Samsung's "Type2"
// (EXIF-format) MakerNote, which is what current Samsung phones/cameras use, and what
// makerNoteTagNames (see decodeTiff) resolves to for Make == Samsung. Samsung has also used a
// "STMN" binary format on older models with a genuinely different byte layout (not IFD-shaped at
// all) and a plain-IFD variant with device-relative offsets -- neither is handled here, so a
// MakerNote from either would come through as unlabeled/garbled "Tag 0x...." entries rather than
// throwing, since the bounds checks below still apply.
private fun decodeMakerNote(
    reader: ByteReader,
    tiffStart: Long,
    absolutePos: Long,
    byteLength: Int,
    littleEndian: Boolean,
    itemEnd: Long,
    tagNames: Map<Int, String>,
): BoxNode {
    val endPos = absolutePos + byteLength
    if (byteLength < 2) {
        return BoxNode("MakerNote", absolutePos, 0, byteLength.toLong(), warnings = listOf("MakerNote too short"))
    }
    val entryCount = readUInt16Endian(reader, absolutePos, littleEndian)
    val fields = mutableListOf<BoxField>()
    var pos = absolutePos + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > endPos) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }
        val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
        if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
            fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
        } else {
            val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
            fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
        }
        pos += 12
    }
    return BoxNode(type = "MakerNote", offset = absolutePos, headerSize = 2, size = byteLength.toLong(), fields = fields)
}

private fun formatTiffValue(reader: ByteReader, type: Int, count: Int, valuePos: Long, littleEndian: Boolean): String {
    return when (type) {
        2 -> {
            val bytes = reader.readBytes(valuePos, count)
            val nullIndex = bytes.indexOf(0)
            String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.UTF_8)
        }
        3 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toString() }
        8 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toShort().toString() }
        4 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toString() }
        9 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toInt().toString() }
        5 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian)
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian)
            "$num/$den"
        }
        10 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian).toInt()
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian).toInt()
            "$num/$den"
        }
        else -> {
            val bytes = reader.readBytes(valuePos, count.coerceAtMost(64))
            bytes.joinToString(" ") { "%02x".format(it) }
        }
    }
}

private fun readUInt16Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Int {
    if (!littleEndian) return reader.readUInt16(offset)
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Long {
    if (!littleEndian) return reader.readUInt32(offset)
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}
