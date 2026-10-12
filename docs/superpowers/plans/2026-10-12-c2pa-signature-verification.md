# C2PA Cryptographic Signature Verification (Phase 2c) — Implementation Plan

**Goal:** Implement full cryptographic verification of C2PA COSE_Sign1 signatures, assertion hashes, and X.509 certificate chains using pure Kotlin and JDK standard cryptography APIs, replacing "서명 검증 전 (not verified)" with live validation results.

**Tech Stack:** Kotlin 2.0.21, Compose Desktop (Material3), JUnit 5 / kotlin.test, JDK `java.security.Signature`, `java.security.cert.CertificateFactory`. No external dependencies.

**Spec:** `docs/superpowers/specs/2026-10-12-c2pa-signature-verification-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paSigStructure.kt` (new) | Deterministic CBOR `Sig_structure` builder & IEEE P1363 to DER converter |
| `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paSignatureVerifier.kt` (new) | Signature verifier (ES256, PS256, RS256), certificate chain validator, assertion hash integrity |
| `app/src/main/kotlin/com/multiviewer/ui/C2paWindow.kt` (modify) | Update Overview tab and header with real cryptographic verification badges and details |
| `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paSignatureVerifierTest.kt` (new) | Unit tests verifying PS256 (`C.jpg`), ES256 (`signed.heif`, `s26-camera.jpg`, `video1.mp4`) |

---

### Task 1: `Sig_structure` CBOR Serialization & ECDSA DER Converter
- Implement `buildCoseSigStructure(protectedBytes: ByteArray, claimBytes: ByteArray): ByteArray`
  - Encodes `["Signature1", protectedBytes, b'', claimBytes]` as RFC 8152 deterministic CBOR.
- Implement `ieeeP1363ToDer(signature: ByteArray): ByteArray`
  - Converts raw `(r || s)` into ASN.1 DER `30 <len> 02 <rLen> r 02 <sLen> s`.

### Task 2: COSE_Sign1 Signature Verification Engine
- Support ES256 (ECDSA P-256 with SHA-256), PS256 (RSA-PSS with SHA-256), RS256.
- Extract public key from `X509Certificate`.
- Verify signature against TBS bytes.
- Test against `C.jpg` (PS256) and `signed.heif` (ES256).

### Task 3: Assertion URI Hash Integrity Validation
- Extract hashed URIs from `claim.assertions` / `created_assertions` + `gathered_assertions`.
- Hash corresponding JUMBF assertion box bytes using SHA-256.
- Verify exact hash match for all assertions.

### Task 4: X.509 Certificate Chain & Trust Evaluator
- Verify signature path along the certificate chain (`chain[i].verify(chain[i+1].publicKey)`).
- Check validity dates (`checkValidity`).
- Classify trust status: `Trusted`, `Untrusted (Test Certificate)`, or `Invalid Chain`.

### Task 5: Integrated Verification Report & UI Updates
- Create `verifyC2paManifest(manifest: C2paManifest): C2paVerificationReport`.
- Update `C2paWindow.kt`:
  - Header: show `서명 유효 (Valid)` / `서명 불일치 (Invalid)` / `신뢰되지 않은 발급자 (Untrusted)`.
  - Overview tab: Add cryptographic verification details card (Signature status, Certificate chain, Assertion hash match count).

### Task 6: Final Verification & Merge
- Run `./gradlew :app:test --rerun-tasks`.
- Verify 0 external crypto dependencies, 0 process calls.
- Fast-forward merge to `main`.
