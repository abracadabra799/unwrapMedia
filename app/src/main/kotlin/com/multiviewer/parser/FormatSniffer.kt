package com.multiviewer.parser

import com.multiviewer.parser.integrity.parseMpegFrame
import com.multiviewer.ui.ALL_SUPPORTED_MEDIA_EXTENSIONS
import com.multiviewer.ui.AUDIO_EXTENSIONS
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.VIDEO_EXTENSIONS
import java.io.File
import java.util.Locale

/**
 * What a file actually is, decided from its bytes rather than its name. The single source of truth
 * for both parseFile()'s parser dispatch and AppState.openFile()'s "can this be opened / is it an
 * image, video or audio" decision -- so a HEIC saved as .jpg opens (and parses) as HEIC.
 *
 * [extensions] is the family of extensions this container is commonly saved under: a file whose
 * extension falls outside it gets an "extension mismatch" warning (see [formatMismatch]). The
 * families deliberately overlap where real files mix them (QuickTime-branded .mp4, ISO-branded
 * .mov) so the warning means "really a different kind of file", not "ftyp brand nitpick".
 */
enum class ContainerFormat(
    val displayName: String,
    val mediaType: MediaType,
    val extensions: Set<String>,
    // Other media types this container legitimately carries; the extension picks among them.
    private val alternateTypes: Set<MediaType> = emptySet(),
) {
    JPEG("JPEG", MediaType.IMAGE, setOf("jpg", "jpeg")),
    PNG("PNG", MediaType.IMAGE, setOf("png")),
    BMP("BMP", MediaType.IMAGE, setOf("bmp")),
    GIF("GIF", MediaType.IMAGE, setOf("gif")),
    TIFF("TIFF", MediaType.IMAGE, setOf("tif", "tiff", "cr2", "nef", "arw", "dng")),
    WEBP("WebP", MediaType.IMAGE, setOf("webp")),
    HEIF("HEIF", MediaType.IMAGE, setOf("heic", "heif", "hif")),
    AVIF("AVIF", MediaType.IMAGE, setOf("avif")),
    MP4("MP4", MediaType.VIDEO, setOf("mp4", "m4v", "mov", "m4a", "apv", "av1", "av2", "3gp", "3g2"), setOf(MediaType.AUDIO)),
    MOV("QuickTime MOV", MediaType.VIDEO, setOf("mov", "mp4", "m4v"), setOf(MediaType.AUDIO)),
    M4A("M4A", MediaType.AUDIO, setOf("m4a", "mp4")),
    WEBM_MKV("WebM/Matroska", MediaType.VIDEO, setOf("webm"), setOf(MediaType.AUDIO)),
    AVI("AVI", MediaType.VIDEO, setOf("avi")),
    FLV("FLV", MediaType.VIDEO, setOf("flv")),
    ASF("ASF", MediaType.VIDEO, setOf("asf", "wmv", "wma"), setOf(MediaType.AUDIO)),
    WAV("WAV", MediaType.AUDIO, setOf("wav")),
    AIFF("AIFF", MediaType.AUDIO, setOf("aiff", "aif", "aifc")),
    FLAC("FLAC", MediaType.AUDIO, setOf("flac")),
    OGG("Ogg", MediaType.AUDIO, setOf("ogg", "opus")),
    MP3("MP3", MediaType.AUDIO, setOf("mp3")),
    AAC("AAC (ADTS)", MediaType.AUDIO, setOf("aac")),
    ;

    /** The tab's media type: [mediaType], unless the extension names another type this container carries (.wma ASF = audio). */
    fun mediaTypeFor(extension: String): MediaType {
        val ext = extension.lowercase(Locale.US)
        return if (MediaType.AUDIO in alternateTypes && ext in AUDIO_EXTENSIONS) MediaType.AUDIO else mediaType
    }

    val isHeifFamily: Boolean get() = this == HEIF || this == AVIF
}

data class ExtensionMismatch(val extension: String, val actual: ContainerFormat)

/**
 * The summary panel's record of an [ExtensionMismatch]: a "Content Format" row at the top of the
 * first section, so it sits next to the file's other basic facts rather than in a new panel.
 */
fun MediaSummary.withExtensionMismatch(mismatch: ExtensionMismatch): MediaSummary {
    val shownExt = if (mismatch.extension.isEmpty()) "no extension" else ".${mismatch.extension}"
    val row = SummaryField("Content Format", "${mismatch.actual.displayName} (⚠ extension $shownExt does not match)")
    val first = sections.firstOrNull() ?: return copy(sections = listOf(SummarySection("General", listOf(row))))
    return copy(sections = listOf(first.copy(fields = listOf(row) + first.fields)) + sections.drop(1))
}

fun formatMismatch(format: ContainerFormat, extension: String): ExtensionMismatch? {
    val ext = extension.lowercase(Locale.US)
    return if (ext in format.extensions) null else ExtensionMismatch(ext, format)
}

/**
 * The file's format when its bytes say so convincingly enough to override the extension (see
 * [isConfidentSniff]); null when unreadable, unrecognized or only weakly matched. Reads the first
 * bytes of the file -- never call it on the UI thread.
 */
fun confidentFormat(file: File): ContainerFormat? = try {
    ByteReader.open(file).use { reader -> sniffFormat(reader)?.takeIf { isConfidentSniff(it, reader) } }
} catch (e: Exception) {
    null
}

/**
 * The extension feature code should branch on: the file's own extension when it already belongs to
 * the detected format's family (keeps .dng vs .tif, .jpeg vs .jpg), otherwise the format's canonical
 * one -- a HEIC saved as .jpg is "heic" here. Falls back to the real extension when undetected.
 */
fun contentExtension(file: File, format: ContainerFormat?): String {
    val ext = file.extension.lowercase(Locale.US)
    if (format == null || ext in format.extensions) return ext
    return format.extensions.first()
}

/** [contentExtension] for callers that have only the file (sniffs it: background threads only). */
fun contentExtension(file: File): String = contentExtension(file, confidentFormat(file))

/**
 * Whether the file may be opened at all: a supported extension, or content recognized with confidence (the
 * same rule as AppState.openFile). Reads the file when the extension isn't supported: background only.
 */
fun isOpenableMediaFile(file: File): Boolean =
    file.isFile && (file.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS || confidentFormat(file) != null)

/** Image/video/audio by content, else by extension (same rule as AppState.openFile). Background threads only. */
fun contentMediaType(file: File, format: ContainerFormat? = confidentFormat(file)): MediaType {
    val ext = file.extension.lowercase(Locale.US)
    return when {
        format != null -> format.mediaTypeFor(ext)
        ext in IMAGE_EXTENSIONS -> MediaType.IMAGE
        ext in VIDEO_EXTENSIONS -> MediaType.VIDEO
        ext in AUDIO_EXTENSIONS -> MediaType.AUDIO
        else -> MediaType.UNKNOWN
    }
}

/**
 * Same magic-byte order parseFile() has always used, so its parser choice is unchanged; the
 * ISO BMFF branch is new: null (unrecognized) unless the first box is a real ftyp or a legacy
 * QuickTime opening box.
 */
fun sniffFormat(reader: ByteReader): ContainerFormat? = when {
    reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8 -> ContainerFormat.JPEG
    isPngMagic(reader) -> ContainerFormat.PNG
    isBmpMagic(reader) -> ContainerFormat.BMP
    isGifMagic(reader) -> ContainerFormat.GIF
    isTiffMagic(reader) -> ContainerFormat.TIFF
    isWebpMagic(reader) -> ContainerFormat.WEBP
    isWavMagic(reader) -> ContainerFormat.WAV
    isAviMagic(reader) -> ContainerFormat.AVI
    isFlvMagic(reader) -> ContainerFormat.FLV
    isAsfMagic(reader) -> ContainerFormat.ASF
    isAdtsMagic(reader) -> ContainerFormat.AAC
    isMp3Magic(reader) -> ContainerFormat.MP3
    isEbmlMagic(reader) -> ContainerFormat.WEBM_MKV
    isFlacMagic(reader) -> ContainerFormat.FLAC
    isOggMagic(reader) -> ContainerFormat.OGG
    isAiffMagic(reader) -> ContainerFormat.AIFF
    else -> sniffIsoBmff(reader)
}

/**
 * Whether a sniff is strong enough to open a file whose extension says nothing (or something
 * else). BMP's "BM" and MPEG audio's 11/12-bit sync word occur in arbitrary data, so those need
 * a sane header (BMP) or a second frame exactly where the first one ends (MP3/ADTS).
 */
fun isConfidentSniff(format: ContainerFormat, reader: ByteReader): Boolean = when (format) {
    ContainerFormat.BMP -> reader.length >= 18 && reader.readUInt32LE(14) in BMP_DIB_HEADER_SIZES
    ContainerFormat.MP3 -> hasId3v2(reader) || hasSecondMpegFrame(reader)
    ContainerFormat.AAC -> hasSecondAdtsFrame(reader)
    else -> true
}

private val BMP_DIB_HEADER_SIZES = setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L)

private fun ByteReader.readUInt32LE(offset: Long): Long {
    val b = readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}

private fun hasId3v2(reader: ByteReader): Boolean {
    if (reader.length < 10 || String(reader.readBytes(0, 3), Charsets.US_ASCII) != "ID3") return false
    val version = reader.readUInt8(3)
    val size = reader.readBytes(6, 4)
    return version in 2..4 && size.none { it.toInt() and 0x80 != 0 }
}

private fun id3v2End(reader: ByteReader): Long {
    if (!hasId3v2(reader)) return 0
    val size = reader.readBytes(6, 4).fold(0L) { acc, b -> (acc shl 7) or (b.toLong() and 0x7F) }
    return 10 + size
}

private fun hasSecondMpegFrame(reader: ByteReader): Boolean {
    val frame = parseMpegFrame(reader, 0, reader.length) ?: return false
    if (frame.freeFormat || frame.length <= 4) return false
    return parseMpegFrame(reader, frame.length.toLong(), reader.length) != null
}

private fun hasSecondAdtsFrame(reader: ByteReader): Boolean {
    val start = id3v2End(reader)
    fun isAdtsAt(pos: Long): Boolean {
        if (pos + 7 > reader.length) return false
        val b1 = reader.readUInt8(pos + 1)
        return reader.readUInt8(pos) == 0xFF && (b1 and 0xF0) == 0xF0 && ((b1 shr 1) and 0x03) == 0
    }
    if (!isAdtsAt(start)) return false
    val frameLength = ((reader.readUInt8(start + 3) and 0x03) shl 11) or
        (reader.readUInt8(start + 4) shl 3) or (reader.readUInt8(start + 5) shr 5)
    if (frameLength < 7) return false
    return isAdtsAt(start + frameLength)
}

private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
private val AVIF_BRANDS = setOf("avif", "avis")
private val M4A_BRANDS = setOf("M4A ", "M4B ", "M4P ")
private val LEGACY_QUICKTIME_OPENING_BOXES = setOf("moov", "mdat", "free", "skip", "wide", "pnot")

private fun sniffIsoBmff(reader: ByteReader): ContainerFormat? {
    if (reader.length < 8) return null
    val size = reader.readUInt32(0)
    val type = reader.readFourCC(4)
    if (size != 0L && size != 1L && size < 8) return null
    if (type != "ftyp") return if (type in LEGACY_QUICKTIME_OPENING_BOXES) ContainerFormat.MOV else null
    if (size < 16 || reader.length < 12) return null
    val major = reader.readFourCC(8)
    val compatEnd = minOf(size, reader.length, 16L + 4 * 64)
    val compatible = (16L until compatEnd step 4).filter { it + 4 <= compatEnd }.map { reader.readFourCC(it) }
    return when {
        major in AVIF_BRANDS -> ContainerFormat.AVIF
        major in HEIF_BRANDS -> ContainerFormat.HEIF
        major in M4A_BRANDS -> ContainerFormat.M4A
        major == "qt  " -> ContainerFormat.MOV
        compatible.any { it in AVIF_BRANDS } -> ContainerFormat.AVIF
        compatible.any { it in HEIF_BRANDS } -> ContainerFormat.HEIF
        else -> ContainerFormat.MP4
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun isPngMagic(reader: ByteReader): Boolean {
    if (reader.length < 8) return false
    return reader.readBytes(0, 8).contentEquals(PNG_SIGNATURE)
}

private fun isBmpMagic(reader: ByteReader): Boolean {
    if (reader.length < 2) return false
    val bytes = reader.readBytes(0, 2)
    return bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
}

private fun isGifMagic(reader: ByteReader): Boolean {
    if (reader.length < 6) return false
    val text = String(reader.readBytes(0, 6), Charsets.US_ASCII)
    return text == "GIF87a" || text == "GIF89a"
}

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}

private fun isWebpMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WEBP"
}

private fun isWavMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WAVE"
}

private fun isAviMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && (reader.readFourCC(8) == "AVI " || reader.readFourCC(8) == "AVIX")
}

private fun isFlvMagic(reader: ByteReader): Boolean {
    if (reader.length < 9) return false
    return String(reader.readBytes(0, 3), Charsets.US_ASCII) == "FLV"
}

private fun isAsfMagic(reader: ByteReader): Boolean {
    if (reader.length < 24) return false
    val b = reader.readBytes(0, 16)
    return b[0] == 0x30.toByte() && b[1] == 0x26.toByte() && b[2] == 0xB2.toByte() && b[3] == 0x75.toByte() &&
        b[4] == 0x8E.toByte() && b[5] == 0x66.toByte() && b[6] == 0xCF.toByte() && b[7] == 0x11.toByte() &&
        b[8] == 0xA6.toByte() && b[9] == 0xD9.toByte() && b[10] == 0x00.toByte() && b[11] == 0xAA.toByte() &&
        b[12] == 0x00.toByte() && b[13] == 0x62.toByte() && b[14] == 0xCE.toByte() && b[15] == 0x6C.toByte()
}

private fun isMp3Magic(reader: ByteReader): Boolean {
    if (reader.length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") return true
    if (reader.length < 2) return false
    val b0 = reader.readUInt8(0)
    val b1 = reader.readUInt8(1)
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}

private fun isEbmlMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readUInt32(0) == 0x1A45DFA3L
}

private fun isFlacMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "fLaC"
}

private fun isOggMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "OggS"
}

private fun isAiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    if (reader.readFourCC(0) != "FORM") return false
    val formType = reader.readFourCC(8)
    return formType == "AIFF" || formType == "AIFC"
}
