package com.multiviewer.parser.conformance.jpeg

enum class ExifIfd(val label: String) { IFD0("0th IFD"), IFD1("1st IFD"), EXIF("Exif IFD"), GPS("GPS IFD"), INTEROP("Interoperability IFD"), MP_INDEX("MP Index IFD") }

/** One 12-byte IFD entry. Offsets are relative to the TIFF header (byte-order mark). */
data class TiffEntry(val tag: Int, val type: Int, val count: Long, val entryOffset: Int, val valueOffset: Long) {
    /** Value size in bytes, or null for a type whose size is unknown. */
    val byteSize: Long? get() = TYPE_SIZES[type]?.let { it * count }
    val inline: Boolean get() = (byteSize ?: Long.MAX_VALUE) <= 4

    companion object {
        val TYPE_SIZES: Map<Int, Long> = mapOf(1 to 1L, 2 to 1L, 3 to 2L, 4 to 4L, 5 to 8L, 6 to 1L, 7 to 1L, 8 to 2L,
            9 to 4L, 10 to 8L, 11 to 4L, 12 to 8L, 13 to 4L, 129 to 1L)
    }
}

data class TiffIfd(val kind: ExifIfd, val offset: Long, val entries: List<TiffEntry>, val next: Long) {
    fun entry(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
}

/**
 * A TIFF structure held in memory (an Exif APP1 or MPF APP2 payload, at most 64 KiB). [base] is the absolute file offset
 * of the TIFF header, for byte ranges. Reading never throws: problems are collected instead.
 */
class ExifTiff private constructor(val bytes: ByteArray, val base: Long) {
    var littleEndian = false
        private set
    val headerProblems = ArrayList<String>()
    val structureProblems = ArrayList<String>()
    val ifds = LinkedHashMap<ExifIfd, TiffIfd>()
    /** IFDs that are pointed to but could not be read (loop, out of range, bad pointer): their tags were never seen. */
    val unreadable = LinkedHashSet<ExifIfd>()
    val headerOk: Boolean get() = headerProblems.isEmpty()

    fun u8(o: Long): Int = bytes[o.toInt()].toInt() and 0xFF
    fun u16(o: Long): Int = if (littleEndian) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)
    fun u32(o: Long): Long = if (littleEndian) u16(o).toLong() or (u16(o + 2).toLong() shl 16) else (u16(o).toLong() shl 16) or u16(o + 2).toLong()
    fun inRange(o: Long, size: Long): Boolean = o >= 0 && size >= 0 && o + size <= bytes.size

    /** SHORT/LONG value of a single-count entry, or null. */
    fun uint(e: TiffEntry): Long? = when {
        e.count != 1L -> null
        e.type == 3 -> u16(e.valueOffset).toLong()
        e.type == 4 || e.type == 13 -> u32(e.valueOffset)
        else -> null
    }

    fun valueBytes(e: TiffEntry): ByteArray? {
        val size = e.byteSize ?: return null
        if (!inRange(e.valueOffset, size) || size > Int.MAX_VALUE) return null
        return bytes.copyOfRange(e.valueOffset.toInt(), (e.valueOffset + size).toInt())
    }

    /** Reads the IFD at [offset]; null (with a structure problem) when it does not fit. */
    fun readIfd(kind: ExifIfd, offset: Long): TiffIfd? {
        val where = "${kind.label} @${offset}"
        if (offset < 8 || !inRange(offset, 2)) { structureProblems += "$where: IFD 오프셋이 데이터 밖 / IFD offset outside the data (${bytes.size} bytes)"; return null }
        val n = u16(offset)
        if (!inRange(offset, 2L + 12L * n + 4)) { structureProblems += "$where: 항목 ${n}개 + 다음 IFD 오프셋이 데이터 끝을 넘음 / $n entries overrun the data"; return null }
        val entries = (0 until n).map { i ->
            val e = offset + 2 + 12L * i
            val type = u16(e + 2)
            val count = u32(e + 4)
            val size = TiffEntry.TYPE_SIZES[type]?.let { it * count }
            val valueOffset = if (size != null && size <= 4) e + 8 else u32(e + 8)
            TiffEntry(u16(e), type, count, e.toInt(), valueOffset)
        }
        entries.filter { it.byteSize != null && !it.inline && !inRange(it.valueOffset, it.byteSize!!) }.forEach {
            structureProblems += "$where: 태그 0x%04X 값(오프셋 %d, %d바이트)이 데이터 밖 / value outside the data".format(it.tag, it.valueOffset, it.byteSize)
        }
        return TiffIfd(kind, offset, entries, u32(offset + 2 + 12L * n))
    }

    companion object {
        /** TIFF header + Exif IFD tree (0th → 1st, 0th → Exif/GPS, Exif → Interoperability). */
        fun read(bytes: ByteArray, base: Long): ExifTiff {
            val t = ExifTiff(bytes, base)
            if (!t.readHeader()) return t
            t.walkExif()
            return t
        }

        /** Header only (MPF): byte order, 42, first IFD offset. */
        fun header(bytes: ByteArray, base: Long): ExifTiff = ExifTiff(bytes, base).also { it.readHeader() }
    }

    var firstIfd: Long = 0
        private set

    private fun readHeader(): Boolean {
        if (bytes.size < 8) { headerProblems += "TIFF 헤더 8바이트가 없음 (${bytes.size}바이트) / no 8-byte TIFF header"; return false }
        littleEndian = when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false
            else -> { headerProblems += "바이트 순서 0x%02X%02X — \"II\"/\"MM\"이 아님 / byte order is not II or MM".format(u8(0), u8(1)); return false }
        }
        if (u16(2) != 42) { headerProblems += "식별값 ${u16(2)} ≠ 42 (002A.H)"; return false }
        firstIfd = u32(4)
        if (firstIfd < 8 || firstIfd + 2 > bytes.size) { headerProblems += "0th IFD 오프셋 $firstIfd — 데이터(${bytes.size}바이트) 밖 / outside the data"; return false }
        return true
    }

    private fun walkExif() {
        val visited = HashSet<Long>()
        fun visit(kind: ExifIfd, offset: Long): TiffIfd? {
            if (!visited.add(offset)) { structureProblems += "${kind.label} @$offset: 이미 읽은 IFD를 다시 가리킴(순환/공유) / IFD loop or shared IFD"; unreadable += kind; return null }
            return readIfd(kind, offset)?.also { ifds[kind] = it } ?: run { unreadable += kind; null }
        }
        val ifd0 = visit(ExifIfd.IFD0, firstIfd) ?: return
        if (ifd0.next != 0L) {
            visit(ExifIfd.IFD1, ifd0.next)?.let { ifd1 ->
                if (ifd1.next != 0L) structureProblems += "1st IFD @${ifd1.offset}: 다음 IFD 오프셋 ${ifd1.next} — APP1에는 0th/1st IFD 최대 2개 (§4.5.4, p.32) / at most two IFDs"
            }
        }
        fun pointer(from: TiffIfd, tag: Int, kind: ExifIfd) {
            val e = from.entry(tag) ?: return
            val off = uint(e)
            if (off == null) { structureProblems += "${from.kind.label}: ${kind.label} 포인터(0x%04X)를 읽을 수 없음 (Type ${e.type}, Count ${e.count}) / unreadable pointer".format(tag); unreadable += kind }
            else visit(kind, off)
        }
        pointer(ifd0, 0x8769, ExifIfd.EXIF)
        pointer(ifd0, 0x8825, ExifIfd.GPS)
        ifds[ExifIfd.EXIF]?.let { pointer(it, 0xA005, ExifIfd.INTEROP) }
    }
}

/** One tag definition of CIPA DC-008-2026 (Type, Count, physical PDF page of its definition). */
data class ExifTagDef(val group: ExifTagGroup, val tag: Int, val name: String, val types: Set<Int>, val count: Long?, val page: Int)

enum class ExifTagGroup { TIFF, EXIF, GPS, INTEROP }

fun ExifIfd.group(): ExifTagGroup = when (this) {
    ExifIfd.IFD0, ExifIfd.IFD1, ExifIfd.MP_INDEX -> ExifTagGroup.TIFF
    ExifIfd.EXIF -> ExifTagGroup.EXIF
    ExifIfd.GPS -> ExifTagGroup.GPS
    ExifIfd.INTEROP -> ExifTagGroup.INTEROP
}

/** Tag Type/Count definitions copied from CIPA DC-008-Translation-2026 (Exif 3.1) §4.6.3-4.6.8 ("Tag = / Type = / Count ="). */
object ExifTagTable {
    private fun d(g: ExifTagGroup, tag: Int, name: String, types: Set<Int>, count: Long?, page: Int) = ExifTagDef(g, tag, name, types, count, page)
    private val TIFF = ExifTagGroup.TIFF
    private val EXIF = ExifTagGroup.EXIF
    private val GPS = ExifTagGroup.GPS
    private val INTEROP = ExifTagGroup.INTEROP

    val all: List<ExifTagDef> = listOf(
        d(TIFF, 0x8769, "ExifIFDPointer", setOf(4), 1, 39),
        d(TIFF, 0x8825, "GPSInfoIFDPointer", setOf(4), 1, 40),
        d(EXIF, 0xA005, "InteroperabilityIFDPointer", setOf(4), 1, 40),
        d(TIFF, 0x0100, "ImageWidth", setOf(3, 4), 1, 43),
        d(TIFF, 0x0101, "ImageLength", setOf(3, 4), 1, 43),
        d(TIFF, 0x0102, "BitsPerSample", setOf(3), 3, 43),
        d(TIFF, 0x0103, "Compression", setOf(3), 1, 44),
        d(TIFF, 0x0106, "PhotometricInterpretation", setOf(3), 1, 44),
        d(TIFF, 0x0112, "Orientation", setOf(3), 1, 44),
        d(TIFF, 0x0115, "SamplesPerPixel", setOf(3), 1, 48),
        d(TIFF, 0x011A, "XResolution", setOf(5), 1, 48),
        d(TIFF, 0x011B, "YResolution", setOf(5), 1, 48),
        d(TIFF, 0x011C, "PlanarConfiguration", setOf(3), 1, 49),
        d(TIFF, 0x0128, "ResolutionUnit", setOf(3), 1, 49),
        d(TIFF, 0x0212, "YCbCrSubSampling", setOf(3), 2, 49),
        d(TIFF, 0x0213, "YCbCrPositioning", setOf(3), 1, 50),
        d(TIFF, 0x0111, "StripOffsets", setOf(3, 4), null, 50),
        d(TIFF, 0x0116, "RowsPerStrip", setOf(3, 4), 1, 51),
        d(TIFF, 0x0117, "StripByteCounts", setOf(3, 4), null, 51),
        d(TIFF, 0x0201, "JPEGInterchangeFormat", setOf(4), 1, 51),
        d(TIFF, 0x0202, "JPEGInterchangeFormatLength", setOf(4), 1, 51),
        d(TIFF, 0x012D, "TransferFunction", setOf(3), 768, 52),
        d(TIFF, 0x013E, "WhitePoint", setOf(5), 2, 52),
        d(TIFF, 0x013F, "PrimaryChromaticities", setOf(5), 6, 52),
        d(TIFF, 0x0211, "YCbCrCoefficients", setOf(5), 3, 52),
        d(TIFF, 0x0214, "ReferenceBlackWhite", setOf(5), 6, 52),
        d(TIFF, 0x010E, "ImageDescription", setOf(2, 129), null, 53),
        d(TIFF, 0x010F, "Make", setOf(2, 129), null, 53),
        d(TIFF, 0x0110, "Model", setOf(2, 129), null, 53),
        d(TIFF, 0x0131, "Software", setOf(2, 129), null, 53),
        d(TIFF, 0x0132, "DateTime", setOf(2), 20, 54),
        d(TIFF, 0x013B, "Artist", setOf(2, 129), null, 54),
        d(TIFF, 0x8298, "Copyright", setOf(2, 129), null, 54),
        d(EXIF, 0x9000, "ExifVersion", setOf(7), 4, 58),
        d(EXIF, 0xA000, "FlashpixVersion", setOf(7), 4, 58),
        d(EXIF, 0xA001, "ColorSpace", setOf(3), 1, 58),
        d(EXIF, 0xA500, "Gamma", setOf(5), 1, 59),
        d(EXIF, 0xA002, "PixelXDimension", setOf(3, 4), 1, 59),
        d(EXIF, 0xA003, "PixelYDimension", setOf(3, 4), 1, 59),
        d(EXIF, 0x9101, "ComponentsConfiguration", setOf(7), 4, 60),
        d(EXIF, 0x9102, "CompressedBitsPerPixel", setOf(5), 1, 60),
        d(EXIF, 0x927C, "MakerNote", setOf(7), null, 60),
        d(EXIF, 0x9286, "UserComment", setOf(7), null, 60),
        d(EXIF, 0xA004, "RelatedSoundFile", setOf(2), 13, 63),
        d(EXIF, 0x9003, "DateTimeOriginal", setOf(2), 20, 63),
        d(EXIF, 0x9004, "DateTimeDigitized", setOf(2), 20, 64),
        d(EXIF, 0x9010, "OffsetTime", setOf(2), 7, 64),
        d(EXIF, 0x9011, "OffsetTimeOriginal", setOf(2), 7, 64),
        d(EXIF, 0x9012, "OffsetTimeDigitized", setOf(2), 7, 65),
        d(EXIF, 0x9290, "SubsecTime", setOf(2), null, 65),
        d(EXIF, 0x9291, "SubsecTimeOriginal", setOf(2), null, 65),
        d(EXIF, 0x9292, "SubsecTimeDigitized", setOf(2), null, 65),
        d(EXIF, 0x829A, "ExposureTime", setOf(5), 1, 66),
        d(EXIF, 0x829D, "FNumber", setOf(5), 1, 66),
        d(EXIF, 0x8822, "ExposureProgram", setOf(3), 1, 66),
        d(EXIF, 0x8824, "SpectralSensitivity", setOf(2), null, 67),
        d(EXIF, 0x8827, "PhotographicSensitivity", setOf(3), null, 67),
        d(EXIF, 0x8828, "OECF", setOf(7), null, 67),
        d(EXIF, 0x8830, "SensitivityType", setOf(3), 1, 68),
        d(EXIF, 0x8831, "StandardOutputSensitivity", setOf(4), 1, 69),
        d(EXIF, 0x8832, "RecommendedExposureIndex", setOf(4), 1, 69),
        d(EXIF, 0x8833, "ISOSpeed", setOf(4), 1, 69),
        d(EXIF, 0x8834, "ISOSpeedLatitudeyyy", setOf(4), 1, 70),
        d(EXIF, 0x8835, "ISOSpeedLatitudezzz", setOf(4), 1, 70),
        d(EXIF, 0x9201, "ShutterSpeedValue", setOf(10), 1, 70),
        d(EXIF, 0x9202, "ApertureValue", setOf(5), 1, 70),
        d(EXIF, 0x9203, "BrightnessValue", setOf(10), 1, 70),
        d(EXIF, 0x9204, "ExposureBiasValue", setOf(10), 1, 70),
        d(EXIF, 0x9205, "MaxApertureValue", setOf(5), 1, 71),
        d(EXIF, 0x9206, "SubjectDistance", setOf(5), 1, 71),
        d(EXIF, 0x9207, "MeteringMode", setOf(3), 1, 71),
        d(EXIF, 0x9208, "LightSource", setOf(3), 1, 71),
        d(EXIF, 0x9209, "Flash", setOf(3), 1, 72),
        d(EXIF, 0x9214, "SubjectArea", setOf(3), null, 73),
        d(EXIF, 0x920A, "FocalLength", setOf(5), 1, 75),
        d(EXIF, 0xA20B, "FlashEnergy", setOf(5), 1, 75),
        d(EXIF, 0xA20E, "FocalPlaneXResolution", setOf(5), 1, 76),
        d(EXIF, 0xA20F, "FocalPlaneYResolution", setOf(5), 1, 77),
        d(EXIF, 0xA210, "FocalPlaneResolutionUnit", setOf(3), 1, 77),
        d(EXIF, 0xA214, "SubjectLocation", setOf(3), 2, 77),
        d(EXIF, 0xA215, "ExposureIndex", setOf(5), 1, 77),
        d(EXIF, 0xA217, "SensingMethod", setOf(3), 1, 78),
        d(EXIF, 0xA300, "FileSource", setOf(7), 1, 78),
        d(EXIF, 0xA301, "SceneType", setOf(7), 1, 78),
        d(EXIF, 0xA302, "CFAPattern", setOf(7), null, 78),
        d(EXIF, 0xA401, "CustomRendered", setOf(3), 1, 80),
        d(EXIF, 0xA402, "ExposureMode", setOf(3), 1, 80),
        d(EXIF, 0xA403, "WhiteBalance", setOf(3), 1, 80),
        d(EXIF, 0xA404, "DigitalZoomRatio", setOf(5), 1, 80),
        d(EXIF, 0xA405, "FocalLengthIn35mmFilm", setOf(3), 1, 81),
        d(EXIF, 0xA406, "SceneCaptureType", setOf(3), 1, 81),
        d(EXIF, 0xA407, "GainControl", setOf(3), 1, 81),
        d(EXIF, 0xA408, "Contrast", setOf(3), 1, 81),
        d(EXIF, 0xA409, "Saturation", setOf(3), 1, 82),
        d(EXIF, 0xA40A, "Sharpness", setOf(3), 1, 82),
        d(EXIF, 0xA40B, "DeviceSettingDescription", setOf(7), null, 82),
        d(EXIF, 0xA40C, "SubjectDistanceRange", setOf(3), 1, 83),
        d(EXIF, 0xA460, "CompositeImage", setOf(3), 1, 86),
        d(EXIF, 0xA461, "SourceImageNumberOfCompositeImage", setOf(3), 2, 86),
        d(EXIF, 0xA462, "SourceExposureTimesOfCompositeImage", setOf(7), null, 87),
        d(EXIF, 0x9400, "Temperature", setOf(10), 1, 88),
        d(EXIF, 0x9401, "Humidity", setOf(5), 1, 88),
        d(EXIF, 0x9402, "Pressure", setOf(5), 1, 89),
        d(EXIF, 0x9403, "WaterDepth", setOf(10), 1, 89),
        d(EXIF, 0x9404, "Acceleration", setOf(5), 1, 89),
        d(EXIF, 0x9405, "CameraElevationAngle", setOf(10), 1, 89),
        d(EXIF, 0xA420, "ImageUniqueID", setOf(2), 33, 90),
        d(EXIF, 0xA430, "CameraOwnerName", setOf(2, 129), null, 90),
        d(EXIF, 0xA431, "BodySerialNumber", setOf(2), null, 90),
        d(EXIF, 0xA432, "LensSpecification", setOf(5), 4, 90),
        d(EXIF, 0xA433, "LensMake", setOf(2, 129), null, 91),
        d(EXIF, 0xA434, "LensModel", setOf(2, 129), null, 91),
        d(EXIF, 0xA435, "LensSerialNumber", setOf(2), null, 91),
        d(EXIF, 0xA436, "ImageTitle", setOf(2, 129), null, 91),
        d(EXIF, 0xA437, "Photographer", setOf(2, 129), null, 91),
        d(EXIF, 0xA438, "ImageEditor", setOf(2, 129), null, 92),
        d(EXIF, 0xA439, "CameraFirmware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43A, "RAWDevelopingSoftware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43B, "ImageEditingSoftware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43C, "MetadataEditingSoftware", setOf(2, 129), null, 93),
        d(EXIF, 0x9287, "LearningOptOutIn", setOf(7), null, 61),
        d(EXIF, 0xA40D, "DevelopmentType", setOf(3), 1, 84),
        d(EXIF, 0xA40E, "DevelopmentTypeDescription", setOf(129), null, 85),
        d(EXIF, 0xA40F, "DistortionCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA410, "ChromaticAberrationCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA411, "ShadingCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA412, "NoiseReduction", setOf(3), 1, 85),
        d(GPS, 0x0000, "GPSVersionID", setOf(1), 4, 94),
        d(GPS, 0x0001, "GPSLatitudeRef", setOf(2), 2, 95),
        d(GPS, 0x0002, "GPSLatitude", setOf(5), 3, 95),
        d(GPS, 0x0003, "GPSLongitudeRef", setOf(2), 2, 95),
        d(GPS, 0x0004, "GPSLongitude", setOf(5), 3, 95),
        d(GPS, 0x0005, "GPSAltitudeRef", setOf(1), 1, 96),
        d(GPS, 0x0006, "GPSAltitude", setOf(5), 1, 96),
        d(GPS, 0x0007, "GPSTimeStamp", setOf(5), 3, 96),
        d(GPS, 0x0008, "GPSSatellites", setOf(2), null, 97),
        d(GPS, 0x0009, "GPSStatus", setOf(2), 2, 97),
        d(GPS, 0x000A, "GPSMeasureMode", setOf(2), 2, 97),
        d(GPS, 0x000B, "GPSDOP", setOf(5), 1, 97),
        d(GPS, 0x000C, "GPSSpeedRef", setOf(2), 2, 98),
        d(GPS, 0x000D, "GPSSpeed", setOf(5), 1, 98),
        d(GPS, 0x000E, "GPSTrackRef", setOf(2), 2, 98),
        d(GPS, 0x000F, "GPSTrack", setOf(5), 1, 98),
        d(GPS, 0x0010, "GPSImgDirectionRef", setOf(2), 2, 98),
        d(GPS, 0x0011, "GPSImgDirection", setOf(5), 1, 99),
        d(GPS, 0x0012, "GPSMapDatum", setOf(2), null, 99),
        d(GPS, 0x0013, "GPSDestLatitudeRef", setOf(2), 2, 99),
        d(GPS, 0x0014, "GPSDestLatitude", setOf(5), 3, 99),
        d(GPS, 0x0015, "GPSDestLongitudeRef", setOf(2), 2, 100),
        d(GPS, 0x0016, "GPSDestLongitude", setOf(5), 3, 100),
        d(GPS, 0x0017, "GPSDestBearingRef", setOf(2), 2, 100),
        d(GPS, 0x0018, "GPSDestBearing", setOf(5), 1, 100),
        d(GPS, 0x0019, "GPSDestDistanceRef", setOf(2), 2, 101),
        d(GPS, 0x001A, "GPSDestDistance", setOf(5), 1, 101),
        d(GPS, 0x001B, "GPSProcessingMethod", setOf(7), null, 101),
        d(GPS, 0x001C, "GPSAreaInformation", setOf(7), null, 102),
        d(GPS, 0x001D, "GPSDateStamp", setOf(2), 11, 102),
        d(GPS, 0x001E, "GPSDifferential", setOf(3), 1, 102),
        d(GPS, 0x001F, "GPSHPositioningError", setOf(5), 1, 103),
        d(INTEROP, 0x0001, "InteroperabilityIndex", setOf(2), null, 103),
    )

    private val byKey = all.associateBy { it.group to it.tag }

    fun def(ifd: ExifIfd, tag: Int): ExifTagDef? = byKey[ifd.group() to tag]

    fun name(ifd: ExifIfd, tag: Int): String = def(ifd, tag)?.name ?: "0x%04X".format(tag)

    /** Field types listed in §4.6.2 ("The following types are used in Exif"). */
    val EXIF_TYPES = setOf(1, 2, 3, 4, 5, 7, 9, 10, 129)
}
