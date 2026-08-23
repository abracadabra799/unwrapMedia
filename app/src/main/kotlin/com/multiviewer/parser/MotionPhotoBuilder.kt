package com.multiviewer.parser

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter

enum class MotionPhotoFormatVersion {
    V1_MICRO_VIDEO,
    V2_MOTION_PHOTO,
}

object MotionPhotoBuilder {

    private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)
    private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"

    // Samsung SEF (Samsung Extension Format) constants
    private const val SEF_MARKER_MOTION_PHOTO_DATA = 0x0A30
    private const val SEF_MARKER_MOTION_PHOTO_VERSION = 0x0A31
    private const val SEF_VERSION = 0x0000006B // Samsung SEF v1.07

    data class PreservedSefBlock(
        val name: String,
        val marker: Int,
        val typeCode: Int,
        val bytes: ByteArray,
    )

    /**
     * Extracts video track duration in microseconds (us) from an MP4/MOV file.
     * Uses mvhd box timescale and duration. Defaults to 1,500,000us (1.5s) if unable to parse.
     */
    fun extractVideoDurationUs(videoFile: File): Long {
        try {
            FileInputStream(videoFile).use { fis ->
                val bufferSize = minOf(videoFile.length(), 2L * 1024L * 1024L).toInt()
                val buffer = ByteArray(bufferSize)
                var readTotal = 0
                while (readTotal < buffer.size) {
                    val count = fis.read(buffer, readTotal, buffer.size - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
                val mvhdTag = "mvhd".toByteArray(Charsets.US_ASCII)
                var mvhdIdx = -1
                for (i in 0..readTotal - mvhdTag.size) {
                    var match = true
                    for (j in mvhdTag.indices) {
                        if (buffer[i + j] != mvhdTag[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        mvhdIdx = i
                        break
                    }
                }
                if (mvhdIdx != -1 && mvhdIdx + 28 <= readTotal) {
                    val p = mvhdIdx + 4
                    val version = buffer[p].toInt() and 0xFF
                    if (version == 0 && p + 20 <= readTotal) {
                        val timescale = ((buffer[p + 12].toLong() and 0xFF) shl 24) or
                            ((buffer[p + 13].toLong() and 0xFF) shl 16) or
                            ((buffer[p + 14].toLong() and 0xFF) shl 8) or
                            (buffer[p + 15].toLong() and 0xFF)
                        val duration = ((buffer[p + 16].toLong() and 0xFF) shl 24) or
                            ((buffer[p + 17].toLong() and 0xFF) shl 16) or
                            ((buffer[p + 18].toLong() and 0xFF) shl 8) or
                            (buffer[p + 19].toLong() and 0xFF)
                        if (timescale > 0) {
                            return (duration * 1_000_000L) / timescale
                        }
                    } else if (version == 1 && p + 28 <= readTotal) {
                        val timescale = ((buffer[p + 20].toLong() and 0xFF) shl 24) or
                            ((buffer[p + 21].toLong() and 0xFF) shl 16) or
                            ((buffer[p + 22].toLong() and 0xFF) shl 8) or
                            (buffer[p + 23].toLong() and 0xFF)
                        var duration = 0L
                        for (i in 0 until 8) {
                            duration = (duration shl 8) or (buffer[p + 24 + i].toLong() and 0xFF)
                        }
                        if (timescale > 0) {
                            return (duration * 1_000_000L) / timescale
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return 1_500_000L
    }

    /**
     * Builds Motion Photo XMP metadata XML string for JPEG.
     * @param version Format version: v2.0 MotionPhoto (default) or v1.0 MicroVideo.
     * @param videoOffsetFromEof Distance in bytes from the end of the file to the first byte (ftyp) of the video.
     * @param primaryPadding Padding in bytes between the end of primary JPEG image and the first byte of video.
     * @param presentationTimestampUs Shutter sync timestamp in microseconds (defaults to video track duration).
     */
    fun buildGoogleMotionPhotoXmp(
        videoOffsetFromEof: Long,
        primaryPadding: Long = 0L,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): String {
        return if (version == MotionPhotoFormatVersion.V1_MICRO_VIDEO) {
            """
                <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                      GCamera:MicroVideo="1"
                      GCamera:MicroVideoVersion="1"
                      GCamera:MicroVideoOffset="$videoOffsetFromEof"
                      GCamera:MicroVideoPresentationTimestampUs="$presentationTimestampUs"/>
                  </rdf:RDF>
                </x:xmpmeta>
            """.trimIndent()
        } else {
            """
                <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:Container="http://ns.google.com/photos/1.0/container/"
                        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                      GCamera:MotionPhoto="1"
                      GCamera:MotionPhotoVersion="1"
                      GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs">
                      <Container:Directory>
                        <rdf:Seq>
                          <rdf:li rdf:parseType="Resource">
                            <Container:Item
                              Item:Semantic="Primary"
                              Item:Mime="image/jpeg"
                              Item:Padding="$primaryPadding"/>
                          </rdf:li>
                          <rdf:li rdf:parseType="Resource">
                            <Container:Item
                              Item:Mime="video/mp4"
                              Item:Semantic="MotionPhoto"
                              Item:Length="$videoOffsetFromEof"
                              Item:Padding="0"/>
                          </rdf:li>
                        </rdf:Seq>
                      </Container:Directory>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
            """.trimIndent()
        }
    }

    /**
     * Builds Motion Photo XMP metadata XML string for HEIC.
     * @param version Format version: v2.0 MotionPhoto (default) or v1.0 MicroVideo.
     * @param videoOffsetFromEof Distance in bytes from the end of the file to the first byte (ftyp) of the video inside mpvd.
     * @param hasGainMap Whether the original HEIC has HDR gain map metadata.
     * @param presentationTimestampUs Shutter sync timestamp in microseconds (defaults to video track duration).
     */
    fun buildGoogleMotionPhotoHeicXmp(
        videoOffsetFromEof: Long,
        hasGainMap: Boolean,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): String {
        if (version == MotionPhotoFormatVersion.V1_MICRO_VIDEO) {
            val sb = StringBuilder()
            sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core Test.SNAPSHOT\">\n")
            sb.append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            sb.append("    <rdf:Description rdf:about=\"\"\n")
            sb.append("        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
            sb.append("      GCamera:MicroVideo=\"1\"\n")
            sb.append("      GCamera:MicroVideoVersion=\"1\"\n")
            sb.append("      GCamera:MicroVideoOffset=\"$videoOffsetFromEof\"\n")
            sb.append("      GCamera:MicroVideoPresentationTimestampUs=\"$presentationTimestampUs\"/>\n")
            sb.append("  </rdf:RDF>\n")
            sb.append("</x:xmpmeta>")
            return sb.toString()
        }

        val gainMapItem = if (hasGainMap) {
            """          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="GainMap"
              Item:Mime="image/heic"
              Item:Length="0"/>
          </rdf:li>
"""
        } else {
            ""
        }

        val sb = StringBuilder()
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core Test.SNAPSHOT\">\n")
        sb.append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        sb.append("    <rdf:Description rdf:about=\"\"\n")
        sb.append("        xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n")
        sb.append("        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
        sb.append("        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
        sb.append("        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
        sb.append("      hdrgm:Version=\"1.0\"\n")
        sb.append("      GCamera:MotionPhoto=\"1\"\n")
        sb.append("      GCamera:MotionPhotoVersion=\"1\"\n")
        sb.append("      GCamera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\">\n")
        sb.append("      <Container:Directory>\n")
        sb.append("        <rdf:Seq>\n")
        sb.append("          <rdf:li rdf:parseType=\"Resource\">\n")
        sb.append("            <Container:Item\n")
        sb.append("              Item:Semantic=\"Primary\"\n")
        sb.append("              Item:Mime=\"image/heic\"\n")
        sb.append("              Item:Padding=\"8\"/>\n")
        sb.append("          </rdf:li>\n")
        if (hasGainMap) {
            sb.append(gainMapItem)
        }
        sb.append("          <rdf:li rdf:parseType=\"Resource\">\n")
        sb.append("            <Container:Item\n")
        sb.append("              Item:Mime=\"video/mp4\"\n")
        sb.append("              Item:Semantic=\"MotionPhoto\"\n")
        sb.append("              Item:Length=\"$videoOffsetFromEof\"\n")
        sb.append("              Item:Padding=\"0\"/>\n")
        sb.append("          </rdf:li>\n")
        sb.append("        </rdf:Seq>\n")
        sb.append("      </Container:Directory>\n")
        sb.append("    </rdf:Description>\n")
        sb.append("  </rdf:RDF>\n")
        sb.append("</x:xmpmeta>")
        return sb.toString()
    }

    /**
     * Constructs a complete JPEG APP1 segment (Marker + Length + XMP ID + NUL + XML Payload).
     */
    fun buildApp1XmpSegment(xmpText: String): ByteArray {
        val xmpBytes = xmpText.toByteArray(Charsets.UTF_8)
        val prefix = XMP_IDENTIFIER + byteArrayOf(0)
        val payloadSize = prefix.size + xmpBytes.size
        val segmentLength = 2 + payloadSize
        require(segmentLength <= 65535) { "XMP metadata exceeds maximum JPEG APP1 segment size (65535 bytes)" }

        val out = ByteArrayOutputStream(2 + segmentLength)
        out.write(0xFF)
        out.write(0xE1) // APP1
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(prefix)
        out.write(xmpBytes)
        return out.toByteArray()
    }

    /**
     * Extracts existing SEF data blocks from raw SEF bytes (e.g. from JPEG trailer or HEIC sefd box),
     * preserving all original metadata blocks while filtering out old MotionPhoto_Data / MotionPhoto_AutoPlay / MotionPhoto_Version.
     */
    fun extractSefBlocksFromPayload(sefPayload: ByteArray): List<PreservedSefBlock> {
        if (sefPayload.size < 12) return emptyList()
        val tailMagic = String(sefPayload.copyOfRange(sefPayload.size - 4, sefPayload.size), Charsets.US_ASCII)
        if (tailMagic != "SEFT") return emptyList()

        val sefBuf = ByteBuffer.wrap(sefPayload).order(ByteOrder.LITTLE_ENDIAN)
        val sefSize = sefBuf.getInt(sefPayload.size - 8).toLong() and 0xFFFFFFFFL
        val sefhPos = (sefPayload.size - 8 - sefSize).toInt()

        if (sefhPos < 0 || sefhPos + 12 > sefPayload.size) return emptyList()
        val sefhMagic = String(sefPayload.copyOfRange(sefhPos, sefhPos + 4), Charsets.US_ASCII)
        if (sefhMagic != "SEFH") return emptyList()

        val count = sefBuf.getInt(sefhPos + 8)
        val preserved = mutableListOf<PreservedSefBlock>()
        var entryPos = sefhPos + 12

        for (i in 0 until count) {
            if (entryPos + 12 > sefPayload.size) break
            val typeCode = sefBuf.getShort(entryPos).toInt() and 0xFFFF
            val marker = sefBuf.getShort(entryPos + 2).toInt() and 0xFFFF
            val offset = sefBuf.getInt(entryPos + 4).toLong() and 0xFFFFFFFFL
            val length = sefBuf.getInt(entryPos + 8).toLong() and 0xFFFFFFFFL
            val blockStart = (sefhPos - offset).toInt()
            val blockEnd = (blockStart + length).toInt()

            if (blockStart in 0..sefPayload.size && blockEnd in blockStart..sefPayload.size && length >= 8) {
                val nameLen = sefBuf.getInt(blockStart + 4)
                if (nameLen in 1..256 && blockStart + 8 + nameLen <= blockEnd) {
                    val name = String(sefPayload.copyOfRange(blockStart + 8, blockStart + 8 + nameLen), Charsets.UTF_8).trimEnd(Char(0))
                    if (name != "MotionPhoto_Data" && name != "MotionPhoto_AutoPlay" && name != "MotionPhoto_Version") {
                        val blockBytes = sefPayload.copyOfRange(blockStart, blockEnd)
                        preserved.add(PreservedSefBlock(name, marker, typeCode, blockBytes))
                    }
                }
            }
            entryPos += 12
        }
        return preserved
    }

    /**
     * Extracts existing SEF data blocks and base JPEG bytes from a JPEG file.
     */
    fun extractExistingSefBlocks(imageBytes: ByteArray): Pair<ByteArray, List<PreservedSefBlock>> {
        if (imageBytes.size < 12) return Pair(imageBytes, emptyList())

        val tailMagic = String(imageBytes.copyOfRange(imageBytes.size - 4, imageBytes.size), Charsets.US_ASCII)
        if (tailMagic != "SEFT") {
            return Pair(imageBytes, emptyList())
        }

        val sefBuf = ByteBuffer.wrap(imageBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sefSize = sefBuf.getInt(imageBytes.size - 8).toLong() and 0xFFFFFFFFL
        val sefhPos = (imageBytes.size - 8 - sefSize).toInt()

        if (sefhPos < 0 || sefhPos + 12 > imageBytes.size) {
            return Pair(imageBytes, emptyList())
        }

        val sefhMagic = String(imageBytes.copyOfRange(sefhPos, sefhPos + 4), Charsets.US_ASCII)
        if (sefhMagic != "SEFH") {
            return Pair(imageBytes, emptyList())
        }

        val count = sefBuf.getInt(sefhPos + 8)
        var minBlockStart = sefhPos
        var entryPos = sefhPos + 12

        for (i in 0 until count) {
            if (entryPos + 12 > imageBytes.size) break
            val offset = sefBuf.getInt(entryPos + 4).toLong() and 0xFFFFFFFFL
            val blockStart = (sefhPos - offset).toInt()
            if (blockStart in 0..imageBytes.size) {
                minBlockStart = minOf(minBlockStart, blockStart)
            }
            entryPos += 12
        }

        val preserved = extractSefBlocksFromPayload(imageBytes)
        val baseJpeg = imageBytes.copyOfRange(0, minBlockStart)
        return Pair(baseJpeg, preserved)
    }

    /**
     * Extracts base ISOBMFF boxes and preserved SEF blocks from a HEIC file.
     * Strips any existing `mpvd` and `sefd` boxes.
     */
    fun extractExistingHeicBoxesAndSef(heicBytes: ByteArray): Pair<ByteArray, List<PreservedSefBlock>> {
        val baseOut = ByteArrayOutputStream()
        val preservedSef = mutableListOf<PreservedSefBlock>()
        var pos = 0

        while (pos < heicBytes.size - 8) {
            val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or
                ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or
                (heicBytes[pos + 3].toLong() and 0xFF)
            val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)

            val boxLen = when (size) {
                0L -> (heicBytes.size - pos).toLong()
                1L -> {
                    if (pos + 16 > heicBytes.size) break
                    val bb = ByteBuffer.wrap(heicBytes, pos + 8, 8)
                    bb.long
                }
                else -> size
            }

            if (pos + boxLen > heicBytes.size || boxLen < 8) {
                baseOut.write(heicBytes, pos, heicBytes.size - pos)
                break
            }

            val boxEnd = (pos + boxLen).toInt()

            if (fourCC == "sefd") {
                val sefPayload = heicBytes.copyOfRange(pos + 8, boxEnd)
                preservedSef.addAll(extractSefBlocksFromPayload(sefPayload))
            } else if (fourCC != "mpvd") {
                baseOut.write(heicBytes, pos, boxLen.toInt())
            }

            pos = boxEnd
        }

        return Pair(baseOut.toByteArray(), preservedSef)
    }

    /**
     * Finds the exact byte offset and allocated extent length of the XMP item (Item 50) in HEIC.
     * Parses meta -> iloc box first, and falls back to scanning if needed.
     */
    fun findXmpExtentInHeic(heicBytes: ByteArray): Pair<Int, Int>? {
        try {
            var pos = 0
            while (pos < heicBytes.size - 8) {
                val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or
                    ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                    ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or
                    (heicBytes[pos + 3].toLong() and 0xFF)
                val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
                val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
                if (pos + boxLen > heicBytes.size || boxLen < 8) break

                if (fourCC == "meta") {
                    val metaPayloadStart = pos + 12
                    val metaEnd = (pos + boxLen).toInt()
                    var mp = metaPayloadStart
                    while (mp < metaEnd - 8) {
                        val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or
                            ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                            ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or
                            (heicBytes[mp + 3].toInt() and 0xFF)
                        val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
                        if (childSz < 8 || mp + childSz > metaEnd) break

                        if (childFourCC == "iloc") {
                            val ilocVersion = heicBytes[mp + 8].toInt() and 0xFF
                            val offLenSz = heicBytes[mp + 12].toInt() and 0xFF
                            val baseIdxSz = heicBytes[mp + 13].toInt() and 0xFF
                            val offSz = offLenSz shr 4
                            val lenSz = offLenSz and 0x0F
                            val baseOffSz = baseIdxSz shr 4

                            var lp = mp + (if (ilocVersion < 2) 16 else 18)
                            val itemCount = if (ilocVersion < 2) {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 8) or (heicBytes[mp + 15].toInt() and 0xFF)
                            } else {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 24) or
                                    ((heicBytes[mp + 15].toInt() and 0xFF) shl 16) or
                                    ((heicBytes[mp + 16].toInt() and 0xFF) shl 8) or
                                    (heicBytes[mp + 17].toInt() and 0xFF)
                            }

                            for (i in 0 until itemCount) {
                                if (lp >= mp + childSz) break
                                lp += if (ilocVersion < 2) 2 else 4 // item_ID
                                if (ilocVersion in 1..2) lp += 2 // construction_method
                                lp += 2 // data_reference_index

                                var baseOffset = 0L
                                for (b in 0 until baseOffSz) {
                                    baseOffset = (baseOffset shl 8) or (heicBytes[lp].toLong() and 0xFF)
                                    lp++
                                }

                                val extentCount = ((heicBytes[lp].toInt() and 0xFF) shl 8) or (heicBytes[lp + 1].toInt() and 0xFF)
                                lp += 2

                                for (e in 0 until extentCount) {
                                    var extentOffset = 0L
                                    for (b in 0 until offSz) {
                                        extentOffset = (extentOffset shl 8) or (heicBytes[lp].toLong() and 0xFF)
                                        lp++
                                    }
                                    var extentLength = 0L
                                    for (b in 0 until lenSz) {
                                        extentLength = (extentLength shl 8) or (heicBytes[lp].toLong() and 0xFF)
                                        lp++
                                    }

                                    val absOffset = (baseOffset + extentOffset).toInt()
                                    val extLen = extentLength.toInt()
                                    if (absOffset in 0..heicBytes.size && absOffset + extLen <= heicBytes.size && extLen > 20) {
                                        val sample = String(heicBytes.copyOfRange(absOffset, minOf(absOffset + 100, absOffset + extLen)), Charsets.UTF_8)
                                        if (sample.contains("<x:xmpmeta") && (sample.contains("Container") || sample.contains("rdf:Description"))) {
                                            return Pair(absOffset, extLen)
                                        }
                                    }
                                }
                            }
                        }
                        mp += childSz
                    }
                }
                pos += boxLen.toInt()
            }
        } catch (e: Exception) {
            // Fallback to pattern scanning
        }

        // Fallback: pattern scanning
        val xmpOpenTag = "<x:xmpmeta".toByteArray(Charsets.UTF_8)
        val xmpCloseTag = "</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        var xmpStart = -1
        for (i in 0..heicBytes.size - xmpOpenTag.size) {
            var match = true
            for (j in xmpOpenTag.indices) {
                if (heicBytes[i + j] != xmpOpenTag[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                xmpStart = i
                break
            }
        }
        if (xmpStart == -1) return null

        var xmpEnd = -1
        for (i in xmpStart..heicBytes.size - xmpCloseTag.size) {
            var match = true
            for (j in xmpCloseTag.indices) {
                if (heicBytes[i + j] != xmpCloseTag[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                xmpEnd = i + xmpCloseTag.size
                break
            }
        }
        if (xmpEnd == -1) return null

        var extentEnd = xmpEnd
        val paddingBytes = setOf(0x20.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x0D.toByte())
        while (extentEnd < heicBytes.size && heicBytes[extentEnd] in paddingBytes) {
            extentEnd++
        }
        return Pair(xmpStart, extentEnd - xmpStart)
    }

    /**
     * Updates the Motion Photo XMP metadata item in HEIC (referenced by iloc in the meta box) in place.
     */
    fun updateHeicXmpItem(
        baseHeicBytes: ByteArray,
        videoOffsetFromEof: Long,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): ByteArray {
        val extent = findXmpExtentInHeic(baseHeicBytes) ?: return baseHeicBytes
        val (xmpStart, allocatedLen) = extent

        val oldXmpStr = String(baseHeicBytes.copyOfRange(xmpStart, minOf(xmpStart + allocatedLen, baseHeicBytes.size)), Charsets.UTF_8)
        val hasGainMap = oldXmpStr.contains("GainMap")

        val newXmpText = buildGoogleMotionPhotoHeicXmp(videoOffsetFromEof, hasGainMap, presentationTimestampUs, version)
        val newXmpBytes = newXmpText.toByteArray(Charsets.UTF_8)

        if (newXmpBytes.size <= allocatedLen) {
            val result = baseHeicBytes.copyOf()
            System.arraycopy(newXmpBytes, 0, result, xmpStart, newXmpBytes.size)
            // Fill remainder of extent with space characters (0x20)
            for (k in (xmpStart + newXmpBytes.size) until (xmpStart + allocatedLen)) {
                result[k] = 0x20.toByte()
            }
            return result
        }

        return baseHeicBytes
    }

    /**
     * Converts a non-JPEG image file to standard JPEG byte array at high quality (95%).
     */
    fun convertImageToJpegBytes(imageFile: File): ByteArray {
        val image = ImageIO.read(imageFile) ?: throw IllegalArgumentException("Failed to decode image: ${imageFile.name}")
        val rgbImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = rgbImage.createGraphics()
        g.drawImage(image, 0, 0, java.awt.Color.WHITE, null)
        g.dispose()

        val baos = ByteArrayOutputStream()
        val writers: Iterator<ImageWriter> = ImageIO.getImageWritersByFormatName("jpg")
        if (!writers.hasNext()) {
            ImageIO.write(rgbImage, "jpg", baos)
            return baos.toByteArray()
        }
        val writer = writers.next()
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = 0.95f
        }
        ImageIO.createImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(rgbImage, null, null), param)
            writer.dispose()
        }
        return baos.toByteArray()
    }

    /**
     * Injects the Motion Photo APP1 XMP segment into JPEG bytes at standard position.
     */
    fun injectMotionPhotoXmpIntoJpeg(
        jpegBytes: ByteArray,
        videoOffsetFromEof: Long,
        primaryPadding: Long = 0L,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): ByteArray {
        require(jpegBytes.size >= 4 && (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) {
            "Invalid JPEG bytes: missing SOI marker (0xFFD8)"
        }

        val xmpText = buildGoogleMotionPhotoXmp(videoOffsetFromEof, primaryPadding, presentationTimestampUs, version)
        val app1Segment = buildApp1XmpSegment(xmpText)

        // Check if there is an Exif APP1 immediately after SOI
        var insertPos = 2
        if (jpegBytes.size >= 8 &&
            (jpegBytes[2].toInt() and 0xFF) == 0xFF &&
            (jpegBytes[3].toInt() and 0xFF) == 0xE1
        ) {
            val exifSegLen = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
            val totalExifSegSize = 2 + exifSegLen
            if (insertPos + totalExifSegSize <= jpegBytes.size &&
                insertPos + 4 + EXIF_PREFIX.size <= jpegBytes.size &&
                jpegBytes.copyOfRange(insertPos + 4, insertPos + 4 + EXIF_PREFIX.size).contentEquals(EXIF_PREFIX)
            ) {
                // Keep Exif APP1 as the very first marker after SOI
                insertPos += totalExifSegSize
            }
        }

        val out = ByteArrayOutputStream(jpegBytes.size + app1Segment.size)
        out.write(jpegBytes, 0, insertPos)
        out.write(app1Segment)

        var pos = insertPos
        while (pos < jpegBytes.size) {
            if (pos + 1 >= jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            val b0 = jpegBytes[pos].toInt() and 0xFF
            val b1 = jpegBytes[pos + 1].toInt() and 0xFF

            if (b0 == 0xFF && b1 == 0xE1 && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    val payloadStart = pos + 4
                    val hasXmpPrefix = (pos + totalSegSize >= payloadStart + XMP_IDENTIFIER.size) &&
                        jpegBytes.copyOfRange(payloadStart, payloadStart + XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
                    if (hasXmpPrefix) {
                        pos += totalSegSize
                        continue
                    }
                }
            }

            if (b0 == 0xFF && b1 == 0xDA) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }

            if (b0 == 0xFF && (b1 == 0xD9 || (b1 in 0xD0..0xD7) || b1 == 0x01)) {
                out.write(b0)
                out.write(b1)
                pos += 2
                continue
            }

            if (b0 == 0xFF && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    out.write(jpegBytes, pos, totalSegSize)
                    pos += totalSegSize
                    continue
                }
            }

            out.write(jpegBytes[pos].toInt() and 0xFF)
            pos++
        }

        return out.toByteArray()
    }

    /**
     * Synthesizes a Samsung Galaxy HEIC Motion Photo file (.heic).
     */
    fun createSamsungHeicMotionPhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ) {
        require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }
        require(videoFile.exists()) { "Video file not found: ${videoFile.absolutePath}" }
        require(videoFile.length() > 0) { "Video file is empty: ${videoFile.absolutePath}" }

        val rawHeicBytes = imageFile.readBytes()
        val (baseHeicBytes, preservedSefBlocks) = extractExistingHeicBoxesAndSef(rawHeicBytes)

        // 1. mpvd box calculation
        val mpvdOffset = baseHeicBytes.size.toLong()
        val mpvdSize = 8L + videoFile.length()
        val videoStartOffset = mpvdOffset + 8L
        val videoLength = videoFile.length()

        // 2. Build SEF Blocks for HEIC
        val allSefBlocks = preservedSefBlocks.toMutableList()

        // Block: MotionPhoto_Version ("mpv3")
        val vNameBytes = "MotionPhoto_Version".toByteArray(Charsets.UTF_8)
        val vPayloadBytes = "mpv3".toByteArray(Charsets.UTF_8)
        val vHeaderBuf = ByteBuffer.allocate(8 + vNameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        vHeaderBuf.putShort(0x0000.toShort())
        vHeaderBuf.putShort(SEF_MARKER_MOTION_PHOTO_VERSION.toShort())
        vHeaderBuf.putInt(vNameBytes.size)
        vHeaderBuf.put(vNameBytes)
        val vBlockBytes = vHeaderBuf.array() + vPayloadBytes
        allSefBlocks.add(PreservedSefBlock("MotionPhoto_Version", SEF_MARKER_MOTION_PHOTO_VERSION, 0x0000, vBlockBytes))

        // Block: MotionPhoto_Data (12-byte pointer payload: "mpv2" + videoStartOffset + videoLength)
        val dNameBytes = "MotionPhoto_Data".toByteArray(Charsets.UTF_8)
        val dPayloadBuf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        dPayloadBuf.put("mpv2".toByteArray(Charsets.US_ASCII))
        dPayloadBuf.putInt(videoStartOffset.toInt())
        dPayloadBuf.putInt(videoLength.toInt())

        val dHeaderBuf = ByteBuffer.allocate(8 + dNameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        dHeaderBuf.putShort(0x0000.toShort())
        dHeaderBuf.putShort(SEF_MARKER_MOTION_PHOTO_DATA.toShort())
        dHeaderBuf.putInt(dNameBytes.size)
        dHeaderBuf.put(dNameBytes)
        val dBlockBytes = dHeaderBuf.array() + dPayloadBuf.array()
        allSefBlocks.add(PreservedSefBlock("MotionPhoto_Data", SEF_MARKER_MOTION_PHOTO_DATA, 0x0000, dBlockBytes))

        // 3. Build SEFH Directory Table
        val totalBlockBytesSize = allSefBlocks.sumOf { it.bytes.size.toLong() }
        val sefDirSize = 12 + allSefBlocks.size * 12
        val seftTailSize = 8
        val sefPayloadSize = totalBlockBytesSize + sefDirSize + seftTailSize

        val sefDirBuf = ByteBuffer.allocate(sefDirSize).order(ByteOrder.LITTLE_ENDIAN)
        sefDirBuf.put("SEFH".toByteArray(Charsets.US_ASCII))
        sefDirBuf.putInt(SEF_VERSION)
        sefDirBuf.putInt(allSefBlocks.size)

        var accumOffset = totalBlockBytesSize
        for (block in allSefBlocks) {
            val blkLen = block.bytes.size.toLong()
            sefDirBuf.putShort(block.typeCode.toShort())
            sefDirBuf.putShort(block.marker.toShort())
            sefDirBuf.putInt(accumOffset.toInt())
            sefDirBuf.putInt(blkLen.toInt())
            accumOffset -= blkLen
        }

        // 4. Build SEFT Tail (8 bytes)
        val seftBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        seftBuf.putInt(sefDirSize)
        seftBuf.put("SEFT".toByteArray(Charsets.US_ASCII))

        // 5. mpvd Header (8 bytes)
        val mpvdHeaderBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        mpvdHeaderBuf.putInt(mpvdSize.toInt())
        mpvdHeaderBuf.put("mpvd".toByteArray(Charsets.US_ASCII))

        // 6. sefd Box Header (8 bytes)
        val sefdBoxSize = 8L + sefPayloadSize
        val sefdHeaderBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        sefdHeaderBuf.putInt(sefdBoxSize.toInt())
        sefdHeaderBuf.put("sefd".toByteArray(Charsets.US_ASCII))

        // 7. Update XMP item in iloc with video offset from EOF: videoLength + sefdBoxSize and duration
        val videoOffsetFromEof = videoLength + sefdBoxSize
        val presentationTimestampUs = extractVideoDurationUs(videoFile)
        val updatedBaseHeicBytes = updateHeicXmpItem(baseHeicBytes, videoOffsetFromEof, presentationTimestampUs, version)

        // 8. Write complete Motion Photo HEIC file
        FileOutputStream(outputFile).use { out ->
            out.write(updatedBaseHeicBytes)
            out.write(mpvdHeaderBuf.array())
            FileInputStream(videoFile).use { videoIn ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (videoIn.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            out.write(sefdHeaderBuf.array())
            for (block in allSefBlocks) {
                out.write(block.bytes)
            }
            out.write(sefDirBuf.array())
            out.write(seftBuf.array())
        }
    }

    /**
     * Synthesizes a Samsung/Google Motion Photo JPEG file (.jpg).
     */
    fun createGoogleMotionPhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ) {
        require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }
        require(videoFile.exists()) { "Video file not found: ${videoFile.absolutePath}" }
        require(videoFile.length() > 0) { "Video file is empty: ${videoFile.absolutePath}" }

        val ext = imageFile.extension.lowercase(Locale.US)
        val rawImageBytes = if (ext == "jpg" || ext == "jpeg") {
            imageFile.readBytes()
        } else {
            convertImageToJpegBytes(imageFile)
        }

        // 1. Extract all existing SEF data blocks from the original photo
        val (baseJpegBytes, preservedSefBlocks) = extractExistingSefBlocks(rawImageBytes)

        // 2. Prepare new MotionPhoto_Data block header (24 bytes)
        val nameBytes = "MotionPhoto_Data".toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size // 16
        val motionBlockHeaderBuf = ByteBuffer.allocate(8 + nameLen).order(ByteOrder.LITTLE_ENDIAN)
        motionBlockHeaderBuf.putShort(0x0000.toShort())
        motionBlockHeaderBuf.putShort(SEF_MARKER_MOTION_PHOTO_DATA.toShort())
        motionBlockHeaderBuf.putInt(nameLen)
        motionBlockHeaderBuf.put(nameBytes)
        val motionBlockHeaderBytes = motionBlockHeaderBuf.array()

        val motionBlockTotalLength = (motionBlockHeaderBytes.size + videoFile.length()).toLong()

        // 3. Calculate all block lengths and total SEF size
        val allBlockLengths = preservedSefBlocks.map { it.bytes.size.toLong() } + listOf(motionBlockTotalLength)
        val totalBlockBytesSize = allBlockLengths.sum()

        val totalEntryCount = preservedSefBlocks.size + 1
        val sefDirSize = 12 + totalEntryCount * 12
        val seftTailSize = 8

        // In Google Photos specification:
        // offsetFromEofToVideo is the exact distance from the END of the file to the FIRST byte of the video (ftyp box)!
        val offsetFromEofToVideo = videoFile.length() + sefDirSize + seftTailSize

        // primaryPadding is the number of bytes between the end of primary JPEG image and the first byte of video
        val preservedBlocksSize = preservedSefBlocks.sumOf { it.bytes.size.toLong() }
        val primaryPadding = preservedBlocksSize + motionBlockHeaderBytes.size

        // Extract video duration for presentation timestamp
        val presentationTimestampUs = extractVideoDurationUs(videoFile)

        // 4. Inject Google Motion Photo XMP into base JPEG
        val motionPhotoJpegBytes = injectMotionPhotoXmpIntoJpeg(
            baseJpegBytes,
            offsetFromEofToVideo,
            primaryPadding,
            presentationTimestampUs,
            version,
        )

        // 5. Build SEFH Directory Table
        val sefDirBuf = ByteBuffer.allocate(sefDirSize).order(ByteOrder.LITTLE_ENDIAN)
        sefDirBuf.put("SEFH".toByteArray(Charsets.US_ASCII))
        sefDirBuf.putInt(SEF_VERSION)
        sefDirBuf.putInt(totalEntryCount)

        var accumOffset = totalBlockBytesSize
        for (block in preservedSefBlocks) {
            val blkLen = block.bytes.size.toLong()
            sefDirBuf.putShort(block.typeCode.toShort())
            sefDirBuf.putShort(block.marker.toShort())
            sefDirBuf.putInt(accumOffset.toInt())
            sefDirBuf.putInt(blkLen.toInt())
            accumOffset -= blkLen
        }

        // Entry for new MotionPhoto_Data block
        sefDirBuf.putShort(0x0000.toShort())
        sefDirBuf.putShort(SEF_MARKER_MOTION_PHOTO_DATA.toShort())
        sefDirBuf.putInt(motionBlockTotalLength.toInt())
        sefDirBuf.putInt(motionBlockTotalLength.toInt())
        val sefDirBytes = sefDirBuf.array()

        // 6. Build SEFT Tail (8 bytes)
        val seftBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        seftBuf.putInt(sefDirSize)
        seftBuf.put("SEFT".toByteArray(Charsets.US_ASCII))
        val seftBytes = seftBuf.array()

        // 7. Write complete synthesized Motion Photo file
        FileOutputStream(outputFile).use { out ->
            out.write(motionPhotoJpegBytes)
            for (block in preservedSefBlocks) {
                out.write(block.bytes)
            }
            out.write(motionBlockHeaderBytes)
            FileInputStream(videoFile).use { videoIn ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (videoIn.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            out.write(sefDirBytes)
            out.write(seftBytes)
        }
    }

    /**
     * Automatically synthesizes Motion Photo according to image format (HEIC or JPEG) and version.
     */
    fun createMotionPhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ) {
        val ext = imageFile.extension.lowercase(Locale.US)
        val outExt = outputFile.extension.lowercase(Locale.US)
        if (ext in setOf("heic", "heif") || outExt in setOf("heic", "heif")) {
            createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile, version)
        } else {
            createGoogleMotionPhoto(imageFile, videoFile, outputFile, version)
        }
    }
}
