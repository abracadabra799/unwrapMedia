package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class CloseTabReleaseTest {
    private fun tmp(): File = File.createTempFile("close-tab", ".mp4").apply { writeBytes(ByteArray(4)); deleteOnExit() }

    private fun heavyTab(): TabState = TabState(tmp()).apply {
        val bmp = ImageBitmap(2, 2)
        imageForensic = ImageForensicData(bitmap = bmp, embeddedThumbnail = bmp)
        gainmapBitmap = bmp
        fullSizeFrameBitmap = bmp
        codecViewFrameBitmap = bmp
        gopFrames = emptyList()
        motionPhotoGopFrames = emptyList()
        audioWaveform = StoredAnalysis(AudioWaveformData(WaveformPeaks(0, 0, emptyList()), 1.0), null)
    }

    @Test fun closeTabClearsContentCheckAndHeavyFields() {
        val app = AppState()
        val tab = heavyTab()
        app.tabs.add(tab)
        app.openContentCheck(tab)

        app.closeTab(0)

        assertNull(app.contentCheckTab)
        assertNull(tab.imageForensic)
        assertNull(tab.gainmapBitmap)
        assertNull(tab.fullSizeFrameBitmap)
        assertNull(tab.codecViewFrameBitmap)
        assertNull(tab.gopFrames)
        assertNull(tab.motionPhotoGopFrames)
        assertNull(tab.audioWaveform)
    }

    @Test fun closingAnotherTabKeepsContentCheckOnItsOwnTab() {
        val app = AppState()
        val kept = heavyTab()
        app.tabs.add(kept)
        app.tabs.add(heavyTab())
        app.openContentCheck(kept)

        app.closeTab(1)

        assertSame(kept, app.contentCheckTab)
    }
}
