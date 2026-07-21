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

    private fun buildVideoFixture(includeAudioTrack: Boolean): BoxNode {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_size", "0", 0, 4), BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd, videoStsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "1", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("width", "1920.0", 0, 4), BoxField("height", "1080.0", 0, 4)))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoTkhd, videoMdia))

        val moovChildren = mutableListOf<BoxNode>()
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "20000", 0, 4)))
        moovChildren.add(mvhd)
        moovChildren.add(videoTrak)

        if (includeAudioTrack) {
            val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
            val mp4a = BoxNode(type = "mp4a", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("channelcount", "2", 0, 2), BoxField("samplerate", "44100.0", 0, 4)))
            val audioStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(mp4a))
            val audioStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(audioStsd))
            val audioMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(audioStbl))
            val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMinf))
            val audioTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "2", 0, 4)))
            val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioTkhd, audioMdia))
            moovChildren.add(audioTrak)
        }

        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = moovChildren)
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))
    }

    @Test
    fun `a full video tree produces all four video sections with correct values`() {
        val root = buildVideoFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("media-summary-video-test", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
        assertEquals(4, summary.sections.size)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("0:00:20", basicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("isom", basicInfo.fields.first { it.label == "Container Brand" }.value)
        assertEquals("500.0 Kbps", basicInfo.fields.first { it.label == "Average Bitrate" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video Track Detail" }
        assertEquals("avc1", videoDetail.fields.first { it.label == "Codec" }.value)
        // Deliberately distinct from mvhd's 20s movie-level duration above: this fixture's video
        // track has its own mdhd (30000/300000 = 10s). If frame-rate calculation ever regressed to
        // use mvhd's duration instead of the track's own mdhd, this would compute 15.00 fps instead
        // of the correct 30.00 fps.
        assertEquals("30.00 fps", videoDetail.fields.first { it.label == "Frame Rate" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio Track Detail" }
        assertEquals("mp4a", audioDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sample Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channels" }.value)
    }

    @Test
    fun `a video-only tree (no audio track) omits Audio Track Detail`() {
        val root = buildVideoFixture(includeAudioTrack = false)
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio Track Detail" })
        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("0", trackList.fields.first { it.label == "Audio Tracks" }.value)
    }

    @Test
    fun `Resolution and Color Space use the primary item's ispe and colr, not the first one in tree order`() {
        val tileIspe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "512", 0, 4), BoxField("image_height", "512", 0, 4)),
        )
        val tileColr = BoxNode(type = "colr", offset = 0, headerSize = 0, size = 0, summary = "ICC profile (10 bytes)")
        val primaryIspe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "4000", 0, 4), BoxField("image_height", "2252", 0, 4)),
        )
        val primaryColr = BoxNode(type = "colr", offset = 0, headerSize = 0, size = 0, summary = "nclx: 9/16/9")
        val ipco = BoxNode(
            type = "ipco", offset = 0, headerSize = 0, size = 0,
            children = listOf(tileIspe, tileColr, primaryIspe, primaryColr),
        )
        val ipmaTileItem = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 1), BoxField("property_index", "2", 0, 1)),
        )
        val ipmaPrimaryItem = BoxNode(
            type = "item_99", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "3", 0, 1), BoxField("property_index", "4", 0, 1)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaTileItem, ipmaPrimaryItem))
        // ipma is a child of iprp (a sibling of ipco), not a direct child of meta — matches the real HEIF box layout.
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(
            type = "pitm", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("primary_item_ID", "99", 0, 4)),
        )
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("4000x2252", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("nclx: 9/16/9", basicInfo.fields.first { it.label == "Color Space" }.value)
    }

    @Test
    fun `without pitm or ipma, Resolution falls back to the first ispe in tree order`() {
        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "800", 0, 4), BoxField("image_height", "600", 0, 4)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("800x600", basicInfo.fields.first { it.label == "Resolution" }.value)
    }

    @Test
    fun `when the primary item's properties don't include a colr, Color Space falls back to the first colr in tree order`() {
        val tileColr = BoxNode(type = "colr", offset = 0, headerSize = 0, size = 0, summary = "ICC profile (10 bytes)")
        val primaryIrot = BoxNode(type = "irot", offset = 0, headerSize = 0, size = 0)
        val ipco = BoxNode(
            type = "ipco", offset = 0, headerSize = 0, size = 0,
            children = listOf(tileColr, primaryIrot),
        )
        val ipmaPrimaryItem = BoxNode(
            type = "item_5", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "2", 0, 1)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaPrimaryItem))
        // ipma is a child of iprp (a sibling of ipco), not a direct child of meta — matches the real HEIF box layout.
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(
            type = "pitm", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("primary_item_ID", "5", 0, 4)),
        )
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("ICC profile (10 bytes)", basicInfo.fields.first { it.label == "Color Space" }.value)
    }

    @Test
    fun `a motion photo image populates motionPhotoVideoSections from the embedded video, using the video's own size`() {
        val bytes = byteArrayOf(
            // outer ftyp (16 bytes) — the containing photo's own top-level ftyp
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            // mpvd header, size=60 — the embedded video wrapper
            0x00, 0x00, 0x00, 0x3C, 'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
            // nested ftyp (16 bytes) — the embedded video's own ftyp
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            // moov, size=36
            0x00, 0x00, 0x00, 0x24, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(),
            // mvhd, size=28: version+flags, creation_time, modification_time, timescale=1000, duration=2000
            0x00, 0x00, 0x00, 0x1C, 'm'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0x00, 0x00, 0x07, 0xD0.toByte(),
        )
        val file = File.createTempFile("motion-photo-video-summary", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val root = parseFile(file)
        val summary = buildMediaSummary(root, file)

        assertEquals(MediaCategory.IMAGE, summary.category)
        val imageBasicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("76 bytes", imageBasicInfo.fields.first { it.label == "File Size" }.value)

        val videoSections = summary.motionPhotoVideoSections
        assertEquals(true, videoSections != null)
        val videoBasicInfo = videoSections!!.first { it.title == "Basic Info" }
        assertEquals("0:00:02", videoBasicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("52 bytes", videoBasicInfo.fields.first { it.label == "File Size" }.value)
    }

    @Test
    fun `an ordinary non-motion-photo image leaves motionPhotoVideoSections null`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("ordinary-image", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val root = parseFile(file)
        val summary = buildMediaSummary(root, file)

        assertEquals(null, summary.motionPhotoVideoSections)
    }

    @Test
    fun `an AVIF-shaped tree (ftyp major_brand avif) produces correct Resolution, Format, and File Size`() {
        val ftyp = BoxNode(
            type = "ftyp", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("major_brand", "avif", 0, 4)),
        )
        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "1920", 0, 4), BoxField("image_height", "1080", 0, 4)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, meta))
        val file = File.createTempFile("avif-summary-test", ".avif")
        file.deleteOnExit()
        file.writeBytes(ByteArray(500_000))

        val basicInfo = buildMediaSummary(root, file).sections.first { it.title == "Basic Info" }
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("avif", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 KB", basicInfo.fields.first { it.label == "File Size" }.value)
    }

    @Test
    fun `a TIFF-shaped tree (IFD0 as a direct root child) produces Resolution, Format TIFF, Camera Info, and GPS Location`() {
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("GPSLatitudeRef", "N", 0, 1),
                BoxField("GPSLatitude", "37/1, 34/1, 0/1", 0, 24),
            ),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("Make", "TiffCam", 0, 7),
                BoxField("Model", "T200", 0, 4),
            ),
            children = listOf(gps),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))
        val file = File.createTempFile("tiff-summary-test", ".tiff")
        file.deleteOnExit()
        file.writeBytes(ByteArray(1000))

        val summary = buildMediaSummary(root, file)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("TIFF", basicInfo.fields.first { it.label == "Format" }.value)

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("TiffCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("T200", cameraInfo.fields.first { it.label == "Model" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
    }
}
