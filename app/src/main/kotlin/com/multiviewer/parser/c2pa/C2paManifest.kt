package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class C2paAction(val action: String, val softwareAgent: String?, val whenTime: String?, val digitalSourceType: String?, val parameters: String?)

data class C2paIngredient(
    val title: String?, val format: String?, val relationship: String?, val instanceId: String?,
    val manifestRef: String?, val thumbnailRef: String?,
)

sealed interface C2paAssertionContent {
    data class Actions(val actions: List<C2paAction>) : C2paAssertionContent
    data class Ingredient(val ingredient: C2paIngredient) : C2paAssertionContent
    class Thumbnail(val mimeType: String?, val bytes: ByteArray) : C2paAssertionContent
    data class Cbor(val value: CborValue) : C2paAssertionContent
    data class Json(val text: String) : C2paAssertionContent
    data class Failed(val reason: String) : C2paAssertionContent
    data object Empty : C2paAssertionContent
}

data class C2paAssertion(val label: String, val contentType: String, val content: C2paAssertionContent, val fileOffset: Long, val size: Int, val rawBytes: ByteArray = ByteArray(0), val diagnostic: String = "")

data class C2paClaim(
    val label: String, val version: Int, val title: String?, val format: String?, val instanceId: String?,
    val claimGenerator: String?, val generatorInfo: List<String>, val signatureRef: String?,
    val assertionRefs: List<String>, val alg: String?, val raw: CborValue,
)

data class C2paSignatureInfo(val algorithm: String?, val certificateSubject: String?, val certificateIssuer: String?, val chainLength: Int, val error: String?)

class C2paManifest(
    val label: String,
    val claim: C2paClaim?,
    val claimError: String?,
    val claimBytes: ByteArray?,
    val assertions: List<C2paAssertion>,
    val signature: C2paSignatureInfo?,
    val signatureBytes: ByteArray?,
    val warnings: List<String>,
)

class C2paManifestStore(val location: C2paStoreLocation, val manifests: List<C2paManifest>, val warnings: List<String>) {
    val active: C2paManifest? get() = manifests.lastOrNull()
}

fun coseAlgorithmName(alg: Long): String = when (alg) {
    -7L -> "ES256"; -35L -> "ES384"; -36L -> "ES512"
    -37L -> "PS256"; -38L -> "PS384"; -39L -> "PS512"
    -8L -> "EdDSA"
    else -> "alg $alg"
}

fun readC2paManifestStore(file: File, root: BoxNode): C2paManifestStore? = try {
    ByteReader.open(file).use { reader -> locateC2paStore(reader, root) }?.let { buildC2paManifestStore(it) }
} catch (e: Exception) {
    null
}

fun buildC2paManifestStore(location: C2paStoreLocation): C2paManifestStore {
    val bytes = location.bytes
    val warnings = location.warnings.toMutableList()
    val top = parseJumbfBoxes(bytes).firstOrNull()
    if (top == null || top.label != "c2pa") return C2paManifestStore(location, emptyList(), warnings + "No c2pa manifest store superbox")
    warnings += top.warnings
    val manifests = top.children
        .filter { it.description?.contentTypeName in setOf("c2ma", "c2um") }
        .map { buildManifest(it, bytes, location.offsetMap) }
    return C2paManifestStore(location, manifests, warnings)
}

private fun buildManifest(box: JumbfBox, bytes: ByteArray, map: C2paOffsetMap): C2paManifest {
    val warnings = box.warnings.toMutableList()
    fun part(type: String) = box.children.firstOrNull { it.description?.contentTypeName == type }

    var claim: C2paClaim? = null
    var claimError: String? = null
    var claimBytes: ByteArray? = null
    val claimBox = part("c2cl")
    val claimCbor = claimBox?.firstContent("cbor")
    if (claimCbor == null) {
        claimError = "No claim CBOR box"
    } else {
        claimBytes = bytes.copyOfRange(claimCbor.payloadStart, claimCbor.payloadEnd)
        try {
            claim = parseClaim(claimBox.label ?: "c2pa.claim", decodeCbor(bytes, claimCbor.payloadStart, claimCbor.payloadEnd))
        } catch (e: CborException) {
            claimError = e.message
        }
    }

    val assertions = part("c2as")?.children?.map { parseAssertion(it, bytes, map) } ?: emptyList()

    var signature: C2paSignatureInfo? = null
    var signatureBytes: ByteArray? = null
    val sigCbor = part("c2cs")?.firstContent("cbor")
    if (sigCbor != null) {
        signatureBytes = bytes.copyOfRange(sigCbor.payloadStart, sigCbor.payloadEnd)
        signature = parseSignature(signatureBytes)
    } else {
        warnings += "No signature box"
    }
    return C2paManifest(box.label ?: "", claim, claimError, claimBytes, assertions, signature, signatureBytes, warnings)
}

private fun nameVersion(v: CborValue): String? = v.asMap()?.let { m ->
    val name = m["name"]?.asText() ?: return@let null
    m["version"]?.asText()?.let { "$name $it" } ?: name
}

private fun parseClaim(label: String, v: CborValue): C2paClaim {
    val m = v.asMap() ?: throw CborException("claim is not a CBOR map", 0)
    val info = when (val gi = m["claim_generator_info"]) {
        is CborArray -> gi.items.mapNotNull(::nameVersion)
        is CborMap -> listOfNotNull(nameVersion(gi))
        else -> emptyList()
    }
    fun urls(key: String) = m[key]?.asArray()?.mapNotNull { it.asMap()?.get("url")?.asText() } ?: emptyList()
    val refs = if (m["assertions"] != null) urls("assertions") else urls("created_assertions") + urls("gathered_assertions")
    return C2paClaim(
        label = label,
        version = if (label.endsWith(".v2")) 2 else 1,
        title = m["dc:title"]?.asText(),
        format = m["dc:format"]?.asText(),
        instanceId = m["instanceID"]?.asText(),
        claimGenerator = m["claim_generator"]?.asText(),
        generatorInfo = info,
        signatureRef = m["signature"]?.asText(),
        assertionRefs = refs,
        alg = m["alg"]?.asText(),
        raw = v,
    )
}

private fun parseAssertion(box: JumbfBox, bytes: ByteArray, map: C2paOffsetMap): C2paAssertion {
    val label = box.label ?: ""
    val type = box.description?.contentTypeName ?: "?"
    val rawBox = box.firstContent("cbor") ?: box.firstContent("json") ?: box.firstContent("bidb")
    val rawBytes = rawBox?.let { bytes.copyOfRange(it.payloadStart, it.payloadEnd) } ?: ByteArray(0)
    var diagnostic = ""
    val content: C2paAssertionContent = try {
        when {
            type == JUMBF_EMBEDDED_FILE_TYPE -> {
                val fd = box.firstContent("bfdb")
                val mime = fd?.takeIf { it.payloadEnd - it.payloadStart > 1 }?.let {
                    var nul = it.payloadStart + 1
                    while (nul < it.payloadEnd && bytes[nul] != 0.toByte()) nul++
                    String(bytes, it.payloadStart + 1, nul - it.payloadStart - 1, Charsets.UTF_8)
                }
                val data = box.firstContent("bidb")
                if (data == null) C2paAssertionContent.Empty
                else C2paAssertionContent.Thumbnail(mime, bytes.copyOfRange(data.payloadStart, data.payloadEnd))
            }
            box.firstContent("cbor") != null -> {
                val c = box.firstContent("cbor")!!
                val v = decodeCbor(bytes, c.payloadStart, c.payloadEnd)
                diagnostic = v.toDiagnostic()
                when {
                    label.startsWith("c2pa.actions") -> C2paAssertionContent.Actions(parseActions(v))
                    label.startsWith("c2pa.ingredient") -> C2paAssertionContent.Ingredient(parseIngredient(v))
                    else -> C2paAssertionContent.Cbor(v)
                }
            }
            box.firstContent("json") != null -> box.firstContent("json")!!.let {
                C2paAssertionContent.Json(String(bytes, it.payloadStart, it.payloadEnd - it.payloadStart, Charsets.UTF_8)).also { diagnostic = it.text }
            }
            else -> C2paAssertionContent.Empty
        }
    } catch (e: CborException) {
        C2paAssertionContent.Failed(e.message ?: "CBOR decode failed")
    }
    return C2paAssertion(label, type, content, map.toFile(box.offset), box.size, rawBytes, diagnostic)
}

private fun parseActions(v: CborValue): List<C2paAction> {
    val actions = v.asMap()?.get("actions")?.asArray()
        ?: throw CborException("actions assertion must contain an actions array", 0)
    return actions.map { a ->
        val m = a.asMap() ?: throw CborException("action is not a map", 0)
        C2paAction(
            action = m["action"]?.asText() ?: throw CborException("action name is missing", 0),
            softwareAgent = m["softwareAgent"]?.let { it.asText() ?: it.asMap()?.get("name")?.asText() },
            whenTime = m["when"]?.asText(),
            digitalSourceType = m["digitalSourceType"]?.asText(),
            parameters = m["parameters"]?.toDiagnostic(),
        )
    }
}

private fun parseIngredient(v: CborValue): C2paIngredient {
    val m = v.asMap() ?: return C2paIngredient(null, null, null, null, null, null)
    return C2paIngredient(
        title = m["dc:title"]?.asText(),
        format = m["dc:format"]?.asText(),
        relationship = m["relationship"]?.asText(),
        instanceId = (m["instanceID"] ?: m["instanceId"])?.asText(),
        manifestRef = (m["c2pa_manifest"] ?: m["active_manifest"])?.asMap()?.get("url")?.asText(),
        thumbnailRef = m["thumbnail"]?.asMap()?.get("url")?.asText(),
    )
}

private fun parseSignature(cose: ByteArray): C2paSignatureInfo = try {
    val tagged = decodeCbor(cose)
    if (tagged is CborTag && tagged.tag != java.math.BigInteger.valueOf(18))
        throw CborException("COSE_Sign1 requires tag 18", 0)
    val body = ((tagged as? CborTag)?.value ?: tagged).asArray()
        ?: throw CborException("COSE_Sign1 is not an array", 0)
    if (body.size != 4) throw CborException("COSE_Sign1 has ${body.size} elements, expected 4", 0)
    val protectedBytes = body[0].asBytes() ?: ByteArray(0)
    val protectedMap = if (protectedBytes.isEmpty()) CborMap(emptyList()) else decodeCbor(protectedBytes).asMap() ?: CborMap(emptyList())
    val unprotected = body[1].asMap() ?: CborMap(emptyList())
    val alg = protectedMap[1L]?.asLong()?.let(::coseAlgorithmName)
    val chain = (protectedMap[33L] ?: unprotected[33L] ?: unprotected["x5chain"])?.let { c ->
        c.asBytes()?.let { listOf(it) } ?: c.asArray()?.mapNotNull { it.asBytes() }
    } ?: emptyList()
    val leaf = chain.firstOrNull()?.let {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(it)) as X509Certificate
    }
    C2paSignatureInfo(alg, leaf?.subjectX500Principal?.name, leaf?.issuerX500Principal?.name, chain.size, null)
} catch (e: Exception) {
    C2paSignatureInfo(null, null, null, 0, e.message ?: e.toString())
}
