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

object MotionPhotoBuilder {

    private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)
    private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"

    // Samsung SEF (Samsung Extension Format) constants
    private const val SEF_MARKER_MOTION_PHOTO_DATA = 0x0A30
    private const val SEF_VERSION = 0x00000106

    data class PreservedSefBlock(
        val name: String,
        val marker: Int,
        val typeCode: Int,
        val bytes: ByteArray,
    )

    /**
     * Builds Google Motion Photo & Samsung compatible XMP metadata XML string.
     */
    fun buildGoogleMotionPhotoXmp(videoOffsetFromEof: Long): String {
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="0"
                  GCamera:MicroVideo="1"
                  GCamera:MicroVideoVersion="1"
                  GCamera:MicroVideoOffset="$videoOffsetFromEof"
                  GCamera:MicroVideoPresentationTimestampUs="0">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item
                          Item:Semantic="Primary"
                          Item:Mime="image/jpeg"
                          Item:Padding="0"/>
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
     * Extracts existing SEF data blocks from the original image (e.g. Image_UTC_Data, MCC_Data,
     * Color_Display_P3, Photo_HDR_Info, Camera_Capture_Mode_Info, Camera_Scene_Info, etc.),
     * preserving all original metadata blocks while filtering out old MotionPhoto_Data / MotionPhoto_AutoPlay.
     * Returns a pair of (Base JPEG bytes before SEF, List of Preserved SEF Blocks).
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
        val preserved = mutableListOf<PreservedSefBlock>()
        var minBlockStart = sefhPos
        var entryPos = sefhPos + 12

        for (i in 0 until count) {
            if (entryPos + 12 > imageBytes.size) break
            val typeCode = sefBuf.getShort(entryPos).toInt() and 0xFFFF
            val marker = sefBuf.getShort(entryPos + 2).toInt() and 0xFFFF
            val offset = sefBuf.getInt(entryPos + 4).toLong() and 0xFFFFFFFFL
            val length = sefBuf.getInt(entryPos + 8).toLong() and 0xFFFFFFFFL
            val blockStart = (sefhPos - offset).toInt()
            val blockEnd = (blockStart + length).toInt()

            if (blockStart in 0..imageBytes.size && blockEnd in blockStart..imageBytes.size && length >= 8) {
                val nameLen = sefBuf.getInt(blockStart + 4)
                if (nameLen in 1..256 && blockStart + 8 + nameLen <= blockEnd) {
                    val name = String(imageBytes.copyOfRange(blockStart + 8, blockStart + 8 + nameLen), Charsets.UTF_8).trimEnd(Char(0))
                    minBlockStart = minOf(minBlockStart, blockStart)

                    // Preserve all SEF data blocks except old video streams
                    if (name != "MotionPhoto_Data" && name != "MotionPhoto_AutoPlay") {
                        val blockBytes = imageBytes.copyOfRange(blockStart, blockEnd)
                        preserved.add(PreservedSefBlock(name, marker, typeCode, blockBytes))
                    }
                }
            }
            entryPos += 12
        }

        val baseJpeg = imageBytes.copyOfRange(0, minBlockStart)
        return Pair(baseJpeg, preserved)
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
     * Injects the Motion Photo APP1 XMP segment into JPEG bytes at standard position:
     * - If Exif APP1 is present immediately after SOI (0xFFD8), XMP is placed right after Exif APP1.
     * - If no Exif APP1, XMP is placed right after SOI.
     * - Any existing XMP APP1 segments are cleanly stripped out.
     */
    fun injectMotionPhotoXmpIntoJpeg(jpegBytes: ByteArray, totalSefSize: Long): ByteArray {
        require(jpegBytes.size >= 4 && (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) {
            "Invalid JPEG bytes: missing SOI marker (0xFFD8)"
        }

        val xmpText = buildGoogleMotionPhotoXmp(totalSefSize)
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
        // Write headers before insertion point (SOI + Exif if present)
        out.write(jpegBytes, 0, insertPos)

        // Write new Motion Photo APP1 XMP segment
        out.write(app1Segment)

        // Parse remaining segments, stripping any existing XMP APP1 segments
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
                        // Skip existing XMP segment
                        pos += totalSegSize
                        continue
                    }
                }
            }

            // If start of scan (SOS, 0xFFDA), copy the rest of the stream up to EOI
            if (b0 == 0xFF && b1 == 0xDA) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }

            // Normal marker with no payload
            if (b0 == 0xFF && (b1 == 0xD9 || (b1 in 0xD0..0xD7) || b1 == 0x01)) {
                out.write(b0)
                out.write(b1)
                pos += 2
                continue
            }

            // Segment with length
            if (b0 == 0xFF && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    out.write(jpegBytes, pos, totalSegSize)
                    pos += totalSegSize
                    continue
                }
            }

            // Fallback
            out.write(jpegBytes[pos].toInt() and 0xFF)
            pos++
        }

        return out.toByteArray()
    }

    /**
     * Merges an image file and a video file into a standard Google/Samsung Motion Photo JPEG file.
     * Preserves:
     * 1. All original EXIF, XMP, ICC, GainMap metadata from the image.
     * 2. All original Samsung SEF metadata fields (Image_UTC_Data, MCC_Data, Color_Display_P3, Photo_HDR_Info, Camera_Capture_Mode_Info, etc.).
     * 3. Wraps the video inside a standard MotionPhoto_Data block and indexes it in the SEFH directory and SEFT tail.
     */
    fun createGoogleMotionPhoto(imageFile: File, videoFile: File, outputFile: File) {
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

        val totalSefSize = totalBlockBytesSize + sefDirSize + seftTailSize

        // 4. Inject Google Motion Photo XMP into base JPEG
        val motionPhotoJpegBytes = injectMotionPhotoXmpIntoJpeg(baseJpegBytes, totalSefSize)

        // 5. Build SEFH Directory Table
        val sefDirBuf = ByteBuffer.allocate(sefDirSize).order(ByteOrder.LITTLE_ENDIAN)
        sefDirBuf.put("SEFH".toByteArray(Charsets.US_ASCII))
        sefDirBuf.putInt(SEF_VERSION)
        sefDirBuf.putInt(totalEntryCount)

        // Compute backward offsets from SEFH pos for each block
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
            // 1. Primary JPEG with Exif and XMP APP1
            out.write(motionPhotoJpegBytes)

            // 2. All preserved SEF data blocks from the original image
            for (block in preservedSefBlocks) {
                out.write(block.bytes)
            }

            // 3. New MotionPhoto_Data Block Header (24 bytes)
            out.write(motionBlockHeaderBytes)

            // 4. MP4 Video Stream Payload
            FileInputStream(videoFile).use { videoIn ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (videoIn.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }

            // 5. SEFH Header & Directory Table
            out.write(sefDirBytes)

            // 6. SEFT Tail at EOF
            out.write(seftBytes)
        }
    }
}
