package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C2paTreeTest {
    private fun tree(name: String) = parseFile(File(javaClass.getResource("/c2pa/$name")!!.toURI()))
    private fun BoxNode.field(name: String) = fields.firstOrNull { it.name == name }?.value
    private fun BoxNode.jumbs(): List<BoxNode> = children.filter { it.type == "jumb" }

    @Test
    fun `JPEG APP11 carries fields and the JUMBF tree with file offsets`() {
        val root = tree("C.jpg")
        val app11 = root.children.first { it.type == "APP11" }
        assertEquals("JP", app11.field("CI"))
        assertEquals("529", app11.field("En"))
        assertEquals("1", app11.field("Z"))
        val store = app11.jumbs().single()
        assertEquals("c2pa", store.field("label"))
        assertEquals(32L, store.offset)
        assertEquals(45872L, store.size)
        assertEquals("jumd", store.children.first().type)
        val manifest = store.jumbs().single()
        assertEquals("c2ma", manifest.field("content_type"))
        val claim = manifest.jumbs().first { it.field("label") == "c2pa.claim" }
        val cbor = claim.children.first { it.type == "cbor" }
        assertTrue(cbor.summary!!.startsWith("dc:title, dc:format, instanceID"))
        assertTrue(hasC2paManifestStore(root))
    }

    @Test
    fun `split JPEG store warns on boundary-spanning boxes`() {
        val root = tree("CA.jpg")
        val app11s = root.children.filter { it.type == "APP11" }
        assertEquals(2, app11s.size)
        assertTrue(app11s[1].children.isEmpty()) // tree hangs under the first segment only
        val store = app11s[0].jumbs().single()
        assertTrue(store.warnings.any { it.contains("boundary") }) // the store itself spans both segments
        assertTrue(app11s[0].summary!!.contains("2 segment"))
    }

    @Test
    fun `BMFF uuid and WebP chunk`() {
        val mp4 = tree("video1.mp4")
        val uuid = mp4.children.first { it.type == "uuid" }
        assertEquals("d8fec3d6-1b0e-483c-9297-5828877ec481", uuid.field("usertype"))
        assertEquals("manifest", uuid.field("purpose"))
        assertEquals(2, uuid.jumbs().single().jumbs().size)
        val second = mp4.children.filter { it.type == "uuid" }[1]
        assertTrue(second.children.isEmpty()) // merkle uuid has no manifest store
        val webp = tree("signed_small.webp")
        val chunk = webp.children.first { it.type == "C2PA" }
        assertEquals("c2pa", chunk.jumbs().single().field("label"))
        assertEquals(50416L, chunk.jumbs().single().offset)
    }

    @Test
    fun `files without C2PA are unchanged`() {
        val root = tree("no_manifest.jpg")
        assertFalse(hasC2paManifestStore(root))
        assertTrue(root.children.none { it.type == "APP11" && it.children.isNotEmpty() })
    }

    @Test
    fun `heif store has v2 labels`() {
        val root = tree("signed.heif")
        val store = assertNotNull(findFirst(root) { it.type == "jumb" && it.fields.any { f -> f.name == "label" && f.value == "c2pa" } })
        val labels = mutableListOf<String>()
        fun walk(n: BoxNode) { n.field("label")?.let { labels += it }; n.children.forEach(::walk) }
        walk(store)
        assertTrue("c2pa.claim.v2" in labels)
        assertTrue("c2pa.hash.bmff.v3" in labels)
    }
    @Test
    fun `S26 original camera files expose C2PA store`() {
        val heic = tree("s26-motion-photo.heic")
        val uuid = heic.children.first { it.type == "uuid" }
        assertEquals(28L, uuid.offset)
        assertEquals(14764L, uuid.size)
        assertEquals("manifest", uuid.field("purpose"))
        assertEquals(73L, uuid.jumbs().single().offset)
        assertTrue(heic.children.any { it.type == "sefd" })
        assertTrue(hasC2paManifestStore(heic))
        val jpeg = tree("s26-camera.jpg")
        val segment = jpeg.children.first { it.type == "APP11" }
        assertEquals(58149L, segment.offset)
        assertEquals(58161L, segment.jumbs().single().offset)
        assertEquals(14482L, segment.jumbs().single().size)
        assertTrue(hasC2paManifestStore(jpeg))
    }

    @Test
    fun `split store hex span reaches last physical byte`() {
        val root = tree("CA.jpg")
        val store = root.children.first { it.type == "APP11" }.jumbs().single()
        assertEquals(117293L, store.offset + store.size)
    }
}
