package com.multiviewer.parser.c2pa

// ISO/IEC 19566-5 JUMBF boxes over an in-memory C2PA manifest store. Offsets are logical offsets
// into the store byte array; C2paLocator maps them back to file offsets.

private val ISO_UUID_SUFFIX = byteArrayOf(
    0x00, 0x11, 0x00, 0x10, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
)
private val EMBEDDED_FILE_UUID = byteArrayOf(
    0x40, 0xCB.toByte(), 0x0C, 0x32, 0xBB.toByte(), 0x8A.toByte(), 0x48, 0x9D.toByte(),
    0xA7.toByte(), 0x0B, 0x2A, 0xD6.toByte(), 0xF4.toByte(), 0x7F, 0x43, 0x69,
)
const val JUMBF_EMBEDDED_FILE_TYPE = "embedded-file"
private const val MAX_SUPERBOX_DEPTH = 32

fun jumbfContentTypeName(uuid: ByteArray): String = when {
    uuid.size == 16 && uuid.copyOfRange(4, 16).contentEquals(ISO_UUID_SUFFIX) &&
        uuid.copyOfRange(0, 4).all { it in 0x20..0x7E } -> String(uuid, 0, 4, Charsets.US_ASCII)
    uuid.contentEquals(EMBEDDED_FILE_UUID) -> JUMBF_EMBEDDED_FILE_TYPE
    else -> uuid.joinToString("") { "%02x".format(it) }.let {
        if (it.length != 32) it else "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
    }
}

class JumbfDescription(
    val contentType: ByteArray,
    val toggles: Int,
    val label: String?,
    val id: Long?,
    val signature: ByteArray?,
    val privateBoxType: String?,
) {
    val contentTypeName: String get() = jumbfContentTypeName(contentType)
}

class JumbfBox(
    val type: String,
    val offset: Int,
    val headerSize: Int,
    val size: Int,
    val description: JumbfDescription?,
    val children: List<JumbfBox>,
    val warnings: List<String>,
) {
    val label: String? get() = description?.label
    val payloadStart: Int get() = offset + headerSize
    val payloadEnd: Int get() = offset + size
    fun child(label: String): JumbfBox? = children.firstOrNull { it.label == label }
    fun firstContent(type: String): JumbfBox? = children.firstOrNull { it.type == type }
}

fun parseJumbfBoxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<JumbfBox> =
    parseLevel(bytes, start, end, 0)

private fun u32(b: ByteArray, at: Int): Long =
    ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
        ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

private fun fourCc(b: ByteArray, at: Int): String =
    String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() })

private fun parseLevel(bytes: ByteArray, start: Int, end: Int, depth: Int): List<JumbfBox> {
    val out = mutableListOf<JumbfBox>()
    var pos = start
    while (pos < end) {
        val remaining = end - pos
        if (remaining < 8) {
            out += JumbfBox("?", pos, 0, remaining, null, emptyList(), listOf("Trailing $remaining byte(s): too short for a box header"))
            break
        }
        val lbox = u32(bytes, pos)
        val type = fourCc(bytes, pos + 4)
        var headerSize = 8
        val size: Long = when (lbox) {
            0L -> remaining.toLong()
            1L -> {
                if (remaining < 16) {
                    out += JumbfBox(type, pos, 8, remaining, null, emptyList(), listOf("64-bit size needs 16 header bytes, $remaining left"))
                    return out
                }
                headerSize = 16
                (u32(bytes, pos + 8) shl 32) or u32(bytes, pos + 12)
            }
            else -> lbox
        }
        if (size < headerSize || size > remaining) {
            out += JumbfBox(type, pos, minOf(headerSize, remaining), remaining, null, emptyList(), listOf("Declared size $size runs past the end of its parent ($remaining byte(s) left)"))
            break
        }
        val boxEnd = pos + size.toInt()
        if (type == "jumb" && depth < MAX_SUPERBOX_DEPTH) {
            val inner = parseLevel(bytes, pos + headerSize, boxEnd, depth + 1)
            val warnings = mutableListOf<String>()
            val first = inner.firstOrNull()
            val description = if (first?.type == "jumd") parseDescription(bytes, first, warnings) else null
            if (description == null) warnings += "Superbox has no jumd description box"
            val children = if (first?.type == "jumd") inner.drop(1) else inner
            out += JumbfBox(type, pos, headerSize, size.toInt(), description, children, warnings + (first?.warnings ?: emptyList()))
        } else {
            val warnings = if (type == "jumb") listOf("Superbox nesting deeper than $MAX_SUPERBOX_DEPTH not expanded") else emptyList()
            out += JumbfBox(type, pos, headerSize, size.toInt(), null, emptyList(), warnings)
        }
        pos = boxEnd
    }
    return out
}

private fun parseDescription(bytes: ByteArray, jumd: JumbfBox, warnings: MutableList<String>): JumbfDescription? {
    val start = jumd.payloadStart
    val end = jumd.payloadEnd
    if (end - start < 17) {
        warnings += "jumd too short (${end - start} bytes) for type and toggles"
        return null
    }
    val type = bytes.copyOfRange(start, start + 16)
    val toggles = bytes[start + 16].toInt() and 0xFF
    var q = start + 17
    var label: String? = null
    if (toggles and 0x02 != 0) {
        var nul = q
        while (nul < end && bytes[nul] != 0.toByte()) nul++
        label = String(bytes, q, nul - q, Charsets.UTF_8)
        if (nul >= end) {
            warnings += "jumd label is not NUL-terminated"
            q = end
        } else {
            q = nul + 1
        }
    }
    var id: Long? = null
    if (toggles and 0x04 != 0) {
        if (end - q >= 4) { id = u32(bytes, q); q += 4 } else warnings += "jumd ID toggle set but fewer than 4 bytes left"
    }
    var signature: ByteArray? = null
    if (toggles and 0x08 != 0) {
        if (end - q >= 32) { signature = bytes.copyOfRange(q, q + 32); q += 32 } else warnings += "jumd signature toggle set but fewer than 32 bytes left"
    }
    var privateType: String? = null
    if (toggles and 0x10 != 0) {
        if (end - q >= 8) {
            val privateBox = parseLevel(bytes, q, end, MAX_SUPERBOX_DEPTH).first()
            privateType = privateBox.type
            warnings += privateBox.warnings.map { "jumd private box: $it" }
        } else warnings += "jumd private-box toggle set but no box follows"
    }
    return JumbfDescription(type, toggles, label, id, signature, privateType)
}
