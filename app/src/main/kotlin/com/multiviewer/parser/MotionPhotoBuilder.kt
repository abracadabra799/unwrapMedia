package com.multiviewer.parser

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter

object MotionPhotoBuilder {

    private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)

    /**
     * Builds standard Google Motion Photo XMP metadata XML string.
     */
    fun buildGoogleMotionPhotoXmp(videoLength: Long): String {
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1"
                    GCamera:MotionPhotoPresentationTimestampUs="0"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="$videoLength"
                    GCamera:MicroVideoPresentationTimestampUs="0">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>image/jpeg</Item:Mime>
                        <Item:Semantic>Primary</Item:Semantic>
                        <Item:Length>0</Item:Length>
                        <Item:Padding>0</Item:Padding>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>video/mp4</Item:Mime>
                        <Item:Semantic>MotionPhoto</Item:Semantic>
                        <Item:Length>$videoLength</Item:Length>
                        <Item:Padding>0</Item:Padding>
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
     * Converts a non-JPEG image file to standard JPEG byte array at high quality (95%).
     */
    fun convertImageToJpegBytes(imageFile: File): ByteArray {
        val image = ImageIO.read(imageFile) ?: throw IllegalArgumentException("Failed to decode image: ${imageFile.name}")
        // Create RGB buffered image (handles ARGB/RGBA alpha removal with white background)
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
     * Injects the Motion Photo APP1 XMP segment into JPEG bytes right after SOI (0xFF, 0xD8).
     * Any previous XMP APP1 segments are filtered out to prevent duplicate conflicting metadata.
     */
    fun injectMotionPhotoXmpIntoJpeg(jpegBytes: ByteArray, videoLength: Long): ByteArray {
        require(jpegBytes.size >= 4 && (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) {
            "Invalid JPEG bytes: missing SOI marker (0xFFD8)"
        }

        val xmpText = buildGoogleMotionPhotoXmp(videoLength)
        val app1Segment = buildApp1XmpSegment(xmpText)

        val out = ByteArrayOutputStream(jpegBytes.size + app1Segment.size)
        // Write SOI
        out.write(0xFF)
        out.write(0xD8)

        // Write new Motion Photo APP1 XMP segment immediately after SOI
        out.write(app1Segment)

        // Parse remaining segments, skipping any previous XMP APP1 segments
        var pos = 2
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

            // Normal segment: write marker + payload
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

            // Fallback: write single byte and advance
            out.write(jpegBytes[pos].toInt() and 0xFF)
            pos++
        }

        return out.toByteArray()
    }

    /**
     * Merges an image file and a video file into a standard Google Motion Photo JPEG file.
     */
    fun createGoogleMotionPhoto(imageFile: File, videoFile: File, outputFile: File) {
        require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }
        require(videoFile.exists()) { "Video file not found: ${videoFile.absolutePath}" }
        require(videoFile.length() > 0) { "Video file is empty: ${videoFile.absolutePath}" }

        val ext = imageFile.extension.lowercase(Locale.US)
        val jpegBytes = if (ext == "jpg" || ext == "jpeg") {
            imageFile.readBytes()
        } else {
            convertImageToJpegBytes(imageFile)
        }

        val motionPhotoJpegBytes = injectMotionPhotoXmpIntoJpeg(jpegBytes, videoFile.length())

        FileOutputStream(outputFile).use { out ->
            // 1. Write Primary JPEG with Motion Photo XMP
            out.write(motionPhotoJpegBytes)

            // 2. Append Video bytes in 64 KB chunks
            FileInputStream(videoFile).use { videoIn ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (videoIn.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
    }
}
