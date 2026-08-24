package com.multiviewer.parser

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzerTest {
    @Test
    fun `analyze does not compute the primary bitmap or histogram -- that's decodePrimaryBitmapAndHistogram's job`() {
        // Regression guard: the primary Skia raster decode + histogram pass is real, measurable
        // work (a large JPEG's decode alone measured at ~45ms) -- analyze() must stay cheap so a
        // file becomes interactive (structure tree, hex view) without waiting on it. Callers run
        // decodePrimaryBitmapAndHistogram separately, off the synchronous open path.
        val file = File.createTempFile("image-analyzer-fast-path-test", ".jpg")
        file.deleteOnExit()
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "jpg", file)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length())

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals(null, forensic.bitmap)
        assertEquals(null, forensic.histogram)
    }

    @Test
    fun `decodePrimaryBitmapAndHistogram decodes a real JPEG and computes its histogram without re-rasterizing twice`() {
        // Regression guard for the calculateHistogram(Bitmap) refactor: it must read pixel data
        // from the already-rasterized primaryBitmap (via asSkiaBitmap()) instead of a second,
        // redundant Bitmap.makeFromImage() rasterization of the original Image. Uses a real,
        // ImageIO-encoded JPEG (not garbage bytes) so Skia actually decodes it and this path runs.
        val width = 64
        val height = 48
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width / 2, height)
        graphics.color = Color.BLUE
        graphics.fillRect(width / 2, 0, width / 2, height)
        graphics.dispose()

        val file = File.createTempFile("image-analyzer-histogram-test", ".jpg")
        file.deleteOnExit()
        ImageIO.write(image, "jpg", file)

        val (bitmap, histogram) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)

        assertTrue(bitmap != null, "Expected Skia to decode this real JPEG")
        assertTrue(histogram != null, "Expected a histogram for a successfully decoded image")
        assertEquals(256, histogram.r.size)
        assertEquals(256, histogram.b.size)
        // Half red, half blue: the red channel's histogram should have real mass away from bin 0,
        // and the blue channel's histogram should too -- proves real pixel data was read, not a
        // zeroed/garbage buffer from a broken bitmap handoff.
        assertTrue(histogram.r.drop(1).any { it > 0f }, "Expected non-trivial red channel data")
        assertTrue(histogram.b.drop(1).any { it > 0f }, "Expected non-trivial blue channel data")
    }

    @Test
    fun `hasThumbnailReference is true when iref has a thmb entry, even if that item's bytes are not JPEG`() {
        // File content is all zero bytes — the "thumbnail item" payload (at offset 40, length 150)
        // is non-JPEG (no 0xFF 0xD8 anywhere), so Strategy 1's magic-byte check will reject it,
        // and Strategies 2/3 have nothing to find either. hasThumbnailReference must still be true
        // because it reflects the iref/thmb *structure*, not decode success.
        val file = File.createTempFile("image-analyzer-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val thmb = BoxNode(
            type = "thmb", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("from_item_ID", "1", 0, 0), BoxField("to_item_ID[0]", "99", 0, 0)),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(thmb))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf, iref))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertTrue(forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }

    @Test
    fun `hasThumbnailReference is false when there is no iref box`() {
        val file = File.createTempFile("image-analyzer-no-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals(false, forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }

    @Test
    fun `does not fallback to decoding GainMap or auxiliary JPEG items when no thumbnail reference exists`() {
        // Create a dummy image file containing a real valid secondary JPEG (e.g. GainMap) at offset 500
        val secondaryJpeg = File.createTempFile("gainmap-secondary", ".jpg")
        secondaryJpeg.deleteOnExit()
        val dummyGainMap = BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY)
        ImageIO.write(dummyGainMap, "jpg", secondaryJpeg)
        val gainMapBytes = secondaryJpeg.readBytes()

        val mainFile = File.createTempFile("image-without-thumb-with-gainmap", ".jpg")
        mainFile.deleteOnExit()
        val dummyPrefix = ByteArray(500)
        mainFile.writeBytes(dummyPrefix + gainMapBytes)

        // Root has no IFD1 thumbnail and no thmb iref
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = mainFile.length())
        val forensic = ImageAnalyzer.analyze(mainFile, root)

        // Must NOT fallback to the GainMap JPEG payload
        assertEquals(null, forensic.embeddedThumbnail)
        assertEquals(false, forensic.hasThumbnailReference)
    }

    @Test
    fun `extracts orientation from IFD0 with translated Exif string`() {
        val file = File.createTempFile("orientation-test", ".jpg")
        file.deleteOnExit()
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Orientation", "Rotate 90 CW", 0, 0)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(ifd0))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals("90° 회전 (6)", forensic.orientation)
        assertEquals("90° 회전 (6)", forensic.thumbnailOrientation)
    }

    @Test
    fun `extracts orientation from IFD0 with numeric code string`() {
        val file = File.createTempFile("orientation-test", ".jpg")
        file.deleteOnExit()
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Orientation", "1", 0, 0)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(ifd0))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals("정상 (1)", forensic.orientation)
    }

    @Test
    fun `extracts separate thumbnail orientation when IFD1 specifies one`() {
        val file = File.createTempFile("orientation-test", ".jpg")
        file.deleteOnExit()
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Orientation", "Rotate 90 CW", 0, 0)),
        )
        val ifd1 = BoxNode(
            type = "IFD1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Orientation", "Horizontal (normal)", 0, 0)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(ifd0, ifd1))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals("90° 회전 (6)", forensic.orientation)
        assertEquals("정상 (1)", forensic.thumbnailOrientation)
    }

    @Test
    fun `extracts rotation from HEIF irot property when no Exif orientation exists`() {
        val file = File.createTempFile("heic-irot-test", ".heic")
        file.deleteOnExit()

        val irot = BoxNode(
            type = "irot", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("angle", "1", 0, 0)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(irot))
        val item1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 0)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(item1))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "1", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, ipco, ipma))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals("90° 회전", forensic.orientation)
    }

    @Test
    fun `orientSkiaImage rotates 90 CW and swaps width and height`() {
        val width = 100
        val height = 50
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val file = File.createTempFile("orient-skia-test", ".png")
        file.deleteOnExit()
        ImageIO.write(img, "png", file)
        val skiaImg = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())

        assertEquals(100, skiaImg.width)
        assertEquals(50, skiaImg.height)

        val rotated90 = ImageAnalyzer.orientSkiaImage(skiaImg, 6) // 90 CW
        assertEquals(50, rotated90.width)
        assertEquals(100, rotated90.height)

        val rotated180 = ImageAnalyzer.orientSkiaImage(skiaImg, 3) // 180
        assertEquals(100, rotated180.width)
        assertEquals(50, rotated180.height)

        val rotated270 = ImageAnalyzer.orientSkiaImage(skiaImg, 8) // 270 CW
        assertEquals(50, rotated270.width)
        assertEquals(100, rotated270.height)
    }

    @Test
    fun `formatResolutionWithOrientation formats rotated and normal dimensions with Raw tags correctly`() {
        // Normal (code 1)
        assertEquals("512x288 · 정상 (1)", com.multiviewer.ui.formatResolutionWithOrientation(512, 288, "정상 (1)", 1))

        // Rotated 90 CW (code 6) -> Display is 288x512, Raw was 512x288
        assertEquals("288x512 (Raw 512x288 · 90° 회전 (6))", com.multiviewer.ui.formatResolutionWithOrientation(288, 512, "90° 회전 (6)", 6))

        // Rotated 180 (code 3) -> Display is 512x288, Raw was 512x288
        assertEquals("512x288 (Raw 512x288 · 180° 회전 (3))", com.multiviewer.ui.formatResolutionWithOrientation(512, 288, "180° 회전 (3)", 3))

        // Rotated 270 CW (code 8) -> Display is 288x512, Raw was 512x288
        assertEquals("288x512 (Raw 512x288 · 270° 회전 (8))", com.multiviewer.ui.formatResolutionWithOrientation(288, 512, "270° 회전 (8)", 8))

        // No orientation metadata
        assertEquals("1920x1080", com.multiviewer.ui.formatResolutionWithOrientation(1920, 1080, null, null))
    }
}
