package com.multiviewer.parser.c2pa

import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C2paCoverageMapTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())

    @Test
    fun `coverage map classifies JPEG segments on s26-camera_jpg`() {
        val file = fixture("s26-camera.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val dataExclusions = parseDataExclusions(cborContent)

        val items = buildStructureCoverage(root, dataExclusions, emptyList())
        assertTrue(items.isNotEmpty())

        val app11 = items.first { it.type == "APP11" }
        assertEquals(CoverageStatus.EXCLUDED, app11.status)

        val app1 = items.first { it.type == "APP1" }
        assertEquals(CoverageStatus.HASHED, app1.status)

        val impact = evaluateEditImpact(C2paContainer.JPEG, emptyList(), dataExclusions)
        assertFalse(impact.sefTrailerSafe)
        assertFalse(impact.motionPhotoVideoSafe)
        assertFalse(impact.xmpMetadataSafe)
    }

    @Test
    fun `coverage map and impact evaluation on s26-motion-photo_heic`() {
        val file = fixture("s26-motion-photo.heic")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label.startsWith("c2pa.hash.bmff") })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val bmffExclusions = parseBmffExclusions(cborContent)

        val items = buildStructureCoverage(root, emptyList(), bmffExclusions)
        assertTrue(items.isNotEmpty())

        val sefd = items.first { it.type == "sefd" }
        assertEquals(CoverageStatus.EXCLUDED, sefd.status)

        val uuid = items.first { it.type == "uuid" && it.name.contains("C2PA") }
        assertEquals(CoverageStatus.EXCLUDED, uuid.status)

        val mdat = items.first { it.type == "mdat" }
        assertEquals(CoverageStatus.HASHED, mdat.status)

        val impact = evaluateEditImpact(C2paContainer.BMFF, bmffExclusions, emptyList())
        assertTrue(impact.sefTrailerSafe)
        assertFalse(impact.motionPhotoVideoSafe)
        assertFalse(impact.xmpMetadataSafe)
    }

    @Test
    fun `coverage map on C_jpg`() {
        val file = fixture("C.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val dataExclusions = parseDataExclusions(cborContent)

        val items = buildStructureCoverage(root, dataExclusions, emptyList())
        val app11 = items.first { it.type == "APP11" }
        assertEquals(CoverageStatus.EXCLUDED, app11.status)

        val dqt = items.first { it.type == "DQT" }
        assertEquals(CoverageStatus.HASHED, dqt.status)
    }
}
