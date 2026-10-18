package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredAnalysisTest {
    private fun tmp(): File = File.createTempFile("stored", ".bin").apply { writeBytes(ByteArray(10)); deleteOnExit() }

    @Test fun returnsValueWhileFileUnchanged() {
        val f = tmp()
        assertEquals("v", StoredAnalysis("v", ContentFileIdentity.capture(f)).currentFor(f))
    }

    @Test fun nullAfterFileChanges() {
        val f = tmp()
        val s = StoredAnalysis("v", ContentFileIdentity.capture(f))
        f.writeBytes(ByteArray(25))
        assertNull(s.currentFor(f))
    }

    @Test fun nullWithoutIdentity() {
        assertNull(StoredAnalysis("v", null).currentFor(tmp()))
    }

    @Test fun nullOnceTheValueIsReclaimed() {
        val f = tmp()
        val s = StoredAnalysis("v", ContentFileIdentity.capture(f))
        assertEquals("v", s.value)
        s.simulateReclaimForTest()
        assertNull(s.currentFor(f))
        assertNull(s.value)
        kotlin.test.assertTrue(s.isReclaimed)
    }

    @Test fun holdsItsValueSoftly() {
        val s = StoredAnalysis("v", null)
        kotlin.test.assertFalse(s.isReclaimed)
        kotlin.test.assertTrue(s.holdsSoftlyForTest())
    }
}
