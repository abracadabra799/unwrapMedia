package com.multiviewer.parser.c2pa

import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C2paManifestTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())
    private fun store(name: String) = assertNotNull(fixture(name).let { readC2paManifestStore(it, parseFile(it)) })

    @Test
    fun `C jpg v1 claim, actions and PS256 signer`() {
        val s = store("C.jpg")
        val m = s.manifests.single()
        assertEquals("contentauth:urn:uuid:b2b1f7fa-b119-4de1-9c0d-c97fbea3f2c3", m.label)
        val claim = assertNotNull(m.claim)
        assertEquals(1, claim.version)
        assertEquals("C.jpg", claim.title)
        assertEquals("image/jpeg", claim.format)
        assertEquals("xmp:iid:22704d84-c37f-4733-a207-56c4c2e67b1a", claim.instanceId)
        assertEquals("make_test_images/0.33.1 c2pa-rs/0.33.1", claim.claimGenerator)
        assertEquals(listOf("make_test_images 0.33.1", "c2pa-rs 0.33.1"), claim.generatorInfo)
        assertEquals("self#jumbf=c2pa.signature", claim.signatureRef)
        assertEquals(4, claim.assertionRefs.size)
        assertEquals("sha256", claim.alg)
        assertEquals(
            listOf("c2pa.thumbnail.claim.jpeg", "stds.schema-org.CreativeWork", "c2pa.actions", "c2pa.hash.data"),
            m.assertions.map { it.label },
        )
        val thumb = assertIs<C2paAssertionContent.Thumbnail>(m.assertions[0].content)
        assertEquals("image/jpeg", thumb.mimeType)
        assertEquals(0xFF.toByte(), thumb.bytes[0])
        assertIs<C2paAssertionContent.Json>(m.assertions[1].content)
        val action = assertIs<C2paAssertionContent.Actions>(m.assertions[2].content).actions.single()
        assertEquals("c2pa.created", action.action)
        assertEquals("Make Test Images 0.33.1", action.softwareAgent)
        assertEquals("http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicMedia", action.digitalSourceType)
        assertEquals("{\"name\": \"gradient\"}", action.parameters)
        assertIs<C2paAssertionContent.Cbor>(m.assertions[3].content)
        val sig = assertNotNull(m.signature)
        assertEquals("PS256", sig.algorithm)
        assertEquals(2, sig.chainLength)
        assertTrue(sig.certificateSubject!!.startsWith("CN=C2PA Signer"))
        assertTrue(sig.certificateIssuer!!.startsWith("CN=Intermediate CA"))
        assertNull(sig.error)
        assertTrue(m.signatureBytes!!.isNotEmpty())
        assertTrue(m.claimBytes!!.isNotEmpty())
        assertEquals(s.manifests.last(), s.active)
    }

    @Test
    fun `CA jpg ingredient`() {
        val m = store("CA.jpg").active!!
        val ing = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
        assertEquals("A.jpg", ing.title)
        assertEquals("image/jpeg", ing.format)
        assertEquals("parentOf", ing.relationship)
        assertEquals("xmp.iid:813ee422-9736-4cdc-9be6-4e35ed8e41cb", ing.instanceId)
        assertEquals("self#jumbf=c2pa.assertions/c2pa.thumbnail.ingredient.jpeg", ing.thumbnailRef)
        assertNull(ing.manifestRef)
        val actions = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Actions)?.actions }
        assertEquals(listOf("c2pa.opened", "c2pa.color_adjustments"), actions.map { it.action })
    }

    @Test
    fun `video1 two manifests, unprotected text-key x5chain, ingredient manifest ref`() {
        val s = store("video1.mp4")
        assertEquals(
            listOf("urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b", "urn:uuid:8da8ffb6-3de4-430a-8924-5b58b561e647"),
            s.manifests.map { it.label },
        )
        val active = s.active!!
        assertEquals("TestApp c2patool/0.6.2 c2pa-rs/0.28.2", active.claim!!.claimGenerator)
        assertEquals(emptyList(), active.claim!!.generatorInfo) // CBOR null
        val ing = active.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
        assertEquals("self#jumbf=/c2pa/urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b", ing.manifestRef)
        assertEquals("ES256", active.signature!!.algorithm)
        assertEquals("CN=Bob,O=Media Publisher Company", s.manifests[0].signature!!.certificateSubject)
        assertEquals("CN=Media Publisher Company Intermediate CA,O=Media Publisher Company", s.manifests[0].signature!!.certificateIssuer)
        assertEquals("c2pa.hash.bmff", s.manifests[0].assertions.last().label)
        assertEquals("c2pa.hash.bmff.v2", active.assertions.last().label)
    }

    @Test
    fun `heif and webp v2 claims`() {
        for (name in listOf("signed.heif", "signed_small.webp")) {
            val m = store(name).active!!
            val claim = m.claim!!
            assertEquals(2, claim.version, name)
            assertEquals("c2pa.claim.v2", claim.label)
            assertEquals("fixture", claim.title)
            assertNull(claim.format)
            assertNull(claim.claimGenerator)
            assertEquals("c2patool 0.27.22", claim.generatorInfo.first())
            assertEquals(2, claim.assertionRefs.size) // created + gathered
            val action = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Actions)?.actions }.single()
            assertEquals("c2pa.created", action.action)
            assertEquals("http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture", action.digitalSourceType)
            assertEquals("ES256", m.signature!!.algorithm)
            assertTrue(m.signature!!.certificateSubject!!.startsWith("CN=C2PA Signer"))
        }
    }

    @Test
    fun `no manifest and alg names`() {
        val f = fixture("no_manifest.jpg")
        assertNull(readC2paManifestStore(f, parseFile(f)))
        assertEquals("EdDSA", coseAlgorithmName(-8))
        assertEquals("alg -999", coseAlgorithmName(-999))
    }

    @Test
    fun `corrupt claim CBOR becomes claimError`() {
        val f = fixture("C.jpg")
        val loc = com.multiviewer.parser.ByteReader.open(f).use { locateC2paStore(it, parseFile(f)) }!!
        val bytes = loc.bytes.copyOf()
        // the claim's cbor payload starts with a8 (map of 8); turn it into a reserved initial byte
        val claimCbor = parseJumbfBoxes(bytes).single().children.single().child("c2pa.claim")!!.firstContent("cbor")!!
        bytes[claimCbor.payloadStart] = 0xFC.toByte()
        val s = buildC2paManifestStore(C2paStoreLocation(loc.container, bytes, loc.offsetMap, loc.anchorOffsets, loc.warnings))
        val m = s.manifests.single()
        assertNull(m.claim)
        assertTrue(m.claimError!!.contains("reserved"))
        assertEquals(4, m.assertions.size) // the rest still parses
    }
    @Test
    fun `Galaxy S26 camera captures preserve raw claims and assertions`() {
        for ((name, title, format, hashLabel) in listOf(
            listOf("s26-motion-photo.heic", "20261012_131852.heic", "image/heic", "c2pa.hash.bmff.v2"),
            listOf("s26-camera.jpg", "20261012_131827.jpg", "image/jpeg", "c2pa.hash.data"),
        )) {
            val m = store(name).manifests.single()
            val c = assertNotNull(m.claim)
            assertEquals(1, c.version)
            assertEquals(title, c.title)
            assertEquals(format, c.format)
            assertEquals("Galaxy_S26_Ultra c2pa-rs/0.75.0", c.claimGenerator)
            assertEquals(listOf("Galaxy S26 Ultra", "c2pa-rs 0.75.0"), c.generatorInfo)
            assertEquals(listOf("c2pa.actions.v2", hashLabel), m.assertions.map { it.label })
            val a = assertIs<C2paAssertionContent.Actions>(m.assertions.first().content).actions.single()
            assertEquals("c2pa.created", a.action)
            assertEquals("Camera", a.softwareAgent)
            assertEquals("http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture", a.digitalSourceType)
            val sig = assertNotNull(m.signature)
            assertEquals("ES256", sig.algorithm)
            assertEquals(2, sig.chainLength)
            assertEquals("C=US,O=Samsung Galaxy,CN=Samsung Galaxy", sig.certificateSubject)
            assertNull(sig.error)
        }
        val heicHash = assertIs<C2paAssertionContent.Cbor>(store("s26-motion-photo.heic").active!!.assertions.last().content).value.asMap()!!
        assertEquals(listOf("/uuid", "/ftyp", "/mfra", "/sefd"), heicHash["exclusions"]!!.asArray()!!.map { it.asMap()!!["xpath"]!!.asText() })
        val jpgHash = assertIs<C2paAssertionContent.Cbor>(store("s26-camera.jpg").active!!.assertions.last().content).value.asMap()!!
        val exclusion = jpgHash["exclusions"]!!.asArray()!!.single().asMap()!!
        assertEquals(58149L, exclusion["start"]!!.asLong())
        assertEquals(14494L, exclusion["length"]!!.asLong())
    }
    private fun mutatedC(name: String, mutate: (ByteArray, JumbfBox) -> Unit): C2paManifestStore {
        val f = fixture("C.jpg")
        val loc = com.multiviewer.parser.ByteReader.open(f).use { locateC2paStore(it, parseFile(f)) }!!
        val bytes = loc.bytes.copyOf()
        val manifest = parseJumbfBoxes(bytes).single().children.single()
        val part = if (name == "c2pa.signature") manifest.child(name)!! else manifest.child("c2pa.assertions")!!.child(name)!!
        mutate(bytes, part.firstContent("cbor")!!)
        return buildC2paManifestStore(C2paStoreLocation(loc.container, bytes, loc.offsetMap, loc.anchorOffsets, loc.warnings))
    }

    @Test
    fun `malformed action shape is a failure rather than empty actions`() {
        val s = mutatedC("c2pa.actions") { bytes, cbor -> bytes[cbor.payloadStart] = 0x80.toByte() }
        assertIs<C2paAssertionContent.Failed>(s.active!!.assertions[2].content)
    }

    @Test
    fun `incorrect COSE tag reports error`() {
        val s = mutatedC("c2pa.signature") { bytes, cbor -> bytes[cbor.payloadStart] = 0xC0.toByte() }
        assertNotNull(s.active!!.signature!!.error)
    }

    @Test
    fun `semantic assertions retain diagnostic and failed assertions retain hex`() {
        val actions = store("C.jpg").active!!.assertions[2]
        assertTrue(actions.diagnostic.contains("digitalSourceType"))
        assertTrue(actions.rawBytes.isNotEmpty())
        val failed = mutatedC("c2pa.actions") { bytes, cbor -> bytes[cbor.payloadStart] = 0xFC.toByte() }.active!!.assertions[2]
        assertIs<C2paAssertionContent.Failed>(failed.content)
        assertEquals(0xFC.toByte(), failed.rawBytes.first())
    }
}
