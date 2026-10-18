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
    }
}
