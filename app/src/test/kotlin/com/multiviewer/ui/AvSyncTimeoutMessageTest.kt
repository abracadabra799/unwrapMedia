package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import kotlin.test.Test
import kotlin.test.assertTrue

class AvSyncTimeoutMessageTest {
    @Test fun followsTheWatchTimeoutInBothLanguages() {
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ProcessManager.DEFAULT_WATCH_TIMEOUT_MILLIS)
        val msg = avSyncTimeoutMessage()
        assertTrue("시간 제한(${minutes}분)" in msg, msg)
        assertTrue("after $minutes min" in msg, msg)
        val custom = avSyncTimeoutMessage(timeoutMillis = 45 * 60_000L)
        assertTrue("45분" in custom && "after 45 min" in custom, custom)
    }
}
