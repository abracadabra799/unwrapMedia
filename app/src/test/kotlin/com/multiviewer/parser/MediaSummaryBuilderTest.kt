package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSummaryBuilderTest {
    private fun tempFile(bytes: Int = 0): File {
        val tmp = File.createTempFile("media-summary-test", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(bytes))
        return tmp
    }

    @Test
    fun `a JPEG-shaped root (has an SOI child) is classified as IMAGE`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 4,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
                BoxNode(type = "EOI", offset = 2, headerSize = 2, size = 2),
            ),
        )
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with a moov track whose handler is video is classified as VIDEO`() {
        val hdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val mdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(hdlr))
        val trak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(trak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))
        assertEquals(MediaCategory.VIDEO, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with no moov (HEIC-shaped) is classified as IMAGE`() {
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `a nested moov reachable only through non-root paths does not affect classification`() {
        val nestedHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val nestedMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(nestedHdlr))
        val nestedTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMdia))
        val nestedMoov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(nestedTrak))
        val mpvd = BoxNode(type = "mpvd", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMoov))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta, mpvd))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `a full image tree produces all four image sections with correct values`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("GPSLatitudeRef", "N", 0, 1),
                BoxField("GPSLatitude", "37/1, 34/1, 0/1", 0, 24),
                BoxField("GPSLongitudeRef", "E", 0, 1),
                BoxField("GPSLongitude", "127/1, 0/1, 0/1", 0, 24),
            ),
        )
        val exif = BoxNode(
            type = "Exif", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ExposureTime", "1/100", 0, 8),
                BoxField("FNumber", "28/10", 0, 8),
                BoxField("ISOSpeedRatings", "200", 0, 2),
                BoxField("FocalLength", "50/1", 0, 8),
                BoxField("DateTimeOriginal", "2026:07:19 10:00:00", 0, 20),
            ),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("Make", "TestCam", 0, 8),
                BoxField("Model", "X100", 0, 5),
                BoxField("DateTime", "2026:07:19 09:00:00", 0, 20),
            ),
            children = listOf(exif, gps),
        )
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 4, size = 0, children = listOf(ifd0))
        val sefdField = BoxNode(type = "Image_UTC_Data", offset = 0, headerSize = 0, size = 0, summary = "1784372666391")
        val sefd = BoxNode(type = "sefd", offset = 0, headerSize = 0, size = 0, children = listOf(sefdField))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), app1, sof0, sefd),
        )
        val tmp = File.createTempFile("media-summary-image-test", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_500_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.IMAGE, summary.category)
        assertEquals(4, summary.sections.size)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("1.5 MB", basicInfo.fields.first { it.label == "File Size" }.value)
        assertEquals("JPEG", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("Color (YCbCr)", basicInfo.fields.first { it.label == "Color Space" }.value)
        assertEquals("2026:07:19 10:00:00", basicInfo.fields.first { it.label == "Capture Date" }.value)

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("TestCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("X100", cameraInfo.fields.first { it.label == "Model" }.value)
        assertEquals("1/100", cameraInfo.fields.first { it.label == "Exposure Time" }.value)
        assertEquals("28/10", cameraInfo.fields.first { it.label == "F-Number" }.value)
        assertEquals("200", cameraInfo.fields.first { it.label == "ISO" }.value)
        assertEquals("50/1", cameraInfo.fields.first { it.label == "Focal Length" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
        assertEquals("37/1, 34/1, 0/1", gpsSection.fields.first { it.label == "Latitude" }.value)

        val samsungSection = summary.sections.first { it.title == "Samsung Metadata" }
        assertEquals("1784372666391", samsungSection.fields.first { it.label == "Image_UTC_Data" }.value)
    }

    @Test
    fun `a minimal image tree with no Exif produces only a Basic Info section`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "1", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(1, summary.sections.size)
        val basicInfo = summary.sections[0]
        assertEquals("Basic Info", basicInfo.title)
        assertEquals("Grayscale", basicInfo.fields.first { it.label == "Color Space" }.value)
    }
}
