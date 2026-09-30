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
     * Resolves and bounds the motion photo presentation timestamp in microseconds (us).
     *
     * Rules:
     * - If input is null or <= 0 (e.g. 0us): Defaults to half the video track duration (duration / 2).
     * - If input > video track duration: Clamped to the full video track duration.
     * - Otherwise: Uses the provided timestamp value directly.
     */
    fun resolvePresentationTimestampUs(inputTimestampUs: Long?, videoDurationUs: Long): Long {
        return when {
            inputTimestampUs == null || inputTimestampUs <= 0L -> (videoDurationUs / 2L).coerceAtLeast(0L)
            inputTimestampUs > videoDurationUs -> videoDurationUs
            else -> inputTimestampUs
        }
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
     * Merges motion-photo attributes into an existing XMP document instead of building a fresh
     * one from scratch -- preserves every other attribute/element the original XMP carried (gain
     * map parameters, camera metadata, anything else), so "모션포토 생성" no longer destroys XMP
     * that was already there.
     *
     * @param existingXmpText The image's existing XMP text, or null if it has none.
     * @param videoOffsetOrLength JPEG: distance in bytes from EOF to the video's first byte.
     *   HEIC: same distance-from-EOF convention as buildGoogleMotionPhotoHeicXmp.
     * @param precedingItemPaddingBytes The byte gap between the end of whichever Directory item
     *   ends up immediately before the new MotionPhoto item, and the start of the video bytes --
     *   same role as buildGoogleMotionPhotoXmp's `primaryPadding`, generalized: when an existing
     *   Directory already has other items (e.g. GainMap), this padding is applied to the item that
     *   was previously last, not necessarily to Primary.
     * @param primaryMimeType Only used when synthesizing a fresh Container:Directory (no existing
     *   one to append to) -- "image/jpeg" or "image/heic".
     */
    internal fun mergeMotionPhotoXmp(
        existingXmpText: String?,
        videoOffsetOrLength: Long,
        precedingItemPaddingBytes: Long,
        presentationTimestampUs: Long,
        version: MotionPhotoFormatVersion,
        primaryMimeType: String,
    ): String {
        val freshBuild = if (primaryMimeType == "image/heic") {
            val hasGainMap = existingXmpText?.contains("GainMap") == true
            buildGoogleMotionPhotoHeicXmp(videoOffsetOrLength, hasGainMap, presentationTimestampUs, version)
        } else {
            buildGoogleMotionPhotoXmp(videoOffsetOrLength, precedingItemPaddingBytes, presentationTimestampUs, version)
        }
        if (existingXmpText == null) return freshBuild

        return try {
            val document = parseXmpDocument(existingXmpText)
            val rdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            val descriptions = document.getElementsByTagNameNS(rdfNs, "Description")
            val description = descriptions.item(0) as? org.w3c.dom.Element ?: return freshBuild

            val xmlnsNs = "http://www.w3.org/2000/xmlns/"
            val gCameraNs = "http://ns.google.com/photos/1.0/camera/"
            val containerNs = "http://ns.google.com/photos/1.0/container/"
            val itemNs = "http://ns.google.com/photos/1.0/container/item/"

            fun ensureNamespace(prefix: String, uri: String) {
                if (description.getAttributeNS(xmlnsNs, prefix).isEmpty()) {
                    description.setAttributeNS(xmlnsNs, "xmlns:$prefix", uri)
                }
            }
            ensureNamespace("GCamera", gCameraNs)

            if (version == MotionPhotoFormatVersion.V1_MICRO_VIDEO) {
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideo", "1")
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoVersion", "1")
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoOffset", videoOffsetOrLength.toString())
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoPresentationTimestampUs", presentationTimestampUs.toString())
                return serializeXmpDocument(document)
            }

            description.setAttributeNS(gCameraNs, "GCamera:MotionPhoto", "1")
            description.setAttributeNS(gCameraNs, "GCamera:MotionPhotoVersion", "1")
            description.setAttributeNS(gCameraNs, "GCamera:MotionPhotoPresentationTimestampUs", presentationTimestampUs.toString())
            ensureNamespace("Container", containerNs)
            ensureNamespace("Item", itemNs)

            val existingDirectory = run {
                val children = description.childNodes
                (0 until children.length)
                    .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                    .find { it.namespaceURI == containerNs && it.localName == "Directory" }
            }

            fun newMotionPhotoLi(): org.w3c.dom.Element {
                val li = document.createElementNS(rdfNs, "rdf:li")
                li.setAttributeNS(rdfNs, "rdf:parseType", "Resource")
                val item = document.createElementNS(containerNs, "Container:Item")
                item.setAttributeNS(itemNs, "Item:Mime", "video/mp4")
                item.setAttributeNS(itemNs, "Item:Semantic", "MotionPhoto")
                item.setAttributeNS(itemNs, "Item:Length", videoOffsetOrLength.toString())
                item.setAttributeNS(itemNs, "Item:Padding", "0")
                li.appendChild(item)
                return li
            }

            if (existingDirectory != null) {
                val seq = run {
                    val children = existingDirectory.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .find { it.namespaceURI == rdfNs && it.localName == "Seq" }
                } ?: return freshBuild

                val liElements = run {
                    val children = seq.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .filter { it.namespaceURI == rdfNs && it.localName == "li" }
                }
                val lastLi = liElements.lastOrNull()
                val lastItem = lastLi?.let { li ->
                    val children = li.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .find { it.namespaceURI == containerNs && it.localName == "Item" }
                }
                lastItem?.setAttributeNS(itemNs, "Item:Padding", precedingItemPaddingBytes.toString())

                seq.appendChild(newMotionPhotoLi())
            } else {
                val directory = document.createElementNS(containerNs, "Container:Directory")
                val seq = document.createElementNS(rdfNs, "rdf:Seq")

                val primaryLi = document.createElementNS(rdfNs, "rdf:li")
                primaryLi.setAttributeNS(rdfNs, "rdf:parseType", "Resource")
                val primaryItem = document.createElementNS(containerNs, "Container:Item")
                primaryItem.setAttributeNS(itemNs, "Item:Semantic", "Primary")
                primaryItem.setAttributeNS(itemNs, "Item:Mime", primaryMimeType)
                primaryItem.setAttributeNS(itemNs, "Item:Padding", precedingItemPaddingBytes.toString())
                primaryLi.appendChild(primaryItem)

                seq.appendChild(primaryLi)
                seq.appendChild(newMotionPhotoLi())
                directory.appendChild(seq)
                description.appendChild(directory)
            }

            serializeXmpDocument(document)
        } catch (e: Exception) {
            freshBuild
        }
    }

    private fun serializeXmpDocument(document: org.w3c.dom.Document): String {
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = java.io.StringWriter()
        transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer))
        return writer.toString()
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

                                // Known gap: unlike repointHeicXmpItem's own field-position walk, this loop never
                                // skips an extent_index field even when index_size > 0 (ISO/IEC 14496-12 puts
                                // extent_index before offset/length whenever index_size > 0, for version 1/2) --
                                // only matters for files mixing construction_method=2 items (e.g. tiled/grid
                                // images) with other registered items, which this app hasn't needed to parse yet.
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
     * Given an XMP item's already-resolved data extent (from findXmpExtentInHeic), re-walks meta ->
     * iloc to find that same item's item_ID, the byte offset of its fixed-width entry within iloc,
     * and its extent_count -- the inputs repointHeicXmpItem needs. Returns null if the structured
     * walk fails (mirrors findXmpExtentInHeic's own fallback-to-pattern-scan case, where there's no
     * structured iloc entry to repoint at all -- callers should fall back to createHeicXmpItem).
     */
    internal fun findHeicXmpIlocEntry(heicBytes: ByteArray, resolvedXmpOffset: Int, resolvedXmpLength: Int): Triple<Long, Long, Int>? {
        try {
            var pos = 0
            while (pos < heicBytes.size - 8) {
                val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                    ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
                val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
                val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
                if (pos + boxLen > heicBytes.size || boxLen < 8) break

                if (fourCC == "meta") {
                    val metaEnd = (pos + boxLen).toInt()
                    var mp = pos + 12
                    while (mp < metaEnd - 8) {
                        val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                            ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
                        val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
                        if (childSz < 8 || mp + childSz > metaEnd) break

                        if (childFourCC == "iloc") {
                            val ilocVersion = heicBytes[mp + 8].toInt() and 0xFF
                            val offLenSz = heicBytes[mp + 12].toInt() and 0xFF
                            val baseIdxSz = heicBytes[mp + 13].toInt() and 0xFF
                            val offSz = offLenSz shr 4
                            val lenSz = offLenSz and 0x0F
                            val baseOffSz = baseIdxSz shr 4
                            val idxSz = baseIdxSz and 0x0F

                            var lp = mp + (if (ilocVersion < 2) 16 else 18)
                            val itemCount = if (ilocVersion < 2) {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 8) or (heicBytes[mp + 15].toInt() and 0xFF)
                            } else {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 15].toInt() and 0xFF) shl 16) or
                                    ((heicBytes[mp + 16].toInt() and 0xFF) shl 8) or (heicBytes[mp + 17].toInt() and 0xFF)
                            }
                            val itemIdWidth = if (ilocVersion < 2) 2 else 4
                            val constructionMethodWidth = if (ilocVersion in 1..2) 2 else 0

                            for (i in 0 until itemCount) {
                                val entryStart = lp
                                val itemId = if (itemIdWidth == 2) {
                                    ((heicBytes[lp].toLong() and 0xFF) shl 8) or (heicBytes[lp + 1].toLong() and 0xFF)
                                } else {
                                    ((heicBytes[lp].toLong() and 0xFF) shl 24) or ((heicBytes[lp + 1].toLong() and 0xFF) shl 16) or
                                        ((heicBytes[lp + 2].toLong() and 0xFF) shl 8) or (heicBytes[lp + 3].toLong() and 0xFF)
                                }
                                lp += itemIdWidth
                                lp += constructionMethodWidth
                                lp += 2 // data_reference_index
                                var baseOffset = 0L
                                for (b in 0 until baseOffSz) { baseOffset = (baseOffset shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                val extentCount = ((heicBytes[lp].toInt() and 0xFF) shl 8) or (heicBytes[lp + 1].toInt() and 0xFF)
                                lp += 2
                                for (e in 0 until extentCount) {
                                    if (idxSz > 0) lp += idxSz
                                    var extentOffset = 0L
                                    for (b in 0 until offSz) { extentOffset = (extentOffset shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                    var extentLength = 0L
                                    for (b in 0 until lenSz) { extentLength = (extentLength shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                    val absOffset = (baseOffset + extentOffset).toInt()
                                    if (absOffset == resolvedXmpOffset && extentLength.toInt() == resolvedXmpLength) {
                                        return Triple(itemId, entryStart.toLong(), extentCount)
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
            return null
        }
        return null
    }

    /**
     * A HEIC XMP item located STRUCTURALLY -- i.e. by its `iinf`/`infe` registration
     * (item_type=="mime", content_type=="application/rdf+xml", exactly how InfeBoxDecoder identifies
     * one) and then by item_ID match against `iloc` -- with no content sniffing anywhere.
     *
     * @param resolvedOffset / [resolvedLength] The item's FIRST extent resolved as base_offset +
     *   extent_offset. Only meaningful as the item's whole content when [constructionMethod] is 0 and
     *   [extentCount] is 1; see readExistingXmpItemText.
     */
    internal data class HeicXmpItemLocation(
        val itemId: Long,
        val ilocEntryOffset: Long,
        val extentCount: Int,
        val constructionMethod: Int,
        val resolvedOffset: Int,
        val resolvedLength: Int,
    )

    /**
     * Answers "does this HEIC already have an XMP item, and where is its iloc entry?" deterministically,
     * from the file's own structure rather than by sniffing bytes for XMP-looking text.
     *
     * This replaces the findXmpExtentInHeic + findHeicXmpIlocEntry pairing for that specific question.
     * That pairing only recognizes an XMP item when "Container" or "rdf:Description" appears within the
     * FIRST 100 bytes of the extent, which real Adobe-style packets (a `<?xpacket begin ...?>` prefix
     * ahead of `<x:xmpmeta`, namespace declarations after it) routinely fail -- `rdf:Description` often
     * lands past byte 200. Mistaking "I couldn't sniff it" for "there is no XMP item" is what made the
     * caller register a SECOND XMP item beside the original, and since readers (this app's own re-parse
     * included) return the first match, the stale original would win.
     */
    internal fun findHeicXmpItemLocation(heicBytes: ByteArray): HeicXmpItemLocation? {
        return try {
            val meta = findMetaBoxBounds(heicBytes) ?: return null
            val iinf = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iinf") ?: return null
            val iloc = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iloc") ?: return null
            val xmpItemId = findXmpItemIdInIinf(heicBytes, iinf) ?: return null
            findIlocEntryForItemId(heicBytes, iloc, xmpItemId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Walks iinf's infe children and returns the item_ID of the one registering an XMP payload --
     * item_type "mime" with content_type "application/rdf+xml" (the same two fields InfeBoxDecoder
     * reads, at the same offsets). infe versions 0 and 1 carry no item_type/content_type at all, so
     * an item registered that way simply can't be identified here and is skipped.
     */
    private fun findXmpItemIdInIinf(heicBytes: ByteArray, iinf: BoxBounds): Long? {
        val iinfVersion = heicBytes[iinf.payloadStart].toInt() and 0xFF
        val entryCountWidth = if (iinfVersion == 0) 2 else 4
        var mp = iinf.payloadStart + 4 + entryCountWidth
        while (mp + 8 <= iinf.end) {
            val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
            if (childSz < 8 || mp + childSz > iinf.end) break
            val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
            val entryEnd = mp + childSz
            if (childFourCC == "infe" && mp + 12 <= entryEnd) {
                val infeVersion = heicBytes[mp + 8].toInt() and 0xFF
                if (infeVersion >= 2) {
                    val itemIdWidth = if (infeVersion >= 3) 4 else 2
                    val itemIdOffset = mp + 12
                    val itemTypeOffset = itemIdOffset + itemIdWidth + 2 // + item_protection_index
                    if (itemTypeOffset + 4 <= entryEnd) {
                        val itemType = String(heicBytes.copyOfRange(itemTypeOffset, itemTypeOffset + 4), Charsets.US_ASCII)
                        if (itemType == "mime") {
                            // item_name is a NUL-terminated string, then content_type is another one.
                            var p = itemTypeOffset + 4
                            while (p < entryEnd && heicBytes[p] != 0.toByte()) p++
                            p++ // skip item_name's NUL
                            val contentTypeStart = p
                            while (p < entryEnd && heicBytes[p] != 0.toByte()) p++
                            if (contentTypeStart <= entryEnd && p <= entryEnd) {
                                val contentType = String(heicBytes.copyOfRange(contentTypeStart, p), Charsets.US_ASCII)
                                if (contentType == "application/rdf+xml") {
                                    return readUIntOfWidth(heicBytes, itemIdOffset.toLong(), itemIdWidth)
                                }
                            }
                        }
                    }
                }
            }
            mp = entryEnd
        }
        return null
    }

    /**
     * Finds the iloc item entry whose item_ID equals [targetItemId] -- by ID, never by content -- and
     * reports the byte offset of that entry (what repointHeicXmpItem rewrites), its extent_count,
     * construction_method, and its first extent resolved to an absolute offset/length. Every field
     * read is bounds checked against iloc's own box end.
     */
    private fun findIlocEntryForItemId(heicBytes: ByteArray, iloc: BoxBounds, targetItemId: Long): HeicXmpItemLocation? {
        val payloadStart = iloc.payloadStart
        if (payloadStart + 8 > iloc.end) return null
        val version = heicBytes[payloadStart].toInt() and 0xFF
        val offLenSz = heicBytes[payloadStart + 4].toInt() and 0xFF
        val baseIdxSz = heicBytes[payloadStart + 5].toInt() and 0xFF
        val offSz = offLenSz shr 4
        val lenSz = offLenSz and 0x0F
        val baseOffSz = baseIdxSz shr 4
        val idxSz = baseIdxSz and 0x0F
        val itemCountWidth = if (version < 2) 2 else 4
        if (payloadStart + 6 + itemCountWidth > iloc.end) return null
        val itemCount = readUIntOfWidth(heicBytes, (payloadStart + 6).toLong(), itemCountWidth).toInt()
        val itemIdWidth = if (version < 2) 2 else 4
        val constructionMethodWidth = if (version in 1..2) 2 else 0

        var lp = payloadStart + 6 + itemCountWidth
        for (i in 0 until itemCount) {
            val entryStart = lp
            if (lp + itemIdWidth + constructionMethodWidth + 2 + baseOffSz + 2 > iloc.end) return null
            val itemId = readUIntOfWidth(heicBytes, lp.toLong(), itemIdWidth)
            lp += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) {
                readUIntOfWidth(heicBytes, lp.toLong(), 2).toInt() and 0xF
            } else {
                0
            }
            lp += constructionMethodWidth
            lp += 2 // data_reference_index
            val baseOffset = readUIntOfWidth(heicBytes, lp.toLong(), baseOffSz)
            lp += baseOffSz
            val extentCount = readUIntOfWidth(heicBytes, lp.toLong(), 2).toInt()
            lp += 2
            var firstExtentOffset = 0L
            var firstExtentLength = 0L
            for (e in 0 until extentCount) {
                if (lp + idxSz + offSz + lenSz > iloc.end) return null
                lp += idxSz
                val extentOffset = readUIntOfWidth(heicBytes, lp.toLong(), offSz)
                lp += offSz
                val extentLength = readUIntOfWidth(heicBytes, lp.toLong(), lenSz)
                lp += lenSz
                if (e == 0) {
                    firstExtentOffset = extentOffset
                    firstExtentLength = extentLength
                }
            }
            if (itemId == targetItemId) {
                return HeicXmpItemLocation(
                    itemId = itemId,
                    ilocEntryOffset = entryStart.toLong(),
                    extentCount = extentCount,
                    constructionMethod = constructionMethod,
                    resolvedOffset = (baseOffset + firstExtentOffset).toInt(),
                    resolvedLength = firstExtentLength.toInt(),
                )
            }
        }
        return null
    }

    /**
     * Reads an existing XMP item's current text so mergeMotionPhotoXmp can preserve it, or null when
     * the item's bytes can't be recovered here: construction_method=1 is idat-relative (resolving it
     * needs real idat-box support, out of scope) and a multi-extent item would need reassembly. In
     * both cases the merge degrades to a fresh build -- the item is still repointed correctly, only
     * the pre-existing attributes are lost -- which is strictly better than the alternative of
     * registering a competing duplicate item.
     */
    private fun readExistingXmpItemText(heicBytes: ByteArray, loc: HeicXmpItemLocation): String? {
        if (loc.constructionMethod != 0 || loc.extentCount != 1) return null
        if (loc.resolvedOffset < 0 || loc.resolvedLength <= 0) return null
        if (loc.resolvedOffset + loc.resolvedLength > heicBytes.size) return null
        return String(heicBytes, loc.resolvedOffset, loc.resolvedLength, Charsets.UTF_8)
            .trimEnd(' ', Char(0), '\n', '\r')
    }

    /**
     * Repoints an existing HEIC XMP item's iloc extent to freshly-appended bytes, instead of
     * overwriting its original byte range in place (which only works when the new content happens
     * to fit in the original allocation -- practically never true once the XMP is being merged
     * rather than replaced). Per ISO/IEC 14496-12 iloc semantics, an item's data extent can point
     * at any absolute offset in the file; there's no requirement it stay where it started. Rewrites
     * only this one item's fixed-width iloc fields (construction_method, base_offset, extent_offset,
     * extent_length) -- same byte count in, same byte count out, so no other box or offset in the
     * file needs to change.
     *
     * @param xmpItemId The XMP item's item_ID (needed only for documentation/assertions -- not used
     *   to re-locate anything, since ilocEntryOffset already pins the exact bytes to rewrite).
     * @param ilocEntryOffset Absolute file offset of this item's fixed-width entry within the iloc
     *   box (construction_method through the start of its extent list).
     * @param existingExtentCount This item's current extent_count -- repoint only handles the
     *   single-extent case (every real-world encoder, and this app's own writer, produces exactly
     *   one extent per XMP item); returns null for anything else so the caller can fall back.
     * @return The rewritten HEIC bytes, or null if this item can't be cheaply repointed (multi-extent).
     */
    internal fun repointHeicXmpItem(
        heicBytes: ByteArray,
        xmpItemId: Long,
        ilocEntryOffset: Long,
        existingExtentCount: Int,
        mergedXmpBytes: ByteArray,
    ): ByteArray? {
        if (existingExtentCount != 1) return null

        val ilocHeader = findIlocHeaderFields(heicBytes) ?: return null
        val (ilocVersion, offSz, lenSz, baseOffSz, indexSz) = ilocHeader

        var fieldPos = ilocEntryOffset.toInt()
        fieldPos += if (ilocVersion < 2) 2 else 4 // skip item_ID
        val constructionMethodFieldPos = if (ilocVersion in 1..2) fieldPos else -1
        if (constructionMethodFieldPos >= 0) fieldPos += 2
        fieldPos += 2 // skip data_reference_index
        val baseOffsetFieldPos = fieldPos
        fieldPos += baseOffSz
        fieldPos += 2 // skip extent_count (unchanged, still 1)
        if (indexSz > 0) fieldPos += indexSz
        val extentOffsetFieldPos = fieldPos
        fieldPos += offSz
        val extentLengthFieldPos = fieldPos

        // New mdat: appended right where the file currently ends.
        val newMdatOffset = heicBytes.size.toLong()
        val newMdatPayloadOffset = newMdatOffset + 8
        val newMdatSize = 8L + mergedXmpBytes.size

        val result = heicBytes.copyOf(heicBytes.size + newMdatSize.toInt())
        val mdatBuf = ByteBuffer.wrap(result, newMdatOffset.toInt(), newMdatSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        mdatBuf.putInt(newMdatSize.toInt())
        mdatBuf.put("mdat".toByteArray(Charsets.US_ASCII))
        mdatBuf.put(mergedXmpBytes)

        // Capture the OLD field values before overwriting them below -- needed for the cleanup pass
        // further down, and reading them directly from these exact, already-known, ID-scoped
        // positions is deterministic (no re-resolution/content-sniffing needed), unlike a generic
        // byte-scan that isn't tied to this specific item.
        val oldConstructionMethod = if (constructionMethodFieldPos >= 0) readUIntOfWidth(heicBytes, constructionMethodFieldPos.toLong(), 2).toInt() else 0
        val oldBaseOffset = readUIntOfWidth(heicBytes, baseOffsetFieldPos.toLong(), baseOffSz)
        val oldExtentOffset = readUIntOfWidth(heicBytes, extentOffsetFieldPos.toLong(), offSz)
        val oldExtentLength = readUIntOfWidth(heicBytes, extentLengthFieldPos.toLong(), lenSz)

        if (constructionMethodFieldPos >= 0) {
            writeUIntOfWidth(result, constructionMethodFieldPos.toLong(), 2, 0L)
        }
        writeUIntOfWidth(result, baseOffsetFieldPos.toLong(), baseOffSz, 0L)
        writeUIntOfWidth(result, extentOffsetFieldPos.toLong(), offSz, newMdatPayloadOffset)
        writeUIntOfWidth(result, extentLengthFieldPos.toLong(), lenSz, mergedXmpBytes.size.toLong())

        // Best-effort: clear the item's OLD extent bytes now that iloc no longer references them, so
        // the pre-merge XMP doesn't linger as an unreferenced duplicate elsewhere in the file -- a
        // later re-parse (e.g. findXmpExtentInHeic's own content-sniff, which real fresh-built XMP
        // can miss on its structured iloc walk since "rdf:Description"/"Container" can land past its
        // 100-byte sample cap, falling through to a whole-file pattern scan) could otherwise mistake
        // the stale duplicate for the current XMP. Only handled for the common construction_method=0
        // (absolute offset) case, where the old location is known exactly and unambiguously from the
        // very field values this function already reads/overwrites above -- no content-sniffing or
        // re-resolution involved. construction_method=1 (idat-relative) old items are left untouched:
        // resolving their true byte position needs a real idat box lookup, out of scope here (this
        // function only ever repoints TO an absolute-offset location, never reads FROM idat) --
        // skipping cleanup for that case changes nothing structurally, matching today's behavior.
        if (oldConstructionMethod == 0) {
            val oldStart = (oldBaseOffset + oldExtentOffset).toInt()
            val oldEnd = oldStart + oldExtentLength.toInt()
            if (oldStart in 0..heicBytes.size && oldEnd in oldStart..heicBytes.size) {
                for (i in oldStart until oldEnd) {
                    result[i] = 0
                }
            }
        }

        return result
    }

    private data class IlocHeaderFields(val version: Int, val offsetSize: Int, val lengthSize: Int, val baseOffsetSize: Int, val indexSize: Int)

    /** Locates the meta -> iloc box and reads its FullBox version and offset/length/base_offset/index field widths. */
    private fun findIlocHeaderFields(heicBytes: ByteArray): IlocHeaderFields? {
        var pos = 0
        while (pos < heicBytes.size - 8) {
            val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
            val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
            val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
            if (pos + boxLen > heicBytes.size || boxLen < 8) return null

            if (fourCC == "meta") {
                val metaEnd = (pos + boxLen).toInt()
                var mp = pos + 12
                while (mp < metaEnd - 8) {
                    val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                        ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
                    val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
                    if (childSz < 8 || mp + childSz > metaEnd) return null
                    if (childFourCC == "iloc") {
                        val version = heicBytes[mp + 8].toInt() and 0xFF
                        val offLenSz = heicBytes[mp + 12].toInt() and 0xFF
                        val baseIdxSz = heicBytes[mp + 13].toInt() and 0xFF
                        return IlocHeaderFields(version, offLenSz shr 4, offLenSz and 0x0F, baseIdxSz shr 4, baseIdxSz and 0x0F)
                    }
                    mp += childSz
                }
            }
            pos += boxLen.toInt()
        }
        return null
    }

    /** Writes an unsigned big-endian integer of the given byte width at the given position. widthBytes of 0 is a no-op. */
    private fun writeUIntOfWidth(bytes: ByteArray, offset: Long, widthBytes: Int, value: Long) {
        for (i in 0 until widthBytes) {
            val shift = (widthBytes - 1 - i) * 8
            bytes[(offset + i).toInt()] = ((value shr shift) and 0xFF).toByte()
        }
    }

    /** Reads an unsigned big-endian integer of the given byte width at the given position. widthBytes of 0 reads as 0. */
    private fun readUIntOfWidth(bytes: ByteArray, offset: Long, widthBytes: Int): Long {
        var value = 0L
        for (i in 0 until widthBytes) {
            value = (value shl 8) or (bytes[(offset + i).toInt()].toLong() and 0xFF)
        }
        return value
    }

    /**
     * Registers a brand-new XMP item in a HEIC file that has none, by appending one infe entry to
     * iinf and one item entry to iloc -- always at the END of each list, never inserted in the
     * middle, so every other existing entry's own bytes never move. Because meta (which contains
     * iinf/iloc) grows, every pre-existing construction_method=0 iloc extent whose absolute offset
     * sits past the old end of meta must have that offset increased by the total growth -- otherwise
     * those items (most importantly the primary image's own pixel data) would point at the wrong
     * bytes. construction_method=1 (idat-relative) entries need no adjustment.
     */
    internal fun createHeicXmpItem(heicBytes: ByteArray, xmpBytes: ByteArray): ByteArray {
        val meta = findMetaBoxBounds(heicBytes) ?: return heicBytes
        val iinf = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iinf") ?: return heicBytes
        val iloc = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iloc") ?: return heicBytes
        val ilocHeader = findIlocHeaderFields(heicBytes) ?: return heicBytes

        // These three boxes are grown below via patchBoxSize, which increments the ordinary 4-byte
        // size field -- never valid for the 64-bit-size (size==1) or extends-to-EOF (size==0) forms.
        // findMetaBoxBounds/findChildBoxBounds already reject those, but re-assert it here so this
        // function's byte safety doesn't rest on a distant helper's internal detail.
        if (readUIntOfWidth(heicBytes, meta.start.toLong(), 4) < 8L) return heicBytes
        if (readUIntOfWidth(heicBytes, iinf.start.toLong(), 4) < 8L) return heicBytes
        if (readUIntOfWidth(heicBytes, iloc.start.toLong(), 4) < 8L) return heicBytes

        // iinf and iloc may appear in EITHER order inside meta -- ISOBMFF imposes no ordering and
        // real encoders do emit iloc first. Each new entry is always appended at the end of its OWN
        // box, so in byte order the two insertion points are (firstBox.end, secondBox.end); assuming
        // iinf-before-iloc would make the "copy the region between them" step a negative-length copy
        // (an uncaught IndexOutOfBoundsException all the way up through createMotionPhoto).
        val iinfFirst = iinf.start < iloc.start
        val firstBox = if (iinfFirst) iinf else iloc
        val secondBox = if (iinfFirst) iloc else iinf
        // They must be disjoint siblings; overlapping/nested shapes aren't safe to operate on.
        if (firstBox.end > secondBox.start) return heicBytes

        val existingItemIds = collectInfeItemIds(heicBytes, iinf)
        // iloc carries its OWN item_count -- a different field in a different box from iinf's infe
        // count. They're normally equal, but nothing structurally guarantees it (an item can have an
        // infe with no iloc entry, or the reverse, in a malformed file), and the offset-shift
        // correction pass at the end of this function is bounded specifically by the PRE-growth ILOC
        // item count. Read it from iloc directly rather than substituting the infe-derived count: an
        // undercount would stop the shift walk early and leave the primary image's own extent
        // pointing into the middle of the grown meta box (corrupting the visible image), while an
        // overcount would walk into the entry just appended and double-shift its already-correct
        // offset. If the two counts disagree the premise doesn't hold at all, so bail out unchanged.
        val ilocItemCountWidth = if (ilocHeader.version < 2) 2 else 4
        if (iloc.payloadStart + 6 + ilocItemCountWidth > iloc.end) return heicBytes
        val ilocItemCount = readUIntOfWidth(heicBytes, (iloc.payloadStart + 6).toLong(), ilocItemCountWidth).toInt()
        if (ilocItemCount != existingItemIds.size) return heicBytes

        val newItemId = (existingItemIds.maxOrNull() ?: 0L) + 1
        // ISO/IEC 14496-12: ItemInfoEntry's item_ID is 16-bit for versions 0, 1 AND 2; only version 3
        // widens it to 32-bit. (iloc's item_ID width is tied to ILOC's own version, independently.)
        val infeVersion = if (newItemId > 0xFFFF) 3 else 2
        val itemIdWidth = if (infeVersion >= 3) 4 else 2
        // The same ID has to be written into iloc too, in whatever width ILOC's version dictates. If
        // it doesn't fit there it would be silently truncated -- and a truncated ID can collide with
        // a real existing item, which is worse than not attaching the XMP at all.
        val ilocItemIdWidth = if (ilocHeader.version < 2) 2 else 4
        if (ilocItemIdWidth == 2 && newItemId > 0xFFFF) return heicBytes
        if (newItemId > 0xFFFFFFFFL) return heicBytes

        // Build the new infe box: FullBox header(4) + item_ID(itemIdWidth) + item_protection_index(2)
        // + item_type(4) + item_name(NUL) + content_type(NUL) -- item_type="mime", no item_name,
        // content_type="application/rdf+xml".
        val contentType = "application/rdf+xml".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val infePayloadSize = 4 + itemIdWidth + 2 + 4 + 1 /* empty item_name NUL */ + contentType.size
        val infeBoxSize = 8 + infePayloadSize
        val infeBuf = ByteBuffer.allocate(infeBoxSize).order(ByteOrder.BIG_ENDIAN)
        infeBuf.putInt(infeBoxSize)
        infeBuf.put("infe".toByteArray(Charsets.US_ASCII))
        infeBuf.put(infeVersion.toByte())
        infeBuf.put(byteArrayOf(0, 0, 0)) // flags
        if (itemIdWidth == 2) infeBuf.putShort(newItemId.toShort()) else infeBuf.putInt(newItemId.toInt())
        infeBuf.putShort(0) // item_protection_index
        infeBuf.put("mime".toByteArray(Charsets.US_ASCII))
        infeBuf.put(0) // empty item_name, NUL-terminated
        infeBuf.put(contentType)
        val infeBytes = infeBuf.array()

        // iloc entry size: item_ID + [construction_method if v1/2] + data_reference_index(2)
        // + base_offset(baseOffsetSize) + extent_count(2) + 1 extent * (indexSize + offsetSize + lengthSize)
        val constructionMethodW = if (ilocHeader.version in 1..2) 2 else 0
        val ilocEntrySize = ilocItemIdWidth + constructionMethodW + 2 + ilocHeader.baseOffsetSize + 2 +
            (ilocHeader.indexSize + ilocHeader.offsetSize + ilocHeader.lengthSize)

        // New mdat for the XMP bytes -- placed after the (about to be rewritten) base bytes, before
        // mpvd/sefd, mirroring repointHeicXmpItem's convention.
        val newMdatOffset = (heicBytes.size + infeBytes.size + ilocEntrySize).toLong()
        val newMdatPayloadOffset = newMdatOffset + 8
        val newMdatSize = 8L + xmpBytes.size

        // Build the new iloc item entry with those exact same field widths.
        val ilocEntryBuf = ByteBuffer.allocate(ilocEntrySize).order(ByteOrder.BIG_ENDIAN)
        if (ilocItemIdWidth == 2) ilocEntryBuf.putShort(newItemId.toShort()) else ilocEntryBuf.putInt(newItemId.toInt())
        if (constructionMethodW == 2) ilocEntryBuf.putShort(0) // construction_method = 0 (absolute offset)
        ilocEntryBuf.putShort(0) // data_reference_index
        putUIntOfWidth(ilocEntryBuf, ilocHeader.baseOffsetSize, 0L) // base_offset
        ilocEntryBuf.putShort(1) // extent_count = 1
        if (ilocHeader.indexSize > 0) putUIntOfWidth(ilocEntryBuf, ilocHeader.indexSize, 0L)
        putUIntOfWidth(ilocEntryBuf, ilocHeader.offsetSize, newMdatPayloadOffset)
        putUIntOfWidth(ilocEntryBuf, ilocHeader.lengthSize, xmpBytes.size.toLong())
        val ilocEntryBytes = ilocEntryBuf.array()

        // Assemble: everything up to the first of the two boxes' end, then that box's new entry, then
        // everything from there to the second box's end, then the second box's new entry, then
        // everything after (still within the original file) -- with box-size/count fields patched,
        // and pre-existing absolute offsets past the old meta end shifted by the total growth.
        val firstInsert = if (iinfFirst) infeBytes else ilocEntryBytes
        val secondInsert = if (iinfFirst) ilocEntryBytes else infeBytes
        val growth = (infeBytes.size + ilocEntryBytes.size).toLong()
        val result = ByteArray(heicBytes.size + growth.toInt() + newMdatSize.toInt())
        var w = 0
        fun copyRange(from: Int, to: Int) {
            System.arraycopy(heicBytes, from, result, w, to - from)
            w += (to - from)
        }
        copyRange(0, firstBox.end)
        System.arraycopy(firstInsert, 0, result, w, firstInsert.size); w += firstInsert.size
        copyRange(firstBox.end, secondBox.end)
        System.arraycopy(secondInsert, 0, result, w, secondInsert.size); w += secondInsert.size
        copyRange(secondBox.end, heicBytes.size)
        val mdatBuf = ByteBuffer.wrap(result, w, newMdatSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        mdatBuf.putInt(newMdatSize.toInt())
        mdatBuf.put("mdat".toByteArray(Charsets.US_ASCII))
        mdatBuf.put(xmpBytes)
        w += newMdatSize.toInt()

        // Box starts in `result`: the box that comes FIRST doesn't move (nothing was inserted before
        // it); the one that comes SECOND shifts forward by exactly the first one's insertion, which
        // landed at firstBox.end <= secondBox.start. meta.start precedes both, so it never moves.
        val iinfStartAfter = if (iinfFirst) iinf.start else iinf.start + ilocEntryBytes.size
        val ilocStartAfter = if (iinfFirst) iloc.start + infeBytes.size else iloc.start

        // Patch box sizes: meta grew by `growth`; iinf only by infeBytes.size and iloc only by
        // ilocEntryBytes.size (neither new entry touches the other box's own size).
        patchBoxSize(result, iinfStartAfter, infeBytes.size.toLong())
        patchBoxSize(result, ilocStartAfter, ilocEntryBytes.size.toLong())
        patchBoxSize(result, meta.start, growth)
        patchInfeItemCount(result, iinfStartAfter)
        patchIlocItemCount(result, ilocStartAfter)

        // Shift every pre-existing construction_method=0 extent whose absolute offset was past the
        // OLD end of meta, by `growth`. `ilocItemCount` is iloc's OWN item count from BEFORE the new
        // entry was appended above, so the newly-appended entry (already carrying its correct final
        // offset) is never visited. `ilocEndAfter` bounds the walk at the grown iloc box's real end
        // so it can never spill into whatever box follows, whatever the count says.
        val ilocEndAfter = ilocStartAfter + (iloc.end - iloc.start) + ilocEntryBytes.size
        shiftAbsoluteIlocOffsetsPastMeta(result, ilocStartAfter, ilocEndAfter, meta.end.toLong(), growth, ilocItemCount)

        return result
    }

    private data class BoxBounds(val start: Int, val payloadStart: Int, val end: Int)

    private fun findMetaBoxBounds(heicBytes: ByteArray): BoxBounds? {
        var pos = 0
        while (pos < heicBytes.size - 8) {
            val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
            val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
            val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
            if (pos + boxLen > heicBytes.size || boxLen < 8) return null
            if (fourCC == "meta") {
                // The returned payloadStart (pos + 12 = 4-byte size + 4-byte type + 4-byte FullBox
                // version/flags) is only correct for the ordinary 32-bit-size box header, and
                // patchBoxSize -- which every caller of these bounds relies on to grow the box --
                // increments that same 4-byte size field. The ISOBMFF 64-bit-size form (size==1, real
                // length in the next 8 bytes, payload 8 bytes further along) and the
                // extends-to-EOF form (size==0, the 4-byte field holding no real length at all) both
                // break those two assumptions. Any real-world `meta` is far too small to need either,
                // so treat them as explicitly unsupported instead of silently mis-parsing/corrupting.
                if (size == 0L || size == 1L) return null
                return BoxBounds(pos, pos + 12, (pos + boxLen).toInt())
            }
            pos += boxLen.toInt()
        }
        return null
    }

    private fun findChildBoxBounds(heicBytes: ByteArray, parentPayloadStart: Int, parentEnd: Int, fourCCTarget: String): BoxBounds? {
        var mp = parentPayloadStart
        while (mp < parentEnd - 8) {
            val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
            val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
            // `childSz < 8` also rejects the two box-size forms this code can't handle -- the 64-bit
            // form (literal size field == 1) and the extends-to-EOF form (== 0) -- since the returned
            // payloadStart assumes the ordinary 8-byte header and patchBoxSize patches the ordinary
            // 4-byte size field. See findMetaBoxBounds' matching guard.
            if (childSz < 8 || mp + childSz > parentEnd) return null
            if (childFourCC == fourCCTarget) return BoxBounds(mp, mp + 8, mp + childSz)
            mp += childSz
        }
        return null
    }

    /** Reads every infe child's item_ID inside an iinf box. */
    private fun collectInfeItemIds(heicBytes: ByteArray, iinf: BoxBounds): List<Long> {
        val ids = mutableListOf<Long>()
        var mp = iinf.payloadStart + 4 // skip iinf's own FullBox header (version+flags) + entry_count... entry_count read below
        // iinf FullBox header: version(1)+flags(3) at payloadStart, entry_count at payloadStart+4 (2 or 4 bytes by version)
        val iinfVersion = heicBytes[iinf.payloadStart].toInt() and 0xFF
        val entryCountWidth = if (iinfVersion == 0) 2 else 4
        mp = iinf.payloadStart + 4 + entryCountWidth
        while (mp < iinf.end - 8) {
            val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
            if (childSz < 8 || mp + childSz > iinf.end) break
            val childFourCC = String(heicBytes, mp + 4, 4, Charsets.US_ASCII)
            if (childFourCC != "infe") {
                // A free/skip padding box (or any other child type) inside iinf is legal ISOBMFF --
                // skip it rather than mis-reading its bytes as an infe's version/item_ID fields,
                // which would inflate the count and could feed a garbage value into the new-ID
                // selection below.
                mp += childSz
                continue
            }
            val infeVersion = heicBytes[mp + 8].toInt() and 0xFF
            // ISO/IEC 14496-12: ItemInfoEntry's item_ID is 16-bit for versions 0, 1 AND 2 -- only
            // version 3 widens it to 32-bit. Treating "not version 2" as 4 bytes mis-reads every
            // version 0/1 entry (and would then feed a garbage maximum into the new-ID choice).
            val itemIdWidth = if (infeVersion >= 3) 4 else 2
            val itemIdOffset = mp + 12
            val itemId = if (itemIdWidth == 2) {
                ((heicBytes[itemIdOffset].toLong() and 0xFF) shl 8) or (heicBytes[itemIdOffset + 1].toLong() and 0xFF)
            } else {
                ((heicBytes[itemIdOffset].toLong() and 0xFF) shl 24) or ((heicBytes[itemIdOffset + 1].toLong() and 0xFF) shl 16) or
                    ((heicBytes[itemIdOffset + 2].toLong() and 0xFF) shl 8) or (heicBytes[itemIdOffset + 3].toLong() and 0xFF)
            }
            ids.add(itemId)
            mp += childSz
        }
        return ids
    }

    private fun putUIntOfWidth(buf: ByteBuffer, widthBytes: Int, value: Long) {
        for (i in 0 until widthBytes) {
            val shift = (widthBytes - 1 - i) * 8
            buf.put(((value shr shift) and 0xFF).toByte())
        }
    }

    /** Adds `delta` to the 4-byte big-endian size field at the start of the box at `boxStart`. */
    private fun patchBoxSize(bytes: ByteArray, boxStart: Int, delta: Long) {
        val current = ((bytes[boxStart].toLong() and 0xFF) shl 24) or ((bytes[boxStart + 1].toLong() and 0xFF) shl 16) or
            ((bytes[boxStart + 2].toLong() and 0xFF) shl 8) or (bytes[boxStart + 3].toLong() and 0xFF)
        writeUIntOfWidth(bytes, boxStart.toLong(), 4, current + delta)
    }

    /** Increments iinf's entry_count field by 1. */
    private fun patchInfeItemCount(bytes: ByteArray, iinfStart: Int) {
        val payloadStart = iinfStart + 8
        val version = bytes[payloadStart].toInt() and 0xFF
        val countWidth = if (version == 0) 2 else 4
        val countOffset = (payloadStart + 4).toLong()
        val current = if (countWidth == 2) {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 1].toLong() and 0xFF)
        } else {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 24) or ((bytes[countOffset.toInt() + 1].toLong() and 0xFF) shl 16) or
                ((bytes[countOffset.toInt() + 2].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 3].toLong() and 0xFF)
        }
        writeUIntOfWidth(bytes, countOffset, countWidth, current + 1)
    }

    /** Increments iloc's item_count field by 1. */
    private fun patchIlocItemCount(bytes: ByteArray, ilocStart: Int) {
        val payloadStart = ilocStart + 8
        val version = bytes[payloadStart].toInt() and 0xFF
        val countWidth = if (version < 2) 2 else 4
        val countOffset = (payloadStart + 6).toLong()
        val current = if (countWidth == 2) {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 1].toLong() and 0xFF)
        } else {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 24) or ((bytes[countOffset.toInt() + 1].toLong() and 0xFF) shl 16) or
                ((bytes[countOffset.toInt() + 2].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 3].toLong() and 0xFF)
        }
        writeUIntOfWidth(bytes, countOffset, countWidth, current + 1)
    }

    /**
     * Walks the first `existingItemCount` item entries in iloc (using the CURRENT, already-grown
     * iloc box at ilocStart -- but only the entries that existed BEFORE this growth) and adds
     * `delta` to any construction_method=0 extent whose absolute offset was >= `oldMetaEnd` (i.e.
     * it pointed past where meta used to end, before this growth).
     *
     * `existingItemCount` MUST be ILOC's OWN item count from before the new item was appended -- the
     * caller must capture it (from iloc's item_count field, NOT from iinf's infe count: different
     * box, different field, no structural guarantee they agree) before calling
     * patchIlocItemCount/appending the new entry. This is
     * deliberately NOT read from iloc's own (already-incremented) item_count field: this function
     * runs AFTER the new entry has already been appended with its correct, final, post-growth
     * offset, and iloc's entries are stored in a flat list where "the last entry" is only knowable
     * by position, not by any per-entry marker -- if this walked the incremented item_count instead
     * of the caller-supplied `existingItemCount`, it would also visit the newly-appended entry and
     * add `delta` to its already-correct offset a second time, corrupting the very entry
     * createHeicXmpItem/repointHeicXmpItem just created. The test in Step 1
     * (`createHeicXmpItem registers a new item without disturbing the existing primary item's
     * bytes`) re-parses the new XMP item's own resolved bytes and would fail if this were wrong,
     * but it would NOT catch a double-shift of the new entry specifically (its bytes are appended
     * fresh regardless of the extent_offset value used to find them, only the read-back would
     * silently read from the wrong place if this were broken and happened to still resolve to
     * something parseable) -- rely on the explicit `existingItemCount` contract, not on that test
     * alone, when reviewing this function.
     *
     * `ilocEnd` is the (post-growth) end of iloc's own box. Every field read/written here is bounds
     * checked against it, so a wrong `existingItemCount` can at worst stop the walk early -- it can
     * never march past iloc and start adding `delta` into whatever box happens to follow.
     *
     * Known limitation (pre-existing, unchanged): only offsets at/after `oldMetaEnd` are shifted, so
     * a construction_method=0 extent pointing INSIDE meta but after the insertion points would not
     * be corrected. Absolute-offset extents into meta don't occur in practice (meta-internal item
     * data lives in `idat` and is referenced with construction_method=1, which is exempt anyway).
     */
    private fun shiftAbsoluteIlocOffsetsPastMeta(
        bytes: ByteArray,
        ilocStart: Int,
        ilocEnd: Int,
        oldMetaEnd: Long,
        delta: Long,
        existingItemCount: Int,
    ) {
        val header = findIlocHeaderFields(bytes) ?: return
        val payloadStart = ilocStart + 8
        val itemCountWidth = if (header.version < 2) 2 else 4
        var pos = payloadStart + 6 + itemCountWidth
        val itemCount = existingItemCount
        val itemIdWidth = if (header.version < 2) 2 else 4
        val constructionMethodWidth = if (header.version in 1..2) 2 else 0
        for (i in 0 until itemCount) {
            // Every fixed-width field of this entry's head must fit inside iloc before it's touched.
            if (pos + itemIdWidth + constructionMethodWidth + 2 + header.baseOffsetSize + 2 > ilocEnd) return
            pos += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) {
                (((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)) and 0xF
            } else {
                0
            }
            pos += constructionMethodWidth
            pos += 2 // data_reference_index
            var baseOffset = 0L
            for (b in 0 until header.baseOffsetSize) baseOffset = (baseOffset shl 8) or (bytes[pos + b].toLong() and 0xFF)
            pos += header.baseOffsetSize
            val extentCount = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            for (e in 0 until extentCount) {
                if (pos + header.indexSize + header.offsetSize + header.lengthSize > ilocEnd) return
                if (header.indexSize > 0) pos += header.indexSize
                val offsetFieldPos = pos
                if (constructionMethod == 0) {
                    // The item's real absolute position is base_offset + extent_offset, not
                    // extent_offset alone -- an encoder that sets a shared base_offset (e.g. pointing
                    // at mdat's start) and keeps extent_offset small/relative would otherwise never
                    // trip the ">= oldMetaEnd" check here even though the item genuinely needs
                    // shifting, silently leaving it pointing at the wrong (pre-growth) location.
                    var extentOffset = 0L
                    for (b in 0 until header.offsetSize) extentOffset = (extentOffset shl 8) or (bytes[offsetFieldPos + b].toLong() and 0xFF)
                    if (baseOffset + extentOffset >= oldMetaEnd) {
                        // Shifting extent_offset alone (leaving base_offset untouched) moves the
                        // resolved total by the same delta -- no need to also rewrite base_offset.
                        writeUIntOfWidth(bytes, offsetFieldPos.toLong(), header.offsetSize, extentOffset + delta)
                    }
                }
                pos += header.offsetSize
                pos += header.lengthSize
            }
        }
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

        // First pass: scan for an existing XMP APP1 (anywhere after insertPos) and capture its text,
        // without mutating anything yet -- mergeMotionPhotoXmp needs this before we can build the
        // segment we're about to insert at insertPos.
        var existingXmpText: String? = null
        run {
            var scanPos = insertPos
            while (scanPos < jpegBytes.size - 1) {
                val b0 = jpegBytes[scanPos].toInt() and 0xFF
                val b1 = jpegBytes[scanPos + 1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xDA) break // start of scan data -- no more markers
                if (b0 != 0xFF) break
                // Length-less markers (restart markers D0-D7, TEM 01) never carry a 2-byte length
                // field -- check for them BEFORE attempting to read one, matching the rewrite pass
                // below. Reading a length for these would misinterpret the two bytes that follow
                // (arbitrary marker-stream content, not a length) and could skip past a real XMP
                // segment.
                if (b1 in 0xD0..0xD7 || b1 == 0x01) {
                    scanPos += 2
                    continue
                }
                if (scanPos + 4 > jpegBytes.size) break
                val segLen = ((jpegBytes[scanPos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[scanPos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (scanPos + totalSegSize > jpegBytes.size) break
                if (b1 == 0xE1) {
                    val payloadStart = scanPos + 4
                    val hasXmpPrefix = (scanPos + totalSegSize >= payloadStart + XMP_IDENTIFIER.size) &&
                        jpegBytes.copyOfRange(payloadStart, payloadStart + XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
                    if (hasXmpPrefix) {
                        val textStart = payloadStart + XMP_IDENTIFIER.size + 1 // +1 for the NUL after the identifier
                        val textEnd = scanPos + totalSegSize
                        if (textStart in 0..textEnd && textEnd <= jpegBytes.size) {
                            existingXmpText = String(jpegBytes, textStart, textEnd - textStart, Charsets.UTF_8)
                        }
                        break
                    }
                }
                scanPos += totalSegSize
            }
        }

        val xmpText = mergeMotionPhotoXmp(existingXmpText, videoOffsetFromEof, primaryPadding, presentationTimestampUs, version, "image/jpeg")
        val app1Segment = buildApp1XmpSegment(xmpText)

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
     * Produces the base HEIC bytes with motion-photo XMP attached -- either merged into the file's
     * EXISTING XMP item (repointed to freshly-appended bytes) or, only when the file genuinely has no
     * XMP at all, as a brand-new registered item.
     *
     * The "does an XMP item already exist" question is answered structurally first
     * (findHeicXmpItemLocation: iinf item_type/content_type, then iloc by item_ID). Content sniffing
     * is used only as a secondary probe for files whose XMP item can't be identified structurally
     * (an infe version 0/1 registration, or a non-standard content_type): if XMP bytes are present but
     * their iloc entry can't be pinned down, this returns the base bytes UNTOUCHED.
     *
     * That last part matters. The previous behavior -- fall back to registering a NEW item -- left the
     * original XMP item still registered in iinf/iloc, earlier in the list, as an orphaned duplicate.
     * Readers that return the first match (this app's own re-parse included) would then read the STALE
     * original and never see the motion-photo attributes, silently defeating the merge. A no-op is
     * strictly better: no data loss, no misleading duplicate, worst case just "no new XMP".
     */
    private fun buildHeicBaseWithMotionPhotoXmp(
        baseHeicBytes: ByteArray,
        videoOffsetFromEof: Long,
        syncTimestampUs: Long,
        version: MotionPhotoFormatVersion,
    ): ByteArray {
        fun mergedBytes(existingXmpText: String?): ByteArray =
            mergeMotionPhotoXmp(existingXmpText, videoOffsetFromEof, 0L, syncTimestampUs, version, "image/heic")
                .toByteArray(Charsets.UTF_8)

        val structuralItem = findHeicXmpItemLocation(baseHeicBytes)
        if (structuralItem != null) {
            val existingXmpText = readExistingXmpItemText(baseHeicBytes, structuralItem)
            return repointHeicXmpItem(
                baseHeicBytes,
                structuralItem.itemId,
                structuralItem.ilocEntryOffset,
                structuralItem.extentCount,
                mergedBytes(existingXmpText),
            ) ?: baseHeicBytes
        }

        val contentExtent = findXmpExtentInHeic(baseHeicBytes)
        if (contentExtent != null) {
            val (xmpStart, xmpLen) = contentExtent
            val entry = findHeicXmpIlocEntry(baseHeicBytes, xmpStart, xmpLen) ?: return baseHeicBytes
            val (itemId, entryOffset, extentCount) = entry
            val existingXmpText = String(baseHeicBytes, xmpStart, xmpLen, Charsets.UTF_8)
                .trimEnd(' ', Char(0), '\n', '\r')
            return repointHeicXmpItem(baseHeicBytes, itemId, entryOffset, extentCount, mergedBytes(existingXmpText))
                ?: baseHeicBytes
        }

        return createHeicXmpItem(baseHeicBytes, mergedBytes(null))
    }

    /**
     * Synthesizes a Samsung Galaxy HEIC Motion Photo file (.heic).
     */
    fun createSamsungHeicMotionPhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
        presentationTimestampUs: Long? = null,
    ) {
        require(imageFile.exists() && imageFile.length() > 0) { "Image file not found or is empty: ${imageFile.absolutePath}" }
        require(videoFile.exists() && videoFile.length() > 0) { "Video file not found or is empty: ${videoFile.absolutePath}" }
        require(version != MotionPhotoFormatVersion.V1_MICRO_VIDEO) {
            "MicroVideo (v1.0) format is not supported for HEIC/HEIF images. Please use Motion Photo v2.0."
        }

        val rawHeicBytes = imageFile.readBytes()
        val (baseHeicBytes, preservedSefBlocks) = extractExistingHeicBoxesAndSef(rawHeicBytes)

        val videoLength = videoFile.length()

        // --- Ordering note (load-bearing) -------------------------------------------------------
        // The base HEIC GROWS in step 3 below, when the XMP item is repointed/created (both paths
        // append bytes; neither is an in-place same-size overwrite). The SEF MotionPhoto_Data block's
        // `video_offset` is an ABSOLUTE file offset, so it must be computed from the POST-growth base
        // size -- computing it from baseHeicBytes.size leaves it short by exactly the growth amount,
        // and Samsung Gallery, which navigates to the video through that pointer, mislocates the
        // video. (This app's own checker/extractor resolve the video structurally via the `mpvd` box
        // instead, so they never notice -- hence no test caught it.)
        //
        // But step 3 itself needs `videoOffsetFromEof` for the XMP text. That is NOT circular:
        // videoOffsetFromEof = videoLength + sefdBoxSize, and sefdBoxSize depends only on the SEF
        // blocks' own BYTE SIZES -- never on where in the file they land. MotionPhoto_Data's size in
        // particular is fixed (block header + 12-byte payload) regardless of what offset value it
        // carries. So the order is: size everything (steps 1-2) -> merge the XMP and learn the final
        // base size (step 3) -> compute the real mpvd/video offsets and patch them into the
        // already-sized block (steps 4-5) -> emit the directory/headers (steps 6-9).
        // ---------------------------------------------------------------------------------------

        // 1. Build SEF Blocks for HEIC
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

        // Block: MotionPhoto_Data (12-byte pointer payload: "mpv2" + videoStartOffset + videoLength).
        // video_offset goes in as a placeholder and is patched in step 5, once the final base-HEIC
        // size is known -- see the ordering note above. The block's SIZE is unaffected by the value.
        val dNameBytes = "MotionPhoto_Data".toByteArray(Charsets.UTF_8)
        val dPayloadBuf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        dPayloadBuf.put("mpv2".toByteArray(Charsets.US_ASCII))
        dPayloadBuf.putInt(0) // video_offset -- patched in step 5
        dPayloadBuf.putInt(videoLength.toInt())

        val dHeaderBuf = ByteBuffer.allocate(8 + dNameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        dHeaderBuf.putShort(0x0000.toShort())
        dHeaderBuf.putShort(SEF_MARKER_MOTION_PHOTO_DATA.toShort())
        dHeaderBuf.putInt(dNameBytes.size)
        dHeaderBuf.put(dNameBytes)
        val dBlockHeaderBytes = dHeaderBuf.array()
        val dBlockBytes = dBlockHeaderBytes + dPayloadBuf.array()
        // Position of the video_offset field inside dBlockBytes: block header, then "mpv2" (4 bytes).
        val videoOffsetFieldPos = dBlockHeaderBytes.size + 4
        allSefBlocks.add(PreservedSefBlock("MotionPhoto_Data", SEF_MARKER_MOTION_PHOTO_DATA, 0x0000, dBlockBytes))

        // 2. SEF directory / trailer sizes -- a function of the blocks' own byte sizes only, never of
        //    where in the file they end up, which is what makes step 3 possible before step 4.
        val totalBlockBytesSize = allSefBlocks.sumOf { it.bytes.size.toLong() }
        val sefDirSize = 12 + allSefBlocks.size * 12
        val seftTailSize = 8
        val sefPayloadSize = totalBlockBytesSize + sefDirSize + seftTailSize
        val sefdBoxSize = 8L + sefPayloadSize

        // 3. Merge into (or create) the XMP item. videoOffsetFromEof is the distance from EOF back to
        //    the video's first byte: everything after the video is the sefd box, so it's
        //    videoLength + sefdBoxSize -- independent of where the video itself starts.
        val videoOffsetFromEof = videoLength + sefdBoxSize
        val videoDurationUs = extractVideoDurationUs(videoFile)
        val syncTimestampUs = resolvePresentationTimestampUs(presentationTimestampUs, videoDurationUs)
        val updatedBaseHeicBytes = try {
            buildHeicBaseWithMotionPhotoXmp(baseHeicBytes, videoOffsetFromEof, syncTimestampUs, version)
        } catch (e: Exception) {
            // Hard global constraint: an XMP merge failure must NEVER abort motion-photo creation.
            // Any unexpected exception out of the whole iinf/iloc box-surgery subsystem falls back to
            // the untouched base bytes -- the output still gets its mpvd video and SEF trailer, it
            // just doesn't gain the new XMP.
            baseHeicBytes
        }

        // 4. mpvd box calculation -- from the POST-growth base size (see the ordering note above).
        val mpvdOffset = updatedBaseHeicBytes.size.toLong()
        val mpvdSize = 8L + videoLength
        val videoStartOffset = mpvdOffset + 8L

        // 5. Patch the now-known absolute video offset into the MotionPhoto_Data block. dBlockBytes is
        //    the very array the PreservedSefBlock added above holds, so this reaches the bytes written
        //    out in step 9.
        writeUIntOfWidth(dBlockBytes, videoOffsetFieldPos.toLong(), 4, videoStartOffset)

        // 6. Build SEFH Directory Table
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

        // 7. Build SEFT Tail (8 bytes)
        val seftBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        seftBuf.putInt(sefDirSize)
        seftBuf.put("SEFT".toByteArray(Charsets.US_ASCII))

        // 8. mpvd + sefd box headers (8 bytes each)
        val mpvdHeaderBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        mpvdHeaderBuf.putInt(mpvdSize.toInt())
        mpvdHeaderBuf.put("mpvd".toByteArray(Charsets.US_ASCII))

        val sefdHeaderBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        sefdHeaderBuf.putInt(sefdBoxSize.toInt())
        sefdHeaderBuf.put("sefd".toByteArray(Charsets.US_ASCII))

        // 9. Write complete Motion Photo HEIC file
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
        presentationTimestampUs: Long? = null,
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
        val videoDurationUs = extractVideoDurationUs(videoFile)
        val syncTimestampUs = resolvePresentationTimestampUs(presentationTimestampUs, videoDurationUs)

        // 4. Inject Google Motion Photo XMP into base JPEG
        val motionPhotoJpegBytes = injectMotionPhotoXmpIntoJpeg(
            baseJpegBytes,
            offsetFromEofToVideo,
            primaryPadding,
            syncTimestampUs,
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
