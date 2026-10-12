package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class C2paPresentationTest {
    @Test
    fun `ingredient link resolves only exact local manifest references`() {
        val labels = listOf("urn:uuid:abc", "urn:uuid:def")
        assertEquals(0, c2paManifestIndex("self#jumbf=/c2pa/urn:uuid:abc", labels))
        assertEquals(1, c2paManifestIndex("self#jumbf=/c2pa/urn:uuid:def", labels))
        assertNull(c2paManifestIndex("https://example.test/urn:uuid:abc", labels))
        assertNull(c2paManifestIndex("self#jumbf=/c2pa/not-urn:uuid:abc", labels))
        assertNull(c2paManifestIndex(null, labels))
    }
}
