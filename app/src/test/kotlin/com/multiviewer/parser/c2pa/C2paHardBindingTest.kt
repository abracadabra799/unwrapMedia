package com.multiviewer.parser.c2pa

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C2paHardBindingTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `computeDataHash matches stored hash on C_jpg`() {
        val file = fixture("C.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseDataExclusions(cborContent)
        assertEquals(1, exclusions.size)
        assertEquals(20L, exclusions[0].start)
        assertEquals(45884L, exclusions[0].length)

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeDataHash(reader, exclusions, alg)
            assertEquals("81664d10e30740d8942df03fb4ee470f66b03b060c037b677d43c0a48afd72c9", hex(computed))
            assertEquals(hex(storedHash), hex(computed))
        }
    }

    @Test
    fun `computeDataHash matches stored hash on s26-camera_jpg`() {
        val file = fixture("s26-camera.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseDataExclusions(cborContent)
        assertEquals(1, exclusions.size)
        assertEquals(58149L, exclusions[0].start)
        assertEquals(14494L, exclusions[0].length)

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeDataHash(reader, exclusions, alg)
            assertEquals("881a9cd33819b11806c965f3c21e8220b2e56a4873b529b2ec2542e3c39e7bc4", hex(computed))
            assertEquals(hex(storedHash), hex(computed))
        }
    }

    @Test
    fun `computeDataHash matches stored hash on CA_jpg with multi-segment APP11`() {
        val file = fixture("CA.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseDataExclusions(cborContent)
        assertEquals(1, exclusions.size)
        assertEquals(20L, exclusions[0].start)
        assertEquals(117273L, exclusions[0].length)

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeDataHash(reader, exclusions, alg)
            assertEquals(hex(storedHash), hex(computed))
        }
    }

    @Test
    fun `computeDataHash matches stored hash on signed_small_webp`() {
        val file = fixture("signed_small.webp")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.data" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseDataExclusions(cborContent)
        assertEquals(1, exclusions.size)

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeDataHash(reader, exclusions, alg)
            assertEquals(hex(storedHash), hex(computed))
        }
    }

    @Test
    fun `computeBmffV3Hash matches stored hash on signed_heif`() {
        val file = fixture("signed.heif")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label == "c2pa.hash.bmff.v3" })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseBmffExclusions(cborContent)
        assertTrue(exclusions.any { it.xpath == "/uuid" })
        assertTrue(exclusions.any { it.xpath == "/ftyp" })

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeBmffV3Hash(reader, exclusions, alg)
            assertEquals(hex(storedHash), hex(computed))
        }
    }

    @Test
    fun `s26-motion-photo_heic exclusions include sefd and diagnose video mismatch`() {
        val file = fixture("s26-motion-photo.heic")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)
        val hashAssertion = assertNotNull(active.assertions.firstOrNull { it.label.startsWith("c2pa.hash.bmff") })
        val cborContent = assertNotNull((hashAssertion.content as? C2paAssertionContent.Cbor)?.value)
        val exclusions = parseBmffExclusions(cborContent)

        // Samsung S26 camera deliberately excludes /sefd so SEF metadata updates preserve C2PA
        assertTrue(exclusions.any { it.xpath == "/sefd" })
        assertTrue(exclusions.any { it.xpath == "/uuid" })
        assertTrue(exclusions.any { it.xpath == "/ftyp" })

        val storedHash = assertNotNull(cborContent.asMap()?.get("hash")?.asBytes())
        val alg = cborContent.asMap()?.get("alg")?.asText() ?: "sha256"

        ByteReader.open(file).use { reader ->
            val computed = computeBmffV2Hash(reader, exclusions, alg)
            // Due to appended motion photo video tracks (ftyp/mdat/moov), hash differs from still-only capture
            assertTrue(hex(storedHash) != hex(computed))
        }
    }
}

