package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStateTest {
    // .mp4, not .bin: openFile() now rejects unsupported extensions before parsing at all (see
    // AppState.openFile's hard extension gate), so fixtures need a real supported extension even
    // when the byte content itself is a trivial placeholder.
    private fun tempFile(name: String): File {
        val tmp = File.createTempFile(name, ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(4))
        return tmp
    }

    // openFile() now analyzes files on a background thread (to keep the UI responsive — see
    // AppState.kt) and marshals results back via EventQueue.invokeLater. Tests that assert on
    // analysis results must wait for that to land instead of reading state immediately.
    private fun waitForLoad(tab: TabState, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (tab.isLoading) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for ${tab.file.name} to finish loading" }
            Thread.sleep(10)
        }
    }

    private fun waitForFrameAnalysis(tab: TabState, timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (tab.isAnalyzingFrames) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for frame analysis of ${tab.file.name}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `analyzeFrames populates gopFrames from a real synthetic video without blocking`() {
        val video = File.createTempFile("appstate-frame-analysis-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val appState = AppState()
        val tab = TabState(video)

        appState.analyzeFrames(tab)
        assertEquals(true, tab.isAnalyzingFrames)

        waitForFrameAnalysis(tab)

        assertEquals(false, tab.isAnalyzingFrames)
        assertEquals(20, tab.gopFrames?.size)
        assertEquals('I', tab.gopFrames?.first()?.type)
        video.delete()
    }

    @Test
    fun `openFile rejects a third distinct file and sets statusMessage`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-1")
        val file2 = tempFile("appstate-test-2")
        val file3 = tempFile("appstate-test-3")

        appState.openFile(file1)
        appState.openFile(file2)
        assertEquals(2, appState.tabs.size)
        assertEquals(null, appState.statusMessage)

        appState.openFile(file3)
        assertEquals(2, appState.tabs.size)
        assertEquals("You can only have 2 files open at a time.", appState.statusMessage)
    }

    @Test
    fun `openFile re-opening an already-open file switches to it without being rejected`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-a")
        val file2 = tempFile("appstate-test-b")

        appState.openFile(file1)
        appState.openFile(file2)
        appState.openFile(file1)

        assertEquals(2, appState.tabs.size)
        assertEquals(0, appState.selectedTabIndex)
        assertEquals(null, appState.statusMessage)
    }

    @Test
    fun `openFile populates embeddedVideo when the file contains a top-level mpvd box`() {
        // A minimal ISOBMFF file: a top-level ftyp box, followed by an mpvd box
        // whose payload is itself a single nested ftyp box (major_brand "isom").
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x18, 'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-motion-photo", ".mp4")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertEquals(null, tab.error)
        assertEquals(24L, tab.embeddedVideo?.start)
        assertEquals(40L, tab.embeddedVideo?.end)
        assertEquals("mp4", tab.embeddedVideo?.extension)
    }

    @Test
    fun `openFile leaves embeddedVideo null when the file has no embedded video`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-no-motion-photo", ".mp4")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertEquals(null, tab.error)
        assertEquals(null, tab.embeddedVideo)
    }

    @Test
    fun `openFile rejects an unsupported extension without creating a tab or parsing`() {
        val file = File.createTempFile("appstate-unsupported", ".xyz")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4))

        val appState = AppState()
        appState.openFile(file)

        assertTrue(appState.tabs.isEmpty())
        assertTrue(appState.openFileError?.contains(".xyz") == true)
    }

    @Test
    fun `openFile rejects a PNG whose header declares a resolution above the hard limit`() {
        // 8-byte PNG signature + a minimal IHDR chunk declaring an absurd 100000x100000
        // resolution -- header parsing alone is cheap (just reading a few structured fields), so
        // this needs no huge actual file to prove the hard-limit gate runs before any pixel decode
        // is attempted.
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) // PNG signature
        fun writeUInt32BE(value: Long) {
            out.write(((value shr 24) and 0xFF).toInt())
            out.write(((value shr 16) and 0xFF).toInt())
            out.write(((value shr 8) and 0xFF).toInt())
            out.write((value and 0xFF).toInt())
        }
        writeUInt32BE(13) // IHDR chunk length
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        writeUInt32BE(100_000) // width
        writeUInt32BE(100_000) // height
        out.write(byteArrayOf(8, 2, 0, 0, 0)) // bit depth, color type, compression, filter, interlace
        writeUInt32BE(0) // CRC (not validated by the parser)

        val file = File.createTempFile("appstate-oversized", ".png")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())

        val appState = AppState()
        appState.openFile(file)

        val deadline = System.currentTimeMillis() + 5000
        while (appState.openFileError == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue(appState.tabs.isEmpty())
        assertTrue(appState.openFileError?.contains("해상도가 지원 한도를 초과") == true)
    }

    @Test
    fun `closeTab on the last remaining tab returns to an empty tab list`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("dummy.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(0)

        assertTrue(appState.tabs.isEmpty())
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the selected tab selects the tab that slides into its position`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab before the selected tab shifts the selection index down`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 2

        appState.closeTab(0)

        assertEquals(listOf("b.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab after the selected tab leaves the selection index unchanged`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(2)

        assertEquals(listOf("a.bin", "b.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the last tab when it is selected selects the new last tab`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `openFile on an undecodable IMAGE-type file sets isDecodingFallback before the ffmpeg callback resolves`() {
        val file = File.createTempFile("appstate-heic-fallback-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will return null

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        // The background analysis thread's EventQueue.invokeLater callback sets isDecodingFallback
        // = true and only *afterward* kicks off the separate ffmpeg decode thread — so immediately
        // after isLoading flips false, the fallback flag must already be set and the decode must
        // not have resolved yet (it needs to spawn a process, which takes longer than this check).
        assertEquals(MediaType.IMAGE, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(true, tab.imageForensic?.isDecodingFallback)
    }

    @Test
    fun `openFile on a VIDEO-type file never sets isDecodingFallback, even though its Skia decode also fails`() {
        val file = File.createTempFile("appstate-video-no-fallback-test", ".mp4")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will also return null here

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertEquals(MediaType.VIDEO, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(false, tab.imageForensic?.isDecodingFallback)
    }

    @Test
    fun `closing a tab while its background decode is still in flight does not throw`() {
        // Reproduction for a reported crash: openFile() kicks off parsing and (for images) a
        // second async decode thread that both eventually mutate `tab` via
        // EventQueue.invokeLater -- closeTab() only removes the tab from appState.tabs, with no
        // mechanism to cancel or even know about that in-flight work. This opens a real (not
        // garbage-byte) JPEG large enough that decode takes measurable time, closes the tab
        // immediately (before isLoading even flips), and asserts nothing throws on any thread
        // within the window those callbacks would fire in.
        val caughtThrowables = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> caughtThrowables.add(throwable) }
        try {
            val file = File.createTempFile("appstate-close-while-decoding-test", ".jpg")
            file.deleteOnExit()
            val image = java.awt.image.BufferedImage(4000, 3000, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color.RED
            graphics.fillRect(0, 0, 4000, 3000)
            graphics.dispose()
            javax.imageio.ImageIO.write(image, "jpg", file)

            val appState = AppState()
            appState.openFile(file)
            assertEquals(1, appState.tabs.size)
            appState.closeTab(0)
            assertTrue(appState.tabs.isEmpty())

            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            file.delete()
            assertTrue(
                caughtThrowables.isEmpty(),
                "Expected no uncaught exceptions from in-flight decode work after closing the tab, got: $caughtThrowables",
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `openFile makes a video tab interactive before per-stream codec details arrive, then fills them in`() {
        // Regression test: probeStreamDetails (a second ffprobe process call, for profile/level/
        // bit rate/etc.) used to run synchronously before isLoading flipped false, adding a whole
        // extra process spawn to "how long until this video tab is usable" -- deferred to run
        // after isLoading = false instead, same pattern as the async image bitmap decode.
        val video = File.createTempFile("appstate-video-codec-details-test-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=5",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(video)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        // Already interactive: structure and a base summary exist without waiting on the second
        // ffprobe call.
        assertEquals(MediaType.VIDEO, tab.type)
        assertTrue(tab.mediaSummary != null)

        val deadline = System.currentTimeMillis() + 5000
        var videoSection = tab.mediaSummary?.sections?.find { it.title == "Video" }
        while (videoSection?.fields?.none { it.label == "Bit Rate" } != false && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            videoSection = tab.mediaSummary?.sections?.find { it.title == "Video" }
        }

        assertTrue(
            videoSection?.fields?.any { it.label == "Bit Rate" } == true,
            "Expected codec details (e.g. Bit Rate) to be merged into the Video section eventually, got: $videoSection",
        )
        video.delete()
    }

    @Test
    fun `openFile opens a real M4A as MediaType_AUDIO with a populated Audio section`() {
        // M4A is an MP4-family container (same box structure as mp4/mov/m4v), so this exercises
        // the same generic box walker + MediaSummaryBuilder path already used for video -- no new
        // parser involved, only new extension routing (see AppState.kt's AUDIO_EXTENSIONS).
        val audio = File.createTempFile("appstate-m4a-test-", ".m4a")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "aac", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Format" } == true, "Expected an Audio section with a Format field, got: $audioSection")
        assertEquals(true, tab.mediaSummary?.sections?.none { it.title == "Video" })
        audio.delete()
    }

    @Test
    fun `openFile opens a real WAV as MediaType_AUDIO with a populated Audio section`() {
        // WAV's root "RIFF" node uses the exact same node type as WebP's own RIFF-prefixed root
        // (see WavWalker/WebpWalker) -- this test guards against detectCategory misclassifying it.
        val audio = File.createTempFile("appstate-wav-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "WAV" } == true, "Expected General/Format=WAV, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Format" && it.value == "PCM" } == true, "Expected Audio/Format=PCM, got: $audioSection")
        assertTrue(audioSection?.fields?.any { it.label == "Bit Depth" && it.value == "16-bit" } == true, "Expected Audio/Bit Depth=16-bit, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real MP3 as MediaType_AUDIO with ID3v2 tags and audio frame details`() {
        val audio = File.createTempFile("appstate-mp3-test-", ".mp3")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "libmp3lame", "-metadata", "title=Test Title", "-metadata", "artist=Test Artist",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "MP3" } == true, "Expected General/Format=MP3, got: $generalSection")
        assertTrue(generalSection?.fields?.any { it.label == "Title" && it.value == "Test Title" } == true, "Expected General/Title=Test Title, got: $generalSection")
        assertTrue(generalSection?.fields?.any { it.label == "Artist" && it.value == "Test Artist" } == true, "Expected General/Artist=Test Artist, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Bit Rate" } == true, "Expected an Audio/Bit Rate field, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real FLAC as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-flac-test-", ".flac")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "flac", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "FLAC" } == true, "Expected General/Format=FLAC, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real OGG (Vorbis) as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-ogg-test-", ".ogg")
        audio.deleteOnExit()
        // This machine's ffmpeg build has no libvorbis -- only the native "vorbis" encoder, which
        // is marked experimental (needs -strict -2) and only supports 2-channel output (needs
        // -ac 2 even though the source is mono).
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "2", "-c:a", "vorbis", "-strict", "-2", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "Vorbis" } == true, "Expected General/Format=Vorbis, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real Opus as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-opus-test-", ".opus")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "libopus", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "Opus" } == true, "Expected General/Format=Opus, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" && it.value == "48000 Hz" } == true, "Expected Audio/Sampling Rate=48000 Hz, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real AIFF as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-aiff-test-", ".aiff")
        audio.deleteOnExit()
        // No explicit -c:a needed -- plain ffmpeg -i produces a valid AIFF (pcm_s16be) on this machine.
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "AIFF" } == true, "Expected General/Format=AIFF, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile classifies a real webm file as MediaType VIDEO`() {
        val video = File.createTempFile("appstate-webm-test-", ".webm")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            "-c:v", "libvpx", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val appState = AppState()
        appState.openFile(video)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertEquals(null, tab.error)
        assertEquals(MediaType.VIDEO, tab.type)
        video.delete()
    }
}
