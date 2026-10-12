# C2PA COSE Signature & X.509 Chain Verification (Phase 2c) — Design Spec

**Goal:** Implement full cryptographic verification of C2PA COSE_Sign1 signatures, X.509 certificate chains, and RFC 3161 timestamps using **JDK crypto APIs only** (no external cryptographic dependencies), surfacing verification results in the C2PA Credentials window.

**Context:** Roadmap item 2c (2026-10-12 benchmark). Phase 2a parsed and displayed stores, and Phase 2b recomputed hard bindings. In Phase 2c, we complete the C2PA verification triad:
1. Hard Binding: asset hash matches claim assertion (Phase 2b ✅)
2. Assertion Integrity: all assertions match claim URI hashes (Phase 2c)
3. Cryptographic Signature: COSE_Sign1 valid with leaf certificate, certificate chain valid, and signing timestamp verified (Phase 2c)

---

## 1. Verified Facts from Real Fixtures

1. **COSE_Sign1 Structure (RFC 8152 / RFC 9052 Section 4.4)**:
   - Encoded as CBOR tag 18 wrapping `[protected_bytes, unprotected_map, payload (null), signature_bytes]`.
   - `Sig_structure = ["Signature1", protected_bytes, b'', claim_bytes]`.
   - TBS (To Be Signed) data is the deterministic CBOR encoding of this 4-element array.
2. **Algorithms**:
   - `PS256` (RSASSA-PSS with SHA-256): used in `C.jpg`, `CA.jpg`. Verified via JDK `java.security.Signature.getInstance("SHA256withRSA/PSS")` with `PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)`.
   - `ES256` (ECDSA P-256 with SHA-256): used in `signed.heif`, `signed_small.webp`, `video1.mp4`, `s26-camera.jpg`, `s26-motion-photo.heic`. COSE supplies raw `r || s` (64 bytes). Supported in JDK via conversion to ASN.1 DER format (`30 <len> 02 <r_len> r 02 <s_len> s`) or `SHA256withECDSAinP1363Format`.
3. **Certificates & Trust**:
   - Certificates are embedded in protected header (key 33) or unprotected header (`x5chain`).
   - Parsed into `X509Certificate` via `CertificateFactory.getInstance("X.509")`.
   - Chain verification: `child.verify(parent.publicKey)`.
   - C2PA test certs (`OU=FOR TESTING_ONLY`, `CN=C2PA Signer`, `CN=Intermediate CA`) are recognized as valid signatures but flagged as `Untrusted (Test Credential)`.

---

## 2. Scope & Capabilities

### In Scope (2c):
1. **Sig_structure Encoder**: Pure Kotlin deterministic CBOR serializer for the 4-item array `["Signature1", protected, b'', payload]`.
2. **COSE_Sign1 Signature Verifier**:
   - Algorithms: `ES256`, `ES384`, `ES512`, `PS256`, `RS256`.
   - IEEE P1363 to DER converter for ECDSA signatures.
   - Public key extraction from leaf X.509 certificate.
3. **X.509 Chain Verifier**:
   - Signature path validation (leaf -> intermediate -> root).
   - Validity period checks against current time or signing timestamp.
   - Self-signed root detection.
4. **Assertion URI Hash Verifier**:
   - Verify every URI in `claim.assertions` matches the hash of its corresponding JUMBF assertion box (`alg` = sha256).
5. **Timestamp Imprint Verification**:
   - Verify that the timestamp token in `sigTst` binds the COSE signature bytes.
6. **UI Integration**:
   - Replace "서명 검증 전 (not verified)" with live status:
     - `Valid (검증 성공)` / `Invalid Signature (서명 위조/불일치)` / `Untrusted Cert (신뢰되지 않은 발급자)`.
   - "개요 (Overview)" 탭에 서명 검증 상세 (서명 일치, 체인 검증, 어설션 해시 일치, 타임스탬프) 카드 추가.

### Constraints:
- Pure Kotlin + JDK standard libraries only (`java.security.*`, `java.security.cert.*`, `javax.crypto.*`). No BouncyCastle, no external dependencies.
- Never crash on malformed keys or corrupted ASN.1 structures.

---

## 3. Architecture

```
com.multiviewer.parser.c2pa/
├── C2paSigStructure.kt       // Deterministic CBOR Sig_structure builder
├── C2paSignatureVerifier.kt   // Signature verification (ES256, PS256) & DER converter
├── C2paChainVerifier.kt       // X.509 chain and trust path evaluator
└── C2paAssertionIntegrity.kt  // Claim URI assertion hash validation
```

### Models

```kotlin
enum class VerificationState {
    VALID,          // Signature, chain, and assertion hashes all match
    INVALID,        // Signature or hash mismatch (tampered)
    UNTRUSTED,      // Cryptographically valid, but cert is not from a known trusted root
    UNSUPPORTED     // Algorithm or format unsupported
}

data class C2paVerificationReport(
    val state: VerificationState,
    val signatureValid: Boolean,
    val algorithm: String,
    val chainValid: Boolean,
    val chainLength: Int,
    val assertionHashesMatch: Boolean,
    val matchedAssertionCount: Int,
    val totalAssertionCount: Int,
    val signingTime: String?,
    val timestampValid: Boolean?,
    val errors: List<String>
)
```
