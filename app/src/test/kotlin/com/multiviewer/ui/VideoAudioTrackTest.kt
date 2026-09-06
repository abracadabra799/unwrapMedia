package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoAudioTrackTest {

    @Test
    fun `frameSyncAction waits when the frame is ahead of the audio clock`() {
        assertEquals(FrameAction.WaitThenDeliver(40), frameSyncAction(frameStartSeconds = 1.04, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction caps the wait at 500ms`() {
        assertEquals(FrameAction.WaitThenDeliver(500), frameSyncAction(frameStartSeconds = 1.9, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction delivers immediately when within tolerance behind`() {
        assertEquals(FrameAction.Deliver, frameSyncAction(frameStartSeconds = 0.95, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction drops when more than 100ms behind the audio clock`() {
        assertEquals(FrameAction.Drop, frameSyncAction(frameStartSeconds = 0.84, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction at exactly minus 100ms still delivers`() {
        assertEquals(FrameAction.Deliver, frameSyncAction(frameStartSeconds = 0.9, audioClockSeconds = 1.0))
    }
}
