package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        // 5, not 4: this fixture's video track (stsz sample_count=300, no stss) now also produces
        // a "Video Detail" section (Keyframe Interval + B-Frames, both unconditional once any
        // sample count is known).
        assertEquals(5, summary.sections.size)

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

        // 4, not 3: see the comment on the "full video tree" test above -- same cause.
        assertEquals(4, summary.sections.size)
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

    @Test
    fun `JPEG Detail describes only the first of two back-to-back JPEG streams at the root`() {
        // Mirrors a real Samsung Motion Photo file: the primary photo's own SOI..EOI stream is
        // immediately followed by a second, unrelated JPEG stream (e.g. an MPF secondary/preview
        // image) as further direct root children.
        val sof0Primary = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dqtPrimary = BoxNode(
            type = "DQT", offset = 0, headerSize = 0, size = 0,
            children = listOf(
                BoxNode(
                    type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
                    fields = listOf(
                        BoxField("destination_id", "0 (Luminance)", 0, 1),
                        BoxField("quality_estimate", "~90%", 0, 65),
                    ),
                ),
            ),
        )
        val sof0Secondary = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "120", 0, 2),
                BoxField("width", "160", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dqtSecondary = BoxNode(
            type = "DQT", offset = 0, headerSize = 0, size = 0,
            children = listOf(
                BoxNode(
                    type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
                    fields = listOf(
                        BoxField("destination_id", "0 (Luminance)", 0, 1),
                        BoxField("quality_estimate", "~10%", 0, 65),
                    ),
                ),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
                sof0Primary,
                dqtPrimary,
                BoxNode(type = "EOI", offset = 0, headerSize = 2, size = 2),
                BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
                sof0Secondary,
                dqtSecondary,
                BoxNode(type = "EOI", offset = 0, headerSize = 2, size = 2),
            ),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("~90%", jpegDetail.fields.first { it.label == "Quality Estimate" }.value)
        val image = summary.sections.first { it.title == "Image" }
        assertEquals("640", image.fields.first { it.label == "Width" }.value)
    }

    @Test
    fun `a PNG with full IHDR fields, pHYs, and tEXt chunks reports PNG Detail`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "640", 0, 4),
                BoxField("height", "480", 0, 4),
                BoxField("bit_depth", "8", 0, 1),
                BoxField("color_type", "6", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "1", 0, 1),
            ),
        )
        val phys = BoxNode(
            type = "pHYs", offset = 0, headerSize = 8, size = 21,
            fields = listOf(
                BoxField("pixels_per_unit_x", "2835", 0, 4),
                BoxField("pixels_per_unit_y", "2835", 0, 4),
                BoxField("unit_specifier", "meter", 0, 1),
            ),
        )
        val text = BoxNode(
            type = "tEXt", offset = 0, headerSize = 8, size = 20,
            fields = listOf(BoxField("keyword", "Software", 0, 8), BoxField("text", "GIMP", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, phys, text))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("8-bit", pngDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals("Deflate/Inflate", pngDetail.fields.first { it.label == "Compression Method" }.value)
        assertEquals("Adam7", pngDetail.fields.first { it.label == "Interlace" }.value)
        assertEquals("72 DPI", pngDetail.fields.first { it.label == "Pixel Density" }.value)
        assertEquals("GIMP", pngDetail.fields.first { it.label == "Software" }.value)
    }

    @Test
    fun `PNG pHYs with an unknown unit specifier reports raw pixels-per-unit instead of a DPI conversion`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "640", 0, 4),
                BoxField("height", "480", 0, 4),
                BoxField("bit_depth", "8", 0, 1),
                BoxField("color_type", "6", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "0", 0, 1),
            ),
        )
        val phys = BoxNode(
            type = "pHYs", offset = 0, headerSize = 8, size = 21,
            fields = listOf(
                BoxField("pixels_per_unit_x", "1", 0, 4),
                BoxField("pixels_per_unit_y", "1", 0, 4),
                BoxField("unit_specifier", "unknown", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, phys))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("1 x 1 px/unit", pngDetail.fields.first { it.label == "Pixel Density" }.value)
        assertEquals("None", pngDetail.fields.first { it.label == "Interlace" }.value)
    }

    @Test
    fun `a minimal PNG with no pHYs or tEXt still reports Bit Depth, Compression Method, and Interlace`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "100", 0, 4),
                BoxField("bit_depth", "1", 0, 1),
                BoxField("color_type", "0", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "0", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("1-bit", pngDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals(null, pngDetail.fields.find { it.label == "Pixel Density" })
        assertEquals(3, pngDetail.fields.size)
    }

    @Test
    fun `a non-PNG image (BMP) has no PNG Detail section`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "PNG Detail" })
    }

    @Test
    fun `a BMP with bit_count and compression fields reports BMP Detail`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 14)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 40,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "50", 0, 4),
                BoxField("bit_count", "24", 0, 2),
                BoxField("compression", "0", 0, 4),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        val bmpDetail = summary.sections.first { it.title == "BMP Detail" }
        assertEquals("24-bit", bmpDetail.fields.first { it.label == "Bit Count" }.value)
        assertEquals("None (BI_RGB)", bmpDetail.fields.first { it.label == "Compression" }.value)
    }

    @Test
    fun `an RLE8-compressed BMP labels Compression as RLE 8-bit (BI_RLE8)`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 14)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 40,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "50", 0, 4),
                BoxField("bit_count", "8", 0, 2),
                BoxField("compression", "1", 0, 4),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        val bmpDetail = summary.sections.first { it.title == "BMP Detail" }
        assertEquals("RLE 8-bit (BI_RLE8)", bmpDetail.fields.first { it.label == "Compression" }.value)
    }

    @Test
    fun `a BMP with no bit_count or compression fields has no BMP Detail section`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "-50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "BMP Detail" })
    }

    @Test
    fun `a non-BMP image (GIF) has no BMP Detail section`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "BMP Detail" })
    }

    @Test
    fun `a GIF with color resolution, a global color table, disposal, delay, and a comment reports GIF Detail`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 7,
            fields = listOf(
                BoxField("width", "320", 0, 2),
                BoxField("height", "240", 0, 2),
                BoxField("global_color_table_flag", "1", 0, 1),
                BoxField("color_resolution", "7", 0, 1),
                BoxField("global_color_table_size", "7", 0, 1),
            ),
        )
        val gce = BoxNode(
            type = "GraphicControlExtension", offset = 0, headerSize = 2, size = 8,
            fields = listOf(
                BoxField("disposal_method", "2", 0, 1),
                BoxField("delay_time", "50", 0, 2),
            ),
        )
        val comment = BoxNode(
            type = "CommentExtension", offset = 0, headerSize = 2, size = 10,
            fields = listOf(BoxField("comment", "Created with GIMP", 0, 18)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, gce, comment))

        val summary = buildMediaSummary(root, tempFile())

        val gifDetail = summary.sections.first { it.title == "GIF Detail" }
        assertEquals("8-bit", gifDetail.fields.first { it.label == "Color Resolution" }.value)
        assertEquals("Yes (256 colors)", gifDetail.fields.first { it.label == "Global Color Table" }.value)
        assertEquals("Restore to Background", gifDetail.fields.first { it.label == "Disposal Method" }.value)
        assertEquals("500 ms", gifDetail.fields.first { it.label == "Frame Delay" }.value)
        assertEquals("Created with GIMP", gifDetail.fields.first { it.label == "Comment" }.value)
    }

    @Test
    fun `a GIF with no global color table omits the Global Color Table, Disposal Method, and Comment fields`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 7,
            fields = listOf(
                BoxField("width", "320", 0, 2),
                BoxField("height", "240", 0, 2),
                BoxField("global_color_table_flag", "0", 0, 1),
                BoxField("color_resolution", "7", 0, 1),
                BoxField("global_color_table_size", "0", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd))

        val summary = buildMediaSummary(root, tempFile())

        val gifDetail = summary.sections.first { it.title == "GIF Detail" }
        assertEquals(null, gifDetail.fields.find { it.label == "Global Color Table" })
        assertEquals(null, gifDetail.fields.find { it.label == "Disposal Method" })
        assertEquals(null, gifDetail.fields.find { it.label == "Comment" })
    }

    @Test
    fun `a non-GIF image (PNG) has no GIF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "GIF Detail" })
    }

    @Test
    fun `a VP8X WebP reports Codec Extended and decodes alpha, animation, and ICC flags`() {
        val vp8x = BoxNode(
            type = "VP8X", offset = 0, headerSize = 8, size = 18,
            fields = listOf(BoxField("flags", "0x30", 0, 1), BoxField("width", "640", 0, 3), BoxField("height", "480", 0, 3)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8x))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Extended (VP8X)", webpDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("Yes", webpDetail.fields.first { it.label == "Has Alpha" }.value)
        assertEquals("No", webpDetail.fields.first { it.label == "Has Animation" }.value)
        assertEquals("Yes", webpDetail.fields.first { it.label == "Has ICC Profile" }.value)
    }

    @Test
    fun `a plain VP8 WebP (no VP8X) reports Codec Lossy with no alpha, animation, or ICC fields`() {
        val vp8 = BoxNode(
            type = "VP8 ", offset = 0, headerSize = 8, size = 10,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Lossy (VP8)", webpDetail.fields.first { it.label == "Codec" }.value)
        assertEquals(null, webpDetail.fields.find { it.label == "Has Alpha" })
    }

    @Test
    fun `a plain VP8L WebP reports Codec Lossless`() {
        val vp8l = BoxNode(
            type = "VP8L", offset = 0, headerSize = 8, size = 9,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8l))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Lossless (VP8L)", webpDetail.fields.first { it.label == "Codec" }.value)
    }

    @Test
    fun `a non-WebP image (PNG) has no WebP Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "WebP Detail" })
    }

    @Test
    fun `a TIFF with Orientation, Compression, PhotometricInterpretation, sample fields, and Resolution reports TIFF Detail`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("Orientation", "Horizontal (normal)", 0, 2),
                BoxField("Compression", "Uncompressed", 0, 2),
                BoxField("PhotometricInterpretation", "RGB", 0, 2),
                BoxField("BitsPerSample", "8, 8, 8", 0, 6),
                BoxField("SamplesPerPixel", "3", 0, 2),
                BoxField("XResolution", "300/1", 0, 8),
                BoxField("YResolution", "300/1", 0, 8),
                BoxField("ResolutionUnit", "inches", 0, 2),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        val tiffDetail = summary.sections.first { it.title == "TIFF Detail" }
        assertEquals("Horizontal (normal)", tiffDetail.fields.first { it.label == "Orientation" }.value)
        assertEquals("Uncompressed", tiffDetail.fields.first { it.label == "Compression" }.value)
        assertEquals("RGB", tiffDetail.fields.first { it.label == "Photometric Interpretation" }.value)
        assertEquals("8, 8, 8", tiffDetail.fields.first { it.label == "Bits Per Sample" }.value)
        assertEquals("3", tiffDetail.fields.first { it.label == "Samples Per Pixel" }.value)
        assertEquals("300/1 x 300/1 inches", tiffDetail.fields.first { it.label == "Resolution" }.value)
    }

    @Test
    fun `a TIFF with XResolution and YResolution but no ResolutionUnit omits the unit suffix`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("XResolution", "72/1", 0, 8),
                BoxField("YResolution", "72/1", 0, 8),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        val tiffDetail = summary.sections.first { it.title == "TIFF Detail" }
        assertEquals("72/1 x 72/1", tiffDetail.fields.first { it.label == "Resolution" }.value)
    }

    @Test
    fun `a TIFF with no Orientation, Compression, or Resolution fields has no TIFF Detail section`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("ImageWidth", "640", 0, 2), BoxField("ImageLength", "480", 0, 2), BoxField("Make", "TiffCam", 0, 7)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "TIFF Detail" })
    }

    @Test
    fun `a non-TIFF image (PNG) has no TIFF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "TIFF Detail" })
    }

    @Test
    fun `HEIC primary item properties (irot, imir, pixi) and an alpha auxC report HEIC-AVIF Detail`() {
        val irot = BoxNode(type = "irot", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("angle", "1", 0, 1)))
        val imir = BoxNode(type = "imir", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("axis", "0", 0, 1)))
        val pixi = BoxNode(type = "pixi", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("bits_per_channel", "8, 8, 8", 0, 3)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(irot, imir, pixi))
        val ipmaPrimaryItem = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("property_index", "1", 0, 1),
                BoxField("property_index", "2", 0, 1),
                BoxField("property_index", "3", 0, 1),
            ),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaPrimaryItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "1", 0, 4)))
        val auxC = BoxNode(
            type = "auxC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("aux_type", "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha", 0, 44)),
        )
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp, auxC))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        val heicDetail = summary.sections.first { it.title == "HEIC/AVIF Detail" }
        assertEquals("90°", heicDetail.fields.first { it.label == "Rotation" }.value)
        assertEquals("Horizontal Flip (좌우반전)", heicDetail.fields.first { it.label == "Mirror" }.value)
        assertEquals("8, 8, 8", heicDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals("Yes", heicDetail.fields.first { it.label == "Has Alpha Channel" }.value)
    }

    @Test
    fun `a HEIC with no irot, imir, pixi, or auxC has no HEIC-AVIF Detail section`() {
        val ispe = BoxNode(type = "ispe", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("image_width", "800", 0, 4), BoxField("image_height", "600", 0, 4)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "HEIC/AVIF Detail" })
    }

    @Test
    fun `an auxC with a non-alpha aux_type does not set Has Alpha Channel`() {
        val auxC = BoxNode(
            type = "auxC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("aux_type", "urn:mpeg:mpegB:cicp:systems:auxiliary:depth", 0, 44)),
        )
        val irot = BoxNode(type = "irot", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("angle", "0", 0, 1)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(irot))
        val ipmaPrimaryItem = BoxNode(type = "item_1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("property_index", "1", 0, 1)))
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaPrimaryItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "1", 0, 4)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp, auxC))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        val heicDetail = summary.sections.first { it.title == "HEIC/AVIF Detail" }
        assertEquals(null, heicDetail.fields.find { it.label == "Has Alpha Channel" })
        assertEquals("0°", heicDetail.fields.first { it.label == "Rotation" }.value)
    }

    @Test
    fun `a non-HEIC image (PNG) has no HEIC-AVIF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "HEIC/AVIF Detail" })
    }

    @Test
    fun `mvhd creation_time and modification_time appear in General when actually set`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val mvhd = BoxNode(
            type = "mvhd", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("timescale", "1000", 0, 4),
                BoxField("duration", "5000", 0, 4),
                BoxField("creation_time", "2026-01-15T10:30:00", 0, 4),
                BoxField("modification_time", "2026-01-16T09:00:00", 0, 4),
            ),
        )
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("2026-01-15T10:30:00", general.fields.first { it.label == "Creation Time" }.value)
        assertEquals("2026-01-16T09:00:00", general.fields.first { it.label == "Modification Time" }.value)
    }

    @Test
    fun `mvhd creation_time and modification_time of 0 (not set) are omitted from General`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val mvhd = BoxNode(
            type = "mvhd", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("timescale", "1000", 0, 4),
                BoxField("duration", "5000", 0, 4),
                BoxField("creation_time", "0 (not set)", 0, 4),
                BoxField("modification_time", "0 (not set)", 0, 4),
            ),
        )
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals(null, general.fields.find { it.label == "Creation Time" })
        assertEquals(null, general.fields.find { it.label == "Modification Time" })
    }

    @Test
    fun `Video Track Duration and Audio Track Duration reflect each track's own mdhd with millisecond precision`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))

        val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
        val audioMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "10500", 0, 4)))
        val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMdhd))
        val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioMdia))

        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "20000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak, audioTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:10.000", general.fields.first { it.label == "Video Track Duration" }.value)
        assertEquals("0:00:10.500", general.fields.first { it.label == "Audio Track Duration" }.value)
    }

    @Test
    fun `WebM General reports Creation Date, Muxing App, and Writing App when present`() {
        val dateUtc = BoxNode(type = "DateUTC", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "2026-01-15T10:30:01", 0, 8)))
        val muxingApp = BoxNode(type = "MuxingApp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "libwebm-0.3.0", 0, 13)))
        val writingApp = BoxNode(type = "WritingApp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "google/video-file", 0, 18)))
        val info = BoxNode(type = "Info", offset = 0, headerSize = 0, size = 0, children = listOf(dateUtc, muxingApp, writingApp))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(info))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("2026-01-15T10:30:01", general.fields.first { it.label == "Creation Date" }.value)
        assertEquals("libwebm-0.3.0", general.fields.first { it.label == "Muxing App" }.value)
        assertEquals("google/video-file", general.fields.first { it.label == "Writing App" }.value)
    }

    @Test
    fun `WebM General omits Creation Date, Muxing App, and Writing App when absent`() {
        val info = BoxNode(type = "Info", offset = 0, headerSize = 0, size = 0)
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(info))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals(null, general.fields.find { it.label == "Creation Date" })
        assertEquals(null, general.fields.find { it.label == "Muxing App" })
        assertEquals(null, general.fields.find { it.label == "Writing App" })
    }

    @Test
    fun `WebM General omits Creation Date when DateUTC is 0 (not set)`() {
        val dateUtc = BoxNode(type = "DateUTC", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "0 (not set)", 0, 8)))
        val info = BoxNode(type = "Info", offset = 0, headerSize = 0, size = 0, children = listOf(dateUtc))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(info))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals(null, general.fields.find { it.label == "Creation Date" })
    }

    @Test
    fun `Video and Audio sections report Handler Name and Language when present and not the und default`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4), BoxField("name", "VideoHandler", 0, 12)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("language", "eng", 0, 2)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))

        val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4), BoxField("name", "SoundHandler", 0, 12)))
        val audioMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("language", "kor", 0, 2)))
        val mp4a = BoxNode(type = "mp4a", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("channelcount", "2", 0, 2), BoxField("samplerate", "44100.0", 0, 4)))
        val audioStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(mp4a))
        val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMdhd, BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(audioStsd))))))
        val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioMdia))

        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak, audioTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("VideoHandler", videoDetail.fields.first { it.label == "Handler Name" }.value)
        assertEquals("eng", videoDetail.fields.first { it.label == "Language" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("SoundHandler", audioDetail.fields.first { it.label == "Handler Name" }.value)
        assertEquals("kor", audioDetail.fields.first { it.label == "Language" }.value)
    }

    @Test
    fun `Video section omits Handler Name when blank and Language when und`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4), BoxField("name", "", 0, 0)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("language", "und", 0, 2)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals(null, videoDetail.fields.find { it.label == "Handler Name" })
        assertEquals(null, videoDetail.fields.find { it.label == "Language" })
    }

    @Test
    fun `WebM Video reports a labeled Stereo Mode when non-zero`() {
        val codecId = BoxNode(type = "CodecID", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "V_VP9", 0, 5)))
        val stereoMode = BoxNode(type = "StereoMode", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val pixelWidth = BoxNode(type = "PixelWidth", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1920", 0, 2)))
        val pixelHeight = BoxNode(type = "PixelHeight", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1080", 0, 2)))
        val video = BoxNode(type = "Video", offset = 0, headerSize = 0, size = 0, children = listOf(stereoMode, pixelWidth, pixelHeight))
        val trackType = BoxNode(type = "TrackType", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val videoTrack = BoxNode(type = "TrackEntry", offset = 0, headerSize = 0, size = 0, children = listOf(trackType, codecId, video))
        val tracks = BoxNode(type = "Tracks", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrack))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(tracks))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("Side by Side (Left Eye First)", videoDetail.fields.first { it.label == "Stereo Mode" }.value)
    }

    @Test
    fun `WebM Video omits Stereo Mode when 0 (mono)`() {
        val codecId = BoxNode(type = "CodecID", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "V_VP9", 0, 5)))
        val stereoMode = BoxNode(type = "StereoMode", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "0", 0, 1)))
        val pixelWidth = BoxNode(type = "PixelWidth", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1920", 0, 2)))
        val pixelHeight = BoxNode(type = "PixelHeight", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1080", 0, 2)))
        val video = BoxNode(type = "Video", offset = 0, headerSize = 0, size = 0, children = listOf(stereoMode, pixelWidth, pixelHeight))
        val trackType = BoxNode(type = "TrackType", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val videoTrack = BoxNode(type = "TrackEntry", offset = 0, headerSize = 0, size = 0, children = listOf(trackType, codecId, video))
        val tracks = BoxNode(type = "Tracks", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrack))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(tracks))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals(null, videoDetail.fields.find { it.label == "Stereo Mode" })
    }

    @Test
    fun `Video Detail reports NAL Length Size and Parameter Sets from avcC`() {
        val avcC = BoxNode(
            type = "avcC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("length_size", "4", 0, 1),
                BoxField("num_sps", "1", 0, 1),
                BoxField("num_pps", "1", 0, 1),
            ),
        )
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, children = listOf(avcC))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd, videoStsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("4 bytes", videoDetail.fields.first { it.label == "NAL Length Size" }.value)
        assertEquals("1 SPS, 1 PPS", videoDetail.fields.first { it.label == "Parameter Sets" }.value)
    }

    @Test
    fun `Video Detail reports NAL Length Size and Parameter Sets from hvcC`() {
        val hvcC = BoxNode(
            type = "hvcC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("length_size", "4", 0, 1),
                BoxField("num_vps", "1", 0, 1),
                BoxField("num_sps", "1", 0, 1),
                BoxField("num_pps", "1", 0, 1),
            ),
        )
        val hvc1 = BoxNode(type = "hvc1", offset = 0, headerSize = 0, size = 0, children = listOf(hvcC))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(hvc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("4 bytes", videoDetail.fields.first { it.label == "NAL Length Size" }.value)
        assertEquals("1 VPS, 1 SPS, 1 PPS", videoDetail.fields.first { it.label == "Parameter Sets" }.value)
    }

    @Test
    fun `Video Detail describes an empty edit's offset using the movie timescale`() {
        val elst = BoxNode(
            type = "elst", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("segment_duration", "1000", 0, 4),
                BoxField("media_time", "-1", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
            ),
        )
        val edts = BoxNode(type = "edts", offset = 0, headerSize = 0, size = 0, children = listOf(elst))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(edts, videoMdia))
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        // segment_duration=1000 in a movie timescale of 1000 -> 1.000s offset
        assertEquals("1 edit (empty edit, 0:00:01.000 offset)", videoDetail.fields.first { it.label == "Edit List" }.value)
    }

    @Test
    fun `Video Detail reports a plain edit count when the first edit is not empty`() {
        val elst = BoxNode(
            type = "elst", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("segment_duration", "1000", 0, 4),
                BoxField("media_time", "0", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
                BoxField("segment_duration", "2000", 0, 4),
                BoxField("media_time", "1000", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
            ),
        )
        val edts = BoxNode(type = "edts", offset = 0, headerSize = 0, size = 0, children = listOf(elst))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(edts, videoMdia))
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("2 edits", videoDetail.fields.first { it.label == "Edit List" }.value)
    }

    @Test
    fun `Video Detail reports the actual Keyframe Interval when stss is present, and B-Frames Yes when ctts has entries`() {
        val stss = BoxNode(
            type = "stss", offset = 0, headerSize = 0, size = 0,
            table = TableData(columns = listOf("sample_number"), fieldWidths = listOf(4), entriesStart = 0, entryCount = 10),
        )
        val stsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val ctts = BoxNode(
            type = "ctts", offset = 0, headerSize = 0, size = 0,
            table = TableData(columns = listOf("sample_count", "sample_offset"), fieldWidths = listOf(4, 4), entriesStart = 0, entryCount = 5),
        )
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(stss, stsz, ctts))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("10 of 300 frames (every ~30 frames)", videoDetail.fields.first { it.label == "Keyframe Interval" }.value)
        assertEquals("Yes", videoDetail.fields.first { it.label == "B-Frames" }.value)
    }

    @Test
    fun `Video Detail reports No B-Frames and an All-frames Keyframe Interval when stss and ctts are both absent`() {
        val stsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(stsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("All frames (no separate sync sample table)", videoDetail.fields.first { it.label == "Keyframe Interval" }.value)
        assertEquals("No", videoDetail.fields.first { it.label == "B-Frames" }.value)
    }

    @Test
    fun `a non-video media type (JPEG) has no Video Detail section`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)),
        )

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "Video Detail" })
    }

    @Test
    fun `Apple photo summary projects Apple Device, Camera, Computational Photography, Depth, and Live Photo sections`() {
        val makerNote = BoxNode(
            type = "MakerNote", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("CameraType", "Back (0)", 0, 2),
                BoxField("ImageCaptureType", "ProRAW (3)", 0, 2),
                BoxField("HDRGain", "150/100 (1.50)", 0, 8),
                BoxField("HDRHeadroom", "200/100 (2.00)", 0, 8),
                BoxField("ContentIdentifier", "TEST-UUID-1234", 0, 16),
                BoxField("AFMeasuredDepth", "120", 0, 2),
                BoxField("AFConfidence", "3", 0, 2),
            ),
        )
        val exif = BoxNode(type = "Exif", offset = 0, headerSize = 0, size = 0, children = listOf(makerNote))
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("Make", "Apple", 0, 6),
                BoxField("Model", "iPhone 15 Pro", 0, 14),
                BoxField("Software", "17.4", 0, 4),
            ),
            children = listOf(exif),
        )
        val auxGainMap = BoxNode(
            type = "HDR Gain Map (Item 2)", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Role", "HDR_GAIN_MAP", 0, 0), BoxField("Resolution", "512x384", 0, 0)),
        )
        val auxImages = BoxNode(type = "Auxiliary Images", offset = 0, headerSize = 0, size = 0, children = listOf(auxGainMap))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0, auxImages))

        val summary = buildMediaSummary(root, tempFile())

        val appleDevice = summary.sections.find { it.title == "Apple Device" }
        assertNotNull(appleDevice)
        assertEquals("Apple", appleDevice.fields.find { it.label == "Make" }?.value)
        assertEquals("iPhone 15 Pro", appleDevice.fields.find { it.label == "Model" }?.value)

        val compPhoto = summary.sections.find { it.title == "Computational Photography" }
        assertNotNull(compPhoto)
        assertTrue(compPhoto.fields.any { it.label == "HDR Gain" })

        val depthSection = summary.sections.find { it.title == "Depth & Portrait" }
        assertNotNull(depthSection)
        assertEquals("120", depthSection.fields.find { it.label == "AF Measured Depth" }?.value)

        val livePhoto = summary.sections.find { it.title == "Live Photo" }
        assertNotNull(livePhoto)
        assertEquals("TEST-UUID-1234", livePhoto.fields.find { it.label == "Content Identifier" }?.value)
    }

    @Test
    fun `Apple video summary projects QuickTime metadata, Dolby Vision, and Timed Metadata`() {
        val appleMeta = BoxNode(
            type = "Apple QuickTime Metadata", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("Make (com.apple.quicktime.make)", "Apple", 0, 0),
                BoxField("Model (com.apple.quicktime.model)", "iPhone 15 Pro", 0, 0),
                BoxField("Content Identifier (com.apple.quicktime.content.identifier)", "TEST-UUID-5678", 0, 0),
                BoxField("Live Photo Still Image Time (com.apple.quicktime.live-photo.still-image-time)", "1000", 0, 0),
            ),
        )
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(appleMeta))

        val dvcC = BoxNode(
            type = "dvcC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("dv_profile", "8", 0, 2),
                BoxField("dv_level", "4", 0, 2),
                BoxField("dv_bl_signal_compatibility_id", "4 (HLG)", 0, 1),
            ),
        )
        val hvc1 = BoxNode(type = "hvc1", offset = 0, headerSize = 0, size = 0, children = listOf(dvcC))
        val stsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(hvc1))
        val stbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(stsd))
        val minf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(stbl))
        val hdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val mdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(hdlr, minf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mdia))

        val mebxHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "mebx", 0, 4)))
        val mebxMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(mebxHdlr))
        val timedMeta = BoxNode(type = "Timed Metadata", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("Total Samples", "30", 0, 0)))
        val mebxTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mebxMdia, timedMeta))

        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(meta, videoTrak, mebxTrak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))

        val summary = buildMediaSummary(root, tempFile())

        val appleDevice = summary.sections.find { it.title == "Apple Device" }
        assertNotNull(appleDevice)
        assertEquals("Apple", appleDevice.fields.find { it.label == "Make" }?.value)

        val livePhoto = summary.sections.find { it.title == "Live Photo" }
        assertNotNull(livePhoto)
        assertEquals("TEST-UUID-5678", livePhoto.fields.find { it.label == "Content Identifier" }?.value)

        val videoMetadata = summary.sections.find { it.title == "Video Metadata" }
        assertNotNull(videoMetadata)
        assertTrue(videoMetadata.fields.any { it.label == "Dolby Vision" })
        assertEquals("1", videoMetadata.fields.find { it.label == "Timed Metadata Tracks" }?.value)
    }
}
