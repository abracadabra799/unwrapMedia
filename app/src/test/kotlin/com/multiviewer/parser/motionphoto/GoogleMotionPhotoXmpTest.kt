package com.multiviewer.parser.motionphoto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleMotionPhotoXmpTest {
    @Test
    fun `reads camera properties by URI whatever the prefix and the directory in order`() {
        val xmp = parseGmpXmp(gmpXmp())
        assertEquals("1", xmp.camera["MotionPhoto"])
        assertEquals("0", xmp.camera["MotionPhotoPresentationTimestampUs"])
        assertTrue(xmp.wrongNamespace.isEmpty(), xmp.wrongNamespace.toString())
        val items = xmp.directory!!
        assertEquals(listOf("Primary", "MotionPhoto"), items.map { it.semantic })
        assertEquals(listOf(0, 1), items.map { it.index })
        assertEquals("100", items[1].length)
        assertNull(items[0].length)
    }

    @Test
    fun `reads element-form properties`() {
        val text = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:Camera="$GMP_CAMERA_NS" xmlns:Container="$GMP_CONTAINER_NS" xmlns:Item="$GMP_ITEM_NS">
<Camera:MotionPhoto>1</Camera:MotionPhoto>
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item><rdf:Description><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic></rdf:Description></Container:Item></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item><rdf:Description><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>42</Item:Length></rdf:Description></Container:Item></rdf:li>
</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val xmp = parseGmpXmp(text)
        assertEquals("1", xmp.camera["MotionPhoto"])
        assertEquals(listOf("Primary", "MotionPhoto"), xmp.directory!!.map { it.semantic })
        assertEquals("42", xmp.directory!![1].length)
    }

    @Test
    fun `camera property under a non-spec URI is reported and not read`() {
        val xmp = parseGmpXmp(gmpXmp(cameraNs = "http://ns.google.com/photos/1.0/camera"))
        assertNull(xmp.camera["MotionPhoto"])
        assertTrue(xmp.wrongNamespace.any { it.startsWith("MotionPhoto ") }, xmp.wrongNamespace.toString())
    }

    @Test
    fun `directory under a non-spec URI is reported and treated as absent`() {
        val xmp = parseGmpXmp(gmpXmp(directoryNs = "http://example.com/container/"))
        assertNull(xmp.directory)
        assertTrue(xmp.wrongNamespace.any { it.startsWith("Directory ") }, xmp.wrongNamespace.toString())
    }

    @Test
    fun `item property under a non-spec URI is reported`() {
        val xmp = parseGmpXmp(gmpXmp(itemNs = "http://example.com/item/"))
        assertTrue(xmp.wrongNamespace.any { it.startsWith("Item:Mime ") }, xmp.wrongNamespace.toString())
        assertNull(xmp.directory!![0].mime)
    }

    @Test
    fun `no directory gives null`() {
        assertNull(parseGmpXmp(gmpXmp(items = null)).directory)
    }

    @Test
    fun `malformed XML throws`() {
        assertFailsWith<Exception> { parseGmpXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken") }
    }
}
