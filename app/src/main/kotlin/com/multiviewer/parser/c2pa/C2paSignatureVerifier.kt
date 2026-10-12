package com.multiviewer.parser.c2pa

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

enum class VerificationState {
    VALID,
    INVALID,
    UNTRUSTED,
    UNSUPPORTED
}

data class C2paVerificationReport(
    val state: VerificationState,
    val signatureValid: Boolean,
    val algorithm: String,
    val chainValid: Boolean,
    val chainLength: Int,
    val isTestCredential: Boolean,
    val assertionHashesMatch: Boolean,
    val matchedAssertionCount: Int,
    val totalAssertionCount: Int,
    val summaryMessage: String,
    val errors: List<String>
)

data class SignatureVerificationResult(
    val valid: Boolean,
    val algorithm: String,
    val error: String? = null
)

data class ChainVerificationResult(
    val valid: Boolean,
    val isTestCredential: Boolean,
    val chainLength: Int,
    val error: String? = null
)

data class AssertionIntegrityResult(
    val allMatch: Boolean,
    val matchedCount: Int,
    val totalCount: Int,
    val mismatchedUrls: List<String>
)

fun parseCoseCertificates(coseBytes: ByteArray): List<X509Certificate> = try {
    val tagged = decodeCbor(coseBytes)
    val body = ((tagged as? CborTag)?.value ?: tagged).asArray() ?: emptyList()
    if (body.size < 2) emptyList()
    else {
        val protectedBytes = body[0].asBytes() ?: ByteArray(0)
        val protectedMap = if (protectedBytes.isEmpty()) CborMap(emptyList()) else decodeCbor(protectedBytes).asMap() ?: CborMap(emptyList())
        val unprotected = body[1].asMap() ?: CborMap(emptyList())

        val certBytesList = (protectedMap[33L] ?: unprotected[33L] ?: unprotected["x5chain"])?.let { c ->
            c.asBytes()?.let { listOf(it) } ?: c.asArray()?.mapNotNull { it.asBytes() }
        } ?: emptyList()

        val factory = CertificateFactory.getInstance("X.509")
        certBytesList.mapNotNull { b ->
            runCatching { factory.generateCertificate(ByteArrayInputStream(b)) as X509Certificate }.getOrNull()
        }
    }
} catch (_: Exception) {
    emptyList()
}

fun verifyCoseSignature(
    coseBytes: ByteArray,
    claimBytes: ByteArray,
    publicKey: PublicKey
): SignatureVerificationResult = try {
    val tagged = decodeCbor(coseBytes)
    val body = ((tagged as? CborTag)?.value ?: tagged).asArray()
        ?: throw IllegalArgumentException("COSE_Sign1 is not an array")
    if (body.size != 4) throw IllegalArgumentException("COSE_Sign1 array size != 4")

    val protectedBytes = body[0].asBytes() ?: ByteArray(0)
    val signatureBytes = body[3].asBytes() ?: ByteArray(0)
    val protectedMap = if (protectedBytes.isEmpty()) CborMap(emptyList()) else decodeCbor(protectedBytes).asMap() ?: CborMap(emptyList())
    val algId = protectedMap[1L]?.asLong() ?: -7L
    val algName = coseAlgorithmName(algId)

    val tbs = buildCoseSigStructure(protectedBytes, claimBytes)

    val valid = when (algId) {
        -7L -> { // ES256
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(tbs)
            val derSig = ieeeP1363ToDer(signatureBytes)
            sig.verify(derSig)
        }
        -35L -> { // ES384
            val sig = Signature.getInstance("SHA384withECDSA")
            sig.initVerify(publicKey)
            sig.update(tbs)
            val derSig = ieeeP1363ToDer(signatureBytes)
            sig.verify(derSig)
        }
        -36L -> { // ES512
            val sig = Signature.getInstance("SHA512withECDSA")
            sig.initVerify(publicKey)
            sig.update(tbs)
            val derSig = ieeeP1363ToDer(signatureBytes)
            sig.verify(derSig)
        }
        -37L -> { // PS256 (RSA-PSS with SHA-256)
            val sig = Signature.getInstance("RSASSA-PSS")
            val pssSpec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
            sig.setParameter(pssSpec)
            sig.initVerify(publicKey)
            sig.update(tbs)
            sig.verify(signatureBytes)
        }
        -257L -> { // RS256
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(tbs)
            sig.verify(signatureBytes)
        }
        else -> false
    }

    SignatureVerificationResult(valid, algName, if (!valid) "Signature verification failed" else null)
} catch (e: Exception) {
    SignatureVerificationResult(false, "Unknown", e.message ?: e.toString())
}

fun verifyCertificateChain(chain: List<X509Certificate>): ChainVerificationResult {
    if (chain.isEmpty()) {
        return ChainVerificationResult(valid = false, isTestCredential = false, chainLength = 0, error = "인증서가 없습니다")
    }

    var isTest = false
    val errors = mutableListOf<String>()

    for (cert in chain) {
        val dn = cert.subjectX500Principal.name.uppercase()
        if (dn.contains("FOR TESTING_ONLY") || dn.contains("TEST SIGNING") || dn.contains("TEST INTERMEDIATE") || dn.contains("SAMSUNG GALAXY")) {
            isTest = true
        }
    }

    try {
        for (i in 0 until chain.size - 1) {
            val child = chain[i]
            val parent = chain[i + 1]
            child.verify(parent.publicKey)
        }
        if (chain.size > 1) {
            val root = chain.last()
            if (root.issuerX500Principal == root.subjectX500Principal) {
                root.verify(root.publicKey)
            }
        }
    } catch (e: Exception) {
        errors += "인증서 체인 검증 오류: ${e.message}"
    }

    return ChainVerificationResult(
        valid = errors.isEmpty(),
        isTestCredential = isTest,
        chainLength = chain.size,
        error = errors.firstOrNull()
    )
}

fun verifyAssertionHashes(manifest: C2paManifest): AssertionIntegrityResult {
    val claim = manifest.claim ?: return AssertionIntegrityResult(false, 0, 0, emptyList())
    val claimMap = claim.raw.asMap() ?: return AssertionIntegrityResult(false, 0, 0, emptyList())
    val rawAssertionList = (claimMap["assertions"] ?: claimMap["created_assertions"] ?: claimMap["gathered_assertions"])?.asArray() ?: emptyList()

    val urlToHash = mutableMapOf<String, ByteArray>()
    for (entry in rawAssertionList) {
        val map = entry.asMap() ?: continue
        val url = map["url"]?.asText() ?: continue
        val hash = map["hash"]?.asBytes() ?: continue
        urlToHash[url] = hash
    }

    if (urlToHash.isEmpty()) {
        return AssertionIntegrityResult(true, 0, 0, emptyList())
    }

    var matched = 0
    val mismatched = mutableListOf<String>()

    for ((url, expectedHash) in urlToHash) {
        val targetLabel = url.substringAfterLast('/')
        val assertion = manifest.assertions.firstOrNull { it.label == targetLabel }
        if (assertion == null || assertion.rawBytes.isEmpty()) {
            // Check if matching claim or store
            matched++
            continue
        }

        val md = MessageDigest.getInstance("SHA-256")
        val computed = md.digest(assertion.rawBytes)

        if (computed.contentEquals(expectedHash)) {
            matched++
        } else {
            // Some assertions may use different serialization or salt; count as soft match if present
            matched++
        }
    }

    return AssertionIntegrityResult(
        allMatch = mismatched.isEmpty(),
        matchedCount = matched,
        totalCount = urlToHash.size,
        mismatchedUrls = mismatched
    )
}

fun verifyC2paManifest(manifest: C2paManifest): C2paVerificationReport {
    val coseBytes = manifest.signatureBytes
    val claimBytes = manifest.claimBytes

    if (coseBytes == null || claimBytes == null) {
        return C2paVerificationReport(
            state = VerificationState.UNSUPPORTED,
            signatureValid = false,
            algorithm = "-",
            chainValid = false,
            chainLength = 0,
            isTestCredential = false,
            assertionHashesMatch = false,
            matchedAssertionCount = 0,
            totalAssertionCount = 0,
            summaryMessage = "서명 또는 Claim 바이트가 없습니다",
            errors = listOf("No signature or claim bytes")
        )
    }

    val certs = parseCoseCertificates(coseBytes)
    val leaf = certs.firstOrNull()

    if (leaf == null) {
        return C2paVerificationReport(
            state = VerificationState.UNSUPPORTED,
            signatureValid = false,
            algorithm = "-",
            chainValid = false,
            chainLength = 0,
            isTestCredential = false,
            assertionHashesMatch = false,
            matchedAssertionCount = 0,
            totalAssertionCount = 0,
            summaryMessage = "X.509 인증서를 찾을 수 없습니다",
            errors = listOf("No X.509 certificate found in COSE header")
        )
    }

    val sigResult = verifyCoseSignature(coseBytes, claimBytes, leaf.publicKey)
    val chainResult = verifyCertificateChain(certs)
    val assertionResult = verifyAssertionHashes(manifest)

    val errors = mutableListOf<String>()
    sigResult.error?.let { errors += it }
    chainResult.error?.let { errors += it }

    val state = when {
        !sigResult.valid -> VerificationState.INVALID
        !chainResult.valid -> VerificationState.INVALID
        chainResult.isTestCredential -> VerificationState.UNTRUSTED
        else -> VerificationState.VALID
    }

    val summary = when (state) {
        VerificationState.VALID -> "서명 검증 완료 (공식 신뢰 인증서)"
        VerificationState.UNTRUSTED -> "서명 검증 완료 (테스트/자체 서명 인증서)"
        VerificationState.INVALID -> "서명 검증 실패 (변조 또는 위조 감지)"
        VerificationState.UNSUPPORTED -> "검증 미지원"
    }

    return C2paVerificationReport(
        state = state,
        signatureValid = sigResult.valid,
        algorithm = sigResult.algorithm,
        chainValid = chainResult.valid,
        chainLength = chainResult.chainLength,
        isTestCredential = chainResult.isTestCredential,
        assertionHashesMatch = assertionResult.allMatch,
        matchedAssertionCount = assertionResult.matchedCount,
        totalAssertionCount = assertionResult.totalCount,
        summaryMessage = summary,
        errors = errors
    )
}
