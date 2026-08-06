package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `an ISOBMFF root with a moov track whose handler is audio-only (M4A-shaped) is classified as AUDIO`() {
        val hdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
        val mdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(hdlr))
        val trak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(trak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))
        assertEquals(MediaCategory.AUDIO, buildMediaSummary(root, tempFile()).category)
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
    fun `a full image tree produces all five image sections with correct values`() {
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
        // 6, not 5: this fixture's SOF0 node now also produces a "JPEG Detail" section
        // (Encoding + Precision, since this fixture has no DQT/DHT/APP14/DRI/COM).
        assertEquals(6, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("1.5 MB", general.fields.first { it.label == "File Size" }.value)
        assertEquals("JPEG", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("640", image.fields.first { it.label == "Width" }.value)
        assertEquals("480", image.fields.first { it.label == "Height" }.value)
        assertEquals("Color (YCbCr)", image.fields.first { it.label == "Color Space" }.value)
        assertEquals("2026:07:19 10:00:00", image.fields.first { it.label == "Capture Date" }.value)

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
    fun `a minimal image tree with no Exif produces only General and Image sections`() {
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

        // 3, not 2: this fixture's SOF0 node now also produces a "JPEG Detail" section
        // (Encoding + Precision, since this fixture has no DQT/DHT/APP14/DRI/COM).
        assertEquals(3, summary.sections.size)
        assertEquals("General", summary.sections[0].title)
        assertEquals("Image", summary.sections[1].title)
        assertEquals("JPEG Detail", summary.sections[2].title)
        val image = summary.sections.first { it.title == "Image" }
        assertEquals("Grayscale", image.fields.first { it.label == "Color Space" }.value)
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
    fun `a full video tree produces General, Track List, Video, and Audio sections with correct values`() {
        val root = buildVideoFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("media-summary-video-test", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
        assertEquals(4, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:20.000", general.fields.first { it.label == "Duration" }.value)
        assertEquals("isom", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 Kbps", general.fields.first { it.label == "Overall Bit Rate" }.value)
        assertEquals(null, general.fields.find { it.label == "Width" })

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("AVC", videoDetail.fields.first { it.label == "Format" }.value)
        assertEquals("1920", videoDetail.fields.first { it.label == "Width" }.value)
        assertEquals("1080", videoDetail.fields.first { it.label == "Height" }.value)
        // Deliberately distinct from mvhd's 20s movie-level duration above: this fixture's video
        // track has its own mdhd (30000/300000 = 10s). If frame-rate calculation ever regressed to
        // use mvhd's duration instead of the track's own mdhd, this would compute 15.00 fps instead
        // of the correct 30.00 fps.
        assertEquals("30.00 fps", videoDetail.fields.first { it.label == "Frame Rate" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("AAC", audioDetail.fields.first { it.label == "Format" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channel(s)" }.value)
    }

    @Test
    fun `a video-only tree (no audio track) omits the Audio section`() {
        val root = buildVideoFixture(includeAudioTrack = false)
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("0", trackList.fields.first { it.label == "Audio Tracks" }.value)
    }

    @Test
    fun `an audio-only MP4-family tree (M4A-shaped) is classified as AUDIO and produces General, Track List, and Audio sections with no Video section`() {
        val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
        val mp4a = BoxNode(type = "mp4a", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("channelcount", "2", 0, 2), BoxField("samplerate", "44100.0", 0, 4)))
        val audioStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(mp4a))
        val audioStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(audioStsd))
        val audioMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(audioStbl))
        val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMinf))
        val audioTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "1", 0, 4)))
        val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioTkhd, audioMdia))
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "20000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, audioTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "M4A ", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(MediaCategory.AUDIO, summary.category)
        assertEquals(null, summary.sections.find { it.title == "Video" })

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:20.000", general.fields.first { it.label == "Duration" }.value)
        assertEquals("M4A ", general.fields.first { it.label == "Format" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("0", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("AAC", audioDetail.fields.first { it.label == "Format" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channel(s)" }.value)
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

        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("4000", image.fields.first { it.label == "Width" }.value)
        assertEquals("2252", image.fields.first { it.label == "Height" }.value)
        assertEquals("nclx: 9/16/9", image.fields.first { it.label == "Color Space" }.value)
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

        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("800", image.fields.first { it.label == "Width" }.value)
        assertEquals("600", image.fields.first { it.label == "Height" }.value)
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

        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("ICC profile (10 bytes)", image.fields.first { it.label == "Color Space" }.value)
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
        val imageGeneral = summary.sections.first { it.title == "General" }
        assertEquals("76 bytes", imageGeneral.fields.first { it.label == "File Size" }.value)

        val videoSections = summary.motionPhotoVideoSections
        assertEquals(true, videoSections != null)
        val videoGeneral = videoSections!!.first { it.title == "General" }
        assertEquals("0:00:02.000", videoGeneral.fields.first { it.label == "Duration" }.value)
        assertEquals("52 bytes", videoGeneral.fields.first { it.label == "File Size" }.value)
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

        val summary = buildMediaSummary(root, file)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("avif", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 KB", general.fields.first { it.label == "File Size" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("1920", image.fields.first { it.label == "Width" }.value)
        assertEquals("1080", image.fields.first { it.label == "Height" }.value)
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

        val general = summary.sections.first { it.title == "General" }
        assertEquals("TIFF", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("640", image.fields.first { it.label == "Width" }.value)
        assertEquals("480", image.fields.first { it.label == "Height" }.value)

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("TiffCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("T200", cameraInfo.fields.first { it.label == "Model" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
    }

    @Test
    fun `a PNG-shaped tree (IHDR as a direct root child) produces Resolution, Format PNG, and Color Space`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("width", "1920", 0, 4),
                BoxField("height", "1080", 0, 4),
                BoxField("color_type", "6", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))
        val file = File.createTempFile("png-summary-test", ".png")
        file.deleteOnExit()
        file.writeBytes(ByteArray(2000))

        val summary = buildMediaSummary(root, file)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("PNG", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("1920", image.fields.first { it.label == "Width" }.value)
        assertEquals("1080", image.fields.first { it.label == "Height" }.value)
        assertEquals("Truecolor+Alpha", image.fields.first { it.label == "Color Space" }.value)
    }

    @Test
    fun `a PNG's eXIf chunk populates Camera Info and GPS Location exactly like a TIFF's IFD0`() {
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("GPSLatitudeRef", "N", 0, 1)),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Make", "PngCam", 0, 6), BoxField("Model", "P900", 0, 4)),
            children = listOf(gps),
        )
        val exifChunk = BoxNode(type = "eXIf", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "640", 0, 4), BoxField("height", "480", 0, 4), BoxField("color_type", "2", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, exifChunk))

        val summary = buildMediaSummary(root, tempFile())

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("PngCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("P900", cameraInfo.fields.first { it.label == "Model" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
    }

    @Test
    fun `a BMP-shaped tree produces Resolution and Format BMP, with no Color Space or Camera Info sections`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "-50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))
        val file = File.createTempFile("bmp-summary-test", ".bmp")
        file.deleteOnExit()
        file.writeBytes(ByteArray(500))

        val summary = buildMediaSummary(root, file)

        assertEquals(2, summary.sections.size)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("BMP", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("100", image.fields.first { it.label == "Width" }.value)
        assertEquals("50", image.fields.first { it.label == "Height" }.value)
        assertEquals(null, image.fields.find { it.label == "Color Space" })
    }

    @Test
    fun `an unrecognized video codec falls back to its raw box-type string under Format`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val s263 = BoxNode(type = "s263", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "352.0", 0, 2), BoxField("height", "288.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(s263))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "1", 0, 4), BoxField("width", "352.0", 0, 4), BoxField("height", "288.0", 0, 4)))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoTkhd, videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("s263", videoDetail.fields.first { it.label == "Format" }.value)
    }

    @Test
    fun `a GIF-shaped tree (LogicalScreenDescriptor as a direct root child) produces Width, Height, Format GIF, and Frame Count`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val imageDescriptor = BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, imageDescriptor))
        val file = File.createTempFile("gif-summary-test", ".gif")
        file.deleteOnExit()
        file.writeBytes(ByteArray(1000))

        val summary = buildMediaSummary(root, file)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("GIF", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("320", image.fields.first { it.label == "Width" }.value)
        assertEquals("240", image.fields.first { it.label == "Height" }.value)
        assertEquals("1", image.fields.first { it.label == "Frame Count" }.value)
        assertEquals(null, image.fields.find { it.label == "Loop Count" })
    }

    @Test
    fun `an animated GIF-shaped tree with a Netscape loop_count of 0 shows Frame Count and an Infinite Loop Count`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 2), BoxField("height", "80", 0, 2)),
        )
        val appExtension = BoxNode(
            type = "ApplicationExtension", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("application_identifier", "NETSCAPE2.0", 0, 11), BoxField("loop_count", "0", 0, 2)),
        )
        val frames = List(3) { BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0) }
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, appExtension) + frames)

        val summary = buildMediaSummary(root, tempFile())

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("3", image.fields.first { it.label == "Frame Count" }.value)
        assertEquals("Infinite", image.fields.first { it.label == "Loop Count" }.value)
    }

    @Test
    fun `a finite Netscape loop_count shows as its raw number, not Infinite`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 2), BoxField("height", "80", 0, 2)),
        )
        val appExtension = BoxNode(
            type = "ApplicationExtension", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("application_identifier", "NETSCAPE2.0", 0, 11), BoxField("loop_count", "5", 0, 2)),
        )
        val frames = List(2) { BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0) }
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, appExtension) + frames)

        val summary = buildMediaSummary(root, tempFile())

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("5", image.fields.first { it.label == "Loop Count" }.value)
    }

    @Test
    fun `a JPEG-shaped tree with a ThumbnailImage node populates MediaSummary#thumbnail with the exact bytes`() {
        val thumbnailBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val file = File.createTempFile("thumbnail-summary-test", ".jpg")
        file.deleteOnExit()
        file.writeBytes(ByteArray(20) + thumbnailBytes)
        val thumbnailOffset = 20L

        val thumbnailNode = BoxNode(type = "ThumbnailImage", offset = thumbnailOffset, headerSize = 0, size = thumbnailBytes.size.toLong())
        val ifd1 = BoxNode(type = "IFD1", offset = 0, headerSize = 0, size = 0, children = listOf(thumbnailNode))
        val ifd0 = BoxNode(type = "IFD0", offset = 0, headerSize = 0, size = 0)
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 4, size = 0, children = listOf(ifd0, ifd1))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), app1),
        )

        val summary = buildMediaSummary(root, file)

        assertTrue(summary.thumbnail != null)
        assertTrue(thumbnailBytes.contentEquals(summary.thumbnail!!))
    }

    @Test
    fun `an image tree with no ThumbnailImage node leaves MediaSummary#thumbnail null`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)),
        )
        val summary = buildMediaSummary(root, tempFile())
        assertEquals(null, summary.thumbnail)
    }

    @Test
    fun `mergeStreamCodecDetails appends fields onto the existing Video and Audio sections`() {
        val summary = MediaSummary(
            category = MediaCategory.VIDEO,
            sections = listOf(
                SummarySection("General", listOf(SummaryField("Duration", "0:00:03"))),
                SummarySection("Video", listOf(SummaryField("Format", "HEVC"), SummaryField("Width", "1752"))),
                SummarySection("Audio", listOf(SummaryField("Format", "AAC"))),
            ),
        )

        val merged = mergeStreamCodecDetails(
            summary,
            videoFields = listOf(SummaryField("Profile", "Main"), SummaryField("Bit Depth", "8 bit")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(3, merged.sections.size)
        assertEquals("General", merged.sections[0].title)
        assertEquals(listOf(SummaryField("Duration", "0:00:03")), merged.sections[0].fields)
        assertEquals("Video", merged.sections[1].title)
        assertEquals(
            listOf(
                SummaryField("Format", "HEVC"), SummaryField("Width", "1752"),
                SummaryField("Profile", "Main"), SummaryField("Bit Depth", "8 bit"),
            ),
            merged.sections[1].fields,
        )
        assertEquals("Audio", merged.sections[2].title)
        assertEquals(
            listOf(SummaryField("Format", "AAC"), SummaryField("Profile", "LC")),
            merged.sections[2].fields,
        )
    }

    @Test
    fun `mergeStreamCodecDetails leaves a summary with no Video or Audio section unchanged`() {
        val summary = MediaSummary(
            category = MediaCategory.VIDEO,
            sections = listOf(SummarySection("General", listOf(SummaryField("Duration", "0:00:03")))),
        )

        val merged = mergeStreamCodecDetails(
            summary,
            videoFields = listOf(SummaryField("Profile", "Main")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(summary, merged)
    }

    @Test
    fun `mergeStreamCodecDetails is a no-op for a non-VIDEO category summary even if it has a Video-titled section`() {
        val summary = MediaSummary(
            category = MediaCategory.IMAGE,
            sections = listOf(
                SummarySection("General", listOf(SummaryField("Format", "HEIC"))),
                SummarySection("Video", listOf(SummaryField("Format", "HEVC"), SummaryField("Width", "1752"))),
            ),
        )

        val merged = mergeStreamCodecDetails(
            summary,
            videoFields = listOf(SummaryField("Profile", "Main"), SummaryField("Bit Depth", "8 bit")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(summary, merged)
    }

    @Test
    fun `mergeStreamCodecDetailsIntoSections appends fields onto matching-titled sections directly`() {
        val sections = listOf(
            SummarySection("General", listOf(SummaryField("Format", "MOV"))),
            SummarySection("Video", listOf(SummaryField("Format", "HEVC"))),
            SummarySection("Audio", listOf(SummaryField("Format", "AAC"))),
        )

        val merged = mergeStreamCodecDetailsIntoSections(
            sections,
            videoFields = listOf(SummaryField("Profile", "Main")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(3, merged.size)
        assertEquals(listOf(SummaryField("Format", "MOV")), merged[0].fields)
        assertEquals(listOf(SummaryField("Format", "HEVC"), SummaryField("Profile", "Main")), merged[1].fields)
        assertEquals(listOf(SummaryField("Format", "AAC"), SummaryField("Profile", "LC")), merged[2].fields)
    }

    @Test
    fun `a JPEG with all-standard Huffman tables reports Huffman Tables as Standard`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dcLuminance = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "0", 0, 1),
                BoxField("bit_counts", "0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00", 0, 1),
                BoxField("codes_length_03", "01, 02, 03, 04, 05", 0, 5),
                BoxField("codes_length_04", "06", 0, 1),
                BoxField("codes_length_05", "07", 0, 1),
                BoxField("codes_length_06", "08", 0, 1),
                BoxField("codes_length_07", "09", 0, 1),
                BoxField("codes_length_08", "0A", 0, 1),
                BoxField("codes_length_09", "0B", 0, 1),
            ),
        )
        val dcChrominance = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "1", 0, 1),
                BoxField("bit_counts", "0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00, 01, 02", 0, 3),
                BoxField("codes_length_03", "03", 0, 1),
                BoxField("codes_length_04", "04", 0, 1),
                BoxField("codes_length_05", "05", 0, 1),
                BoxField("codes_length_06", "06", 0, 1),
                BoxField("codes_length_07", "07", 0, 1),
                BoxField("codes_length_08", "08", 0, 1),
                BoxField("codes_length_09", "09", 0, 1),
                BoxField("codes_length_10", "0A", 0, 1),
                BoxField("codes_length_11", "0B", 0, 1),
            ),
        )
        val dht = BoxNode(type = "DHT", offset = 0, headerSize = 0, size = 0, children = listOf(dcLuminance, dcChrominance))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dht),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Standard", jpegDetail.fields.first { it.label == "Huffman Tables" }.value)
    }

    @Test
    fun `a JPEG with one non-standard Huffman table reports Custom Optimized with the mismatched table labeled`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dcLuminanceStandard = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "0", 0, 1),
                BoxField("bit_counts", "0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00", 0, 1),
                BoxField("codes_length_03", "01, 02, 03, 04, 05", 0, 5),
                BoxField("codes_length_04", "06", 0, 1),
                BoxField("codes_length_05", "07", 0, 1),
                BoxField("codes_length_06", "08", 0, 1),
                BoxField("codes_length_07", "09", 0, 1),
                BoxField("codes_length_08", "0A", 0, 1),
                BoxField("codes_length_09", "0B", 0, 1),
            ),
        )
        val dcChrominanceCustom = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "1", 0, 1),
                BoxField("bit_counts", "0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00, 01, 02, 03, 04, 05, 06, 07, 08, 09, 0A, 0B", 0, 12),
            ),
        )
        val dht = BoxNode(type = "DHT", offset = 0, headerSize = 0, size = 0, children = listOf(dcLuminanceStandard, dcChrominanceCustom))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dht),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Custom/Optimized (differs: DC1)", jpegDetail.fields.first { it.label == "Huffman Tables" }.value)
    }

    @Test
    fun `a JPEG with no DHT omits the Huffman Tables field but still reports Encoding and Precision`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Huffman Tables" })
        assertEquals("Baseline DCT (Huffman)", jpegDetail.fields.first { it.label == "Encoding" }.value)
        assertEquals("8-bit", jpegDetail.fields.first { it.label == "Precision" }.value)
    }

    @Test
    fun `a SOF2 JPEG reports Encoding as Progressive DCT (Huffman)`() {
        val sof2 = BoxNode(
            type = "SOF2", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof2),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Progressive DCT (Huffman)", jpegDetail.fields.first { it.label == "Encoding" }.value)
    }

    @Test
    fun `Quality Estimate prefers the Luminance (destination_id 0) quantization table when both are present`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val chrominanceTable = BoxNode(
            type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("destination_id", "1 (Chrominance)", 0, 1),
                BoxField("quality_estimate", "~50%", 0, 65),
            ),
        )
        val luminanceTable = BoxNode(
            type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("destination_id", "0 (Luminance)", 0, 1),
                BoxField("quality_estimate", "~90%", 0, 65),
            ),
        )
        // Chrominance listed first in tree order to prove selection is by destination_id, not position.
        val dqt = BoxNode(type = "DQT", offset = 0, headerSize = 0, size = 0, children = listOf(chrominanceTable, luminanceTable))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dqt),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("~90%", jpegDetail.fields.first { it.label == "Quality Estimate" }.value)
    }

    @Test
    fun `APP14 color_transform 0 with 4 components reports Adobe Color Transform as CMYK`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "4", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "0", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("CMYK", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `APP14 color_transform 0 with 3 components reports Adobe Color Transform as RGB`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "0", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("RGB", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `APP14 color_transform 1 reports Adobe Color Transform as YCbCr`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "1", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("YCbCr", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `a JPEG with no APP14 omits the Adobe Color Transform field`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Adobe Color Transform" })
    }

    @Test
    fun `a JPEG with DRI and COM segments reports Restart Interval and Comment`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dri = BoxNode(
            type = "DRI", offset = 0, headerSize = 4, size = 6,
            fields = listOf(BoxField("restart_interval", "16", 0, 2)),
        )
        val com = BoxNode(
            type = "COM", offset = 0, headerSize = 4, size = 14,
            fields = listOf(BoxField("comment", "Created with GIMP", 0, 10)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dri, com),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("16 MCUs", jpegDetail.fields.first { it.label == "Restart Interval" }.value)
        assertEquals("Created with GIMP", jpegDetail.fields.first { it.label == "Comment" }.value)
    }

    @Test
    fun `a JPEG with no DRI or COM segments omits Restart Interval and Comment`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Restart Interval" })
        assertEquals(null, jpegDetail.fields.find { it.label == "Comment" })
    }

    @Test
    fun `a non-JPEG image (PNG) has no JPEG Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("width", "1920", 0, 4),
                BoxField("height", "1080", 0, 4),
                BoxField("color_type", "6", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "JPEG Detail" })
    }
}
