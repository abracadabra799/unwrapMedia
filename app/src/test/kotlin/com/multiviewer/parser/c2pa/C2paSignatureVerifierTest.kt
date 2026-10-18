package com.multiviewer.parser.c2pa

import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C2paSignatureVerifierTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())

    @Test
    fun `verifyC2paManifest verifies PS256 signature on C_jpg`() {
        val file = fixture("C.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)

        val report = verifyC2paManifest(active)
        assertTrue(report.signatureValid, "PS256 signature must be valid on C.jpg")
        assertEquals("PS256", report.algorithm)
        assertTrue(report.chainValid, "Certificate chain must be valid on C.jpg")
        assertEquals(2, report.chainLength)
        assertTrue(report.isTestCredential, "C2PA Test cert should be identified as test credential")
        assertEquals(VerificationState.UNTRUSTED, report.state)
    }

    @Test
    fun `verifyC2paManifest verifies ES256 signature on signed_heif`() {
        val file = fixture("signed.heif")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)

        val report = verifyC2paManifest(active)
        assertTrue(report.signatureValid, "ES256 signature must be valid on signed.heif")
        assertEquals("ES256", report.algorithm)
        assertTrue(report.chainValid)
    }

    @Test
    fun `verifyC2paManifest verifies ES256 signature on s26-camera_jpg`() {
        val file = fixture("s26-camera.jpg")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))
        val active = assertNotNull(store.active)

        val report = verifyC2paManifest(active)
        assertTrue(report.signatureValid, "Galaxy S26 Ultra camera signature must be valid")
        assertEquals("ES256", report.algorithm)
        assertTrue(report.chainValid)
    }

    @Test
    fun `verifyC2paManifest verifies ES256 signature on video1_mp4`() {
        val file = fixture("video1.mp4")
        val root = parseFile(file)
        val store = assertNotNull(readC2paManifestStore(file, root))

        for (manifest in store.manifests) {
            val report = verifyC2paManifest(manifest)
            assertTrue(report.signatureValid, "Video1 manifest signature must be valid for ${manifest.label}")
            assertEquals("ES256", report.algorithm)
        }
    }

    @Test
    fun `verdict is not VALID when any assertion hash mismatches`() {
        val ok = AssertionIntegrityResult(true, 2, 2, emptyList())
        val bad = AssertionIntegrityResult(false, 1, 2, listOf("self#jumbf=c2pa.assertions/c2pa.hash.data"))
        assertEquals(VerificationState.VALID, verdictFor(sigValid = true, chainValid = true, isTest = false, assertions = ok))
        assertEquals(VerificationState.INVALID, verdictFor(sigValid = true, chainValid = true, isTest = false, assertions = bad))
        assertEquals(VerificationState.INVALID, verdictFor(sigValid = true, chainValid = true, isTest = true, assertions = bad))
        assertEquals(VerificationState.UNTRUSTED, verdictFor(sigValid = true, chainValid = true, isTest = true, assertions = ok))
        assertEquals(VerificationState.INVALID, verdictFor(sigValid = false, chainValid = true, isTest = false, assertions = ok))
        val none = AssertionIntegrityResult(true, 0, 0, emptyList())
        assertEquals(VerificationState.INVALID, verdictFor(sigValid = true, chainValid = true, isTest = false, assertions = none))
    }

    private val signedFixtures = listOf("C.jpg", "CA.jpg", "s26-camera.jpg", "s26-motion-photo.heic", "signed.heif", "signed_small.webp", "video1.mp4")

    @Test
    fun `assertion hashes really match on every signed fixture`() {
        for (name in signedFixtures) {
            val file = fixture(name)
            val store = assertNotNull(readC2paManifestStore(file, parseFile(file)), name)
            for (manifest in store.manifests) {
                val r = verifyAssertionHashes(manifest)
                assertTrue(r.totalCount > 0, "$name ${manifest.label}: no hashed assertions")
                assertEquals(emptyList(), r.mismatchedUrls, "$name ${manifest.label}")
                assertEquals(r.totalCount, r.matchedCount, "$name ${manifest.label}")
                assertTrue(r.allMatch)
            }
        }
    }

    private fun activeOf(name: String): C2paManifest {
        val file = fixture(name)
        return assertNotNull(assertNotNull(readC2paManifestStore(file, parseFile(file))).active)
    }

    private fun C2paManifest.withAssertions(list: List<C2paAssertion>) =
        C2paManifest(label, claim, claimError, claimBytes, list, signature, signatureBytes, warnings)

    @Test
    fun `a tampered assertion box is reported as a mismatch and the verdict is INVALID`() {
        val m = activeOf("signed.heif")
        val victim = m.assertions.first { it.label.startsWith("c2pa.actions") }
        val tampered = victim.hashedBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val bad = m.withAssertions(m.assertions.map { if (it === victim) it.copy(hashedBytes = tampered) else it })

        val r = verifyAssertionHashes(bad)
        assertEquals(false, r.allMatch)
        assertEquals(r.totalCount - 1, r.matchedCount)
        assertTrue(r.mismatchedUrls.single().endsWith(victim.label), r.mismatchedUrls.toString())
        val report = verifyC2paManifest(bad)
        assertEquals(VerificationState.INVALID, report.state)
        assertTrue(report.signatureValid, "the claim signature itself is still valid")
        assertEquals("어설션 해시 불일치 (어설션 변조 감지)", report.summaryMessage)
        assertTrue(report.errors.any { it.contains(victim.label) }, report.errors.toString())
    }

    @Test
    fun `a referenced assertion missing from the store is not counted as matched`() {
        val m = activeOf("signed.heif")
        val victim = m.assertions.first { it.label.startsWith("c2pa.actions") }
        val r = verifyAssertionHashes(m.withAssertions(m.assertions.filterNot { it === victim }))
        assertEquals(false, r.allMatch)
        assertEquals(r.totalCount - 1, r.matchedCount)
        assertTrue(r.mismatchedUrls.single().endsWith(victim.label))
    }
}
