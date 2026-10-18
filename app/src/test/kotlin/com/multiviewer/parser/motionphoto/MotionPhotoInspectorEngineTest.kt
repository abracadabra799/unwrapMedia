package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MotionPhotoInspectorEngineTest {

    private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long, majorBrand: String = "isom"): File {
        val bytes = ByteArray(totalSize.toInt())
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
        majorBrand.toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 8).toInt())
        val tmp = File.createTempFile("motion-photo-engine-test", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        return tmp
    }

    private fun googleDirectoryXmp(declaredLength: Long, mime: String = "video/mp4"): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoPresentationTimestampUs="500000">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="MotionPhoto" Item:Mime="$mime" Item:Length="$declaredLength"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    private fun rootWithXmp(xmpText: String, fileSize: Long): BoxNode = BoxNode(
        type = "root", offset = 0, headerSize = 0, size = fileSize,
        children = listOf(
            BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
            BoxNode(
                type = "APP1", offset = 2, headerSize = 4, size = xmpText.length.toLong(),
                fields = listOf(BoxField("xmp", xmpText, 2, xmpText.length.toLong())),
            ),
            BoxNode(type = "SOF0", offset = 100, headerSize = 2, size = 17),
            BoxNode(type = "SOS", offset = 120, headerSize = 2, size = 12),
            BoxNode(type = "EOI", offset = 300, headerSize = 2, size = 2),
        ),
    )

    private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private data class SefTestField(val marker: Int, val name: String, val data: ByteArray)

    private fun buildSefTrailer(fields: List<SefTestField>): ByteArray {
        val blockBytesList = fields.map { f ->
            val nameBytes = f.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val header = ByteArray(8)
            header.putUInt16LE(2, f.marker)
            header.putUInt32LE(4, nameBytes.size.toLong())
            header + nameBytes + f.data
        }
        val blocksTotalSize = blockBytesList.sumOf { it.size }

        val dirSize = 12 + fields.size * 12
        val dir = ByteArray(dirSize)
        dir[0] = 'S'.code.toByte(); dir[1] = 'E'.code.toByte(); dir[2] = 'F'.code.toByte(); dir[3] = 'H'.code.toByte()
        dir.putUInt32LE(8, fields.size.toLong())
        var remaining = blocksTotalSize.toLong()
        for ((idx, f) in fields.withIndex()) {
            val entryPos = 12 + idx * 12
            dir.putUInt16LE(entryPos + 2, f.marker)
            dir.putUInt32LE(entryPos + 4, remaining)
            dir.putUInt32LE(entryPos + 8, blockBytesList[idx].size.toLong())
            remaining -= blockBytesList[idx].size
        }

        val seft = ByteArray(8)
        seft.putUInt32LE(0, dirSize.toLong())
        seft[4] = 'S'.code.toByte(); seft[5] = 'E'.code.toByte(); seft[6] = 'F'.code.toByte(); seft[7] = 'T'.code.toByte()

        var result = ByteArray(0)
        for (b in blockBytesList) result += b
        result += dir
        result += seft
        return result
    }

    @Test
    fun `test real S26 HEIC motion photo inspection`() {
        val heicFile = File("app/src/test/resources/c2pa/s26-motion-photo.heic")
        if (!heicFile.exists()) return

        val root = parseFile(heicFile)
        val report = MotionPhotoInspectorEngine.inspect(heicFile, root)

        assertNotNull(report)
        assertTrue(report.formatLabel.contains("HEIC") || report.formatLabel.contains("mpvd"))
        assertEquals(CheckStatus.PASS, report.stillImageSection.status)
        assertTrue(report.allSections.size == 5)
    }

    @Test
    fun `test Google XMP offset matching`() {
        val declaredLength = 200L
        val totalSize = 1000L
        val file = tempFileWithFtypAt(800L, totalSize) // 1000 - 200 = 800
        val xmp = googleDirectoryXmp(declaredLength)
        val root = rootWithXmp(xmp, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val offsetItem = report.formatXmpSection.items.find { it.id == "xmp.offset_match" }
        assertNotNull(offsetItem)
        assertEquals(CheckStatus.PASS, offsetItem.status)
    }

    @Test
    fun `test Google XMP offset mismatch produces warning`() {
        val declaredLength = 200L
        val totalSize = 1000L
        val file = tempFileWithFtypAt(816L, totalSize) // 16 bytes off from 800
        val xmp = googleDirectoryXmp(declaredLength)
        val root = rootWithXmp(xmp, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val offsetItem = report.formatXmpSection.items.find { it.id == "xmp.offset_match" }
        assertNotNull(offsetItem)
        assertEquals(CheckStatus.WARN, offsetItem.status)
    }

    @Test
    fun `test SEF non-motion entries and overlap validation`() {
        val f1 = SefTestField(0x0A00, "MotionPhoto_Data", ByteArray(32) { 0 })
        val f2 = SefTestField(0x0A01, "MCC_Data", ByteArray(16) { 0 })
        val trailer = buildSefTrailer(listOf(f1, f2))

        val dummyPrefix = ByteArray(100)
        val fullBytes = dummyPrefix + trailer
        val temp = File.createTempFile("sef-test", ".jpg")
        temp.deleteOnExit()
        temp.writeBytes(fullBytes)

        val sefdNode = BoxNode(
            type = "sefd", offset = 100L, headerSize = 0, size = trailer.size.toLong(),
            children = listOf(
                BoxNode(type = "MotionPhoto_Data", offset = 100L, headerSize = 0, size = 32L),
                BoxNode(type = "MCC_Data", offset = 132L, headerSize = 0, size = 16L),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = fullBytes.size.toLong(), children = listOf(sefdNode))

        val report = MotionPhotoInspectorEngine.inspect(temp, root)
        val overlapItem = report.sefSection.items.find { it.id == "sef.overlap_check" }
        assertNotNull(overlapItem)
        assertEquals(CheckStatus.PASS, overlapItem.status)

        val nonMotionItem = report.sefSection.items.find { it.id == "sef.non_motion_entries" }
        assertNotNull(nonMotionItem)
        assertEquals(CheckStatus.PASS, nonMotionItem.status)
    }

    @Test
    fun `test Google XMP multi-item directory validation (Primary, MotionPhoto, GainMap)`() {
        val xmpWithGainMap = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1"
                    GCamera:MotionPhotoPresentationTimestampUs="250000">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="300" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="100"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val totalSize = 1000L
        val file = tempFileWithFtypAt(700L, totalSize) // 1000 - 300 = 700
        val root = rootWithXmp(xmpWithGainMap, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val xmpItems = report.formatXmpSection.items

        // Namespaces check
        val nsItem = xmpItems.find { it.id == "xmp.namespaces" }
        assertNotNull(nsItem)
        assertEquals(CheckStatus.PASS, nsItem.status)

        // GCamera flags
        val gcamItem = xmpItems.find { it.id == "xmp.gcamera_motion_photo" }
        assertNotNull(gcamItem)
        assertEquals(CheckStatus.PASS, gcamItem.status)

        // Directory overview: 3 items
        val dirOverview = xmpItems.find { it.id == "xmp.directory_overview" }
        assertNotNull(dirOverview)
        assertEquals(CheckStatus.PASS, dirOverview.status)
        assertTrue(dirOverview.actual?.contains("3개") == true)

        // Item #1: Primary
        val item1 = xmpItems.find { it.id == "xmp.item.1.primary" }
        assertNotNull(item1)
        assertEquals(CheckStatus.PASS, item1.status)

        // Item #2: MotionPhoto
        val item2 = xmpItems.find { it.id == "xmp.item.2.motion_photo" }
        assertNotNull(item2)
        assertEquals(CheckStatus.PASS, item2.status)

        // Item #3: GainMap
        val item3 = xmpItems.find { it.id == "xmp.item.3.GainMap" }
        assertNotNull(item3)
        assertEquals(CheckStatus.PASS, item3.status)

        // Cumulative length (300 + 100 = 400 < 1000)
        val cumulative = xmpItems.find { it.id == "xmp.secondary_total_length" }
        assertNotNull(cumulative)
        assertEquals(CheckStatus.PASS, cumulative.status)
    }

    @Test
    fun `test offset mismatch and invalid items provide actionable remediation guide`() {
        // Offset mismatch test
        val declaredLength = 200L
        val totalSize = 1000L
        val file = tempFileWithFtypAt(816L, totalSize) // 16 bytes off from 800
        val xmp = googleDirectoryXmp(declaredLength)
        val root = rootWithXmp(xmp, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val offsetItem = report.formatXmpSection.items.find { it.id == "xmp.offset_match" }
        assertNotNull(offsetItem)
        assertEquals(CheckStatus.WARN, offsetItem.status)
        assertNotNull(offsetItem.remediation)
        assertTrue(offsetItem.remediation!!.contains("Item:Length = file_size - ftyp_offset"))

        // Invalid Primary Item test (e.g. Semantic != Primary, Length != 0)
        val badPrimaryXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Secondary" Item:Mime="image/jpeg" Item:Length="500"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val badRoot = rootWithXmp(badPrimaryXmp, totalSize)
        val badReport = MotionPhotoInspectorEngine.inspect(file, badRoot)
        val primaryItem = badReport.formatXmpSection.items.find { it.id == "xmp.item.1.primary" }
        assertNotNull(primaryItem)
        assertEquals(CheckStatus.WARN, primaryItem.status)
        assertNotNull(primaryItem.remediation)
        assertTrue(primaryItem.remediation!!.contains("Item:Semantic=\"Primary\""))
    }

    @Test
    fun `test Google Motion Photo spec length and padding requirements for Primary, MotionPhoto, and GainMap`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="300" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="100"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val totalSize = 1000L
        val file = tempFileWithFtypAt(700L, totalSize)
        val root = rootWithXmp(xmp, totalSize)
        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val items = report.formatXmpSection.items

        // Primary: Length should not be present / omitted or 0 (PASS)
        val primLength = items.find { it.id == "xmp.item.1.primary.length" }
        assertNotNull(primLength)
        assertEquals(CheckStatus.PASS, primLength.status)
        assertTrue(primLength.expected?.contains("있으면 안 됨") == true)

        // Primary: Padding is optional (PASS)
        val primPadding = items.find { it.id == "xmp.item.1.primary.padding" }
        assertNotNull(primPadding)
        assertEquals(CheckStatus.PASS, primPadding.status)
        assertTrue(primPadding.expected?.contains("선택") == true)

        // MotionPhoto: Length is required (> 0) (PASS)
        val mpLength = items.find { it.id == "xmp.item.2.motion_photo.length" }
        assertNotNull(mpLength)
        assertEquals(CheckStatus.PASS, mpLength.status)
        assertTrue(mpLength.expected?.contains("필수") == true)

        // MotionPhoto: Padding is optional (PASS)
        val mpPadding = items.find { it.id == "xmp.item.2.motion_photo.padding" }
        assertNotNull(mpPadding)
        assertEquals(CheckStatus.PASS, mpPadding.status)
        assertTrue(mpPadding.expected?.contains("선택") == true)

        // GainMap: Length is required (> 0) (PASS)
        val gmLength = items.find { it.id == "xmp.item.3.GainMap.length" }
        assertNotNull(gmLength)
        assertEquals(CheckStatus.PASS, gmLength.status)
        assertTrue(gmLength.expected?.contains("필수") == true)

        // GainMap: Padding is optional (PASS)
        val gmPadding = items.find { it.id == "xmp.item.3.GainMap.padding" }
        assertNotNull(gmPadding)
        assertEquals(CheckStatus.PASS, gmPadding.status)
        assertTrue(gmPadding.expected?.contains("선택") == true)
    }

    @Test
    fun `test invalid primary length and missing motion photo length produces failures`() {
        // Primary has positive length (forbidden in spec), MotionPhoto has no length (missing)
        val badXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Length="400"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val totalSize = 1000L
        val file = tempFileWithFtypAt(700L, totalSize)
        val root = rootWithXmp(badXmp, totalSize)
        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val items = report.formatXmpSection.items

        // Primary length with positive value must produce WARN
        val primLength = items.find { it.id == "xmp.item.1.primary.length" }
        assertNotNull(primLength)
        assertEquals(CheckStatus.WARN, primLength.status)
        assertTrue(primLength.actual?.contains("400") == true)

        // MotionPhoto missing length must produce FAIL
        val mpLength = items.find { it.id == "xmp.item.2.motion_photo.length" }
        assertNotNull(mpLength)
        assertEquals(CheckStatus.FAIL, mpLength.status)
    }

    @Test
    fun `test real Galaxy JPEG motion photo Primary and MotionPhoto spec items`() {
        val file = File("/Users/dong.kim/Downloads/20260718_200439_motion.jpg")
        if (!file.exists()) return
        val root = parseFile(file)
        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val items = report.formatXmpSection.items

        // Primary: Length omitted (PASS)
        val primLength = items.find { it.id == "xmp.item.1.primary.length" }
        assertNotNull(primLength)
        assertEquals(CheckStatus.PASS, primLength.status)
        assertTrue(primLength.actual?.contains("생략") == true)

        // Primary: Padding = 0 (PASS)
        val primPadding = items.find { it.id == "xmp.item.1.primary.padding" }
        assertNotNull(primPadding)
        assertEquals(CheckStatus.PASS, primPadding.status)

        // MotionPhoto: Length = 3961157 (PASS)
        val mpLength = items.find { it.id == "xmp.item.2.motion_photo.length" }
        assertNotNull(mpLength)
        assertEquals(CheckStatus.PASS, mpLength.status)

        // GainMap status check item
        val gmStatus = items.find { it.id == "xmp.gainmap_spec_status" }
        assertNotNull(gmStatus)
    }

    // --- Cancellation: closing the inspector window must stop its ffmpeg/ffprobe work -----------------------

    private fun makeFifo(suffix: String): File {
        val fifo = File.createTempFile("mp-inspector-$suffix-", ".fifo")
        fifo.delete()
        ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor()
        fifo.deleteOnExit()
        return fifo
    }

    private fun awaitNewDescendant(before: Set<Long>, what: String): ProcessHandle {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val candidate = ProcessHandle.current().descendants()
                .filter { it.pid() !in before && it.info().command().orElse("").contains(what) }
                .findFirst()
            if (candidate.isPresent) return candidate.get()
            Thread.sleep(50)
        }
        fail("No new $what process appeared within 10s")
    }

    // Runs [work] on a thread blocked on a FIFO-fed ffmpeg/ffprobe, interrupts it once the process exists,
    // and returns how the work ended. The FIFO keeps the process blocked forever, so the outcome is
    // deterministic: either the interrupt propagates, or the work swallows it and returns normally.
    private fun interruptMidProcess(what: String, work: (File) -> Unit): String {
        val fifo = makeFifo(what)
        val before = ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()
        var outcome = "unfinished"
        val worker = Thread {
            outcome = try { work(fifo); "returned normally" } catch (_: InterruptedException) { "interrupted" }
        }.apply { isDaemon = true; start() }
        val process = awaitNewDescendant(before, what)
        worker.interrupt()
        worker.join(5_000)
        val deadline = System.currentTimeMillis() + 3_000
        while (process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val survived = process.isAlive
        if (survived) process.destroyForcibly()
        fifo.delete()
        assertTrue(!survived, "$what outlived the interrupted inspection")
        return outcome
    }

    @Test
    fun `decode check propagates the interrupt instead of swallowing it`() {
        val outcome = interruptMidProcess("ffmpeg") { MotionPhotoInspectorEngine.runDecodeCheck(it) }
        assertEquals("interrupted", outcome)
    }

    @Test
    fun `stream duration probe propagates the interrupt instead of swallowing it`() {
        val outcome = interruptMidProcess("ffprobe") { MotionPhotoInspectorEngine.probeStreamDurations(it) }
        assertEquals("interrupted", outcome)
    }

    // A real JPEG-shaped container (zero prefix) with a real ffmpeg-made MP4 appended and a Google XMP
    // directory pointing at it, so findEmbeddedVideo/extractEmbeddedVideo take their real paths.
    private fun googleMotionPhotoWithRealVideo(): Pair<File, BoxNode> {
        val mp4 = File.createTempFile("mp-inspector-clip-", ".mp4")
        mp4.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10:duration=1",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", mp4.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val video = mp4.readBytes()
        mp4.delete()
        val prefix = ByteArray(1000)
        val file = File.createTempFile("mp-inspector-photo-", ".jpg")
        file.deleteOnExit()
        file.writeBytes(prefix + video)
        val total = file.length()
        return file to rootWithXmp(googleDirectoryXmp(video.size.toLong()), total)
    }

    @Test
    fun `inspect stops with InterruptedException when its thread is interrupted`() {
        val (file, root) = googleMotionPhotoWithRealVideo()
        assertNotNull(com.multiviewer.parser.ByteReader.open(file).use { com.multiviewer.parser.findEmbeddedVideo(root, it) })
        Thread.currentThread().interrupt()
        val outcome = try {
            MotionPhotoInspectorEngine.inspect(file, root)
            "returned normally"
        } catch (_: InterruptedException) {
            "interrupted"
        } finally {
            Thread.interrupted()
        }
        assertEquals("interrupted", outcome)
    }
}
