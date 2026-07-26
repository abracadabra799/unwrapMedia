package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldPanelTest {
    @Test
    fun `prettyPrintXmlOrRaw indents a flat XMP packet`() {
        val raw = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"

        val formatted = prettyPrintXmlOrRaw(raw)

        assertTrue(formatted.contains("\n"), "expected pretty-printed output to span multiple lines")
        assertTrue(formatted.lines().any { it.trimStart() != it }, "expected at least one indented line")
    }

    @Test
    fun `prettyPrintXmlOrRaw falls back to the original text for malformed XML`() {
        val raw = "not xml at all"

        assertEquals(raw, prettyPrintXmlOrRaw(raw))
    }

    @Test
    fun `prettyPrintXmlOrRaw blocks a DOCTYPE-based XXE payload instead of resolving it`() {
        val secretFile = kotlin.io.path.createTempFile().toFile()
        secretFile.deleteOnExit()
        secretFile.writeText("super-secret-value")
        val raw = """
            <?xml version="1.0"?>
            <!DOCTYPE x:xmpmeta [<!ENTITY xxe SYSTEM "file://${secretFile.absolutePath}">]>
            <x:xmpmeta>&xxe;</x:xmpmeta>
        """.trimIndent()

        val result = prettyPrintXmlOrRaw(raw)

        assertTrue(!result.contains("super-secret-value"), "DOCTYPE-declared external entity must not be resolved")
    }
}
