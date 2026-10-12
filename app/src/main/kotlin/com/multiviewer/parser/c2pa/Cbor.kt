package com.multiviewer.parser.c2pa

import java.math.BigInteger
import kotlin.math.pow

// Minimal RFC 8949 CBOR decoder for C2PA claims, assertions and COSE structures. This codebase
// deliberately has no CBOR/JSON library; values are decoded into an immutable tree.
sealed interface CborValue

data class CborInt(val value: BigInteger) : CborValue {
    fun toLongOrNull(): Long? = if (value.bitLength() < 64) value.toLong() else null
}

class CborBytes(val value: ByteArray) : CborValue {
    override fun equals(other: Any?): Boolean = other is CborBytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "CborBytes(${value.size} bytes)"
}

data class CborText(val value: String) : CborValue
data class CborArray(val items: List<CborValue>) : CborValue

data class CborMap(val entries: List<Pair<CborValue, CborValue>>) : CborValue {
    operator fun get(key: String): CborValue? =
        entries.firstOrNull { (k, _) -> k is CborText && k.value == key }?.second

    operator fun get(key: Long): CborValue? =
        entries.firstOrNull { (k, _) -> k is CborInt && k.value == BigInteger.valueOf(key) }?.second
}

data class CborTag(val tag: BigInteger, val value: CborValue) : CborValue
data class CborBool(val value: Boolean) : CborValue
data object CborNull : CborValue
data object CborUndefined : CborValue
data class CborSimple(val value: Int) : CborValue
data class CborFloat(val value: Double) : CborValue

fun CborValue.asText(): String? = (this as? CborText)?.value
fun CborValue.asBytes(): ByteArray? = (this as? CborBytes)?.value
fun CborValue.asMap(): CborMap? = this as? CborMap
fun CborValue.asArray(): List<CborValue>? = (this as? CborArray)?.items
fun CborValue.asLong(): Long? = (this as? CborInt)?.toLongOrNull()

class CborException(message: String, val offset: Int) : Exception("$message at byte $offset")

data class CborLimits(val maxDepth: Int = 64, val maxItems: Int = 1_000_000)

fun decodeCbor(bytes: ByteArray, start: Int = 0, end: Int = bytes.size, limits: CborLimits = CborLimits()): CborValue =
    CborDecoder(bytes, start, end, limits).read(0)

private const val BREAK = 0xFF

private class CborDecoder(val bytes: ByteArray, start: Int, val end: Int, val limits: CborLimits) {
    init {
        if (start < 0 || end < start || end > bytes.size) throw CborException("invalid decode range", start)
    }

    var pos = start
    var items = 0

    fun u8(): Int {
        if (pos >= end) throw CborException("unexpected end of data", pos)
        return bytes[pos++].toInt() and 0xFF
    }

    fun take(n: Int): ByteArray {
        if (n < 0 || n > end - pos) throw CborException("length $n exceeds the ${end - pos} bytes left", pos)
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun argument(info: Int, itemStart: Int): BigInteger = when {
        info < 24 -> BigInteger.valueOf(info.toLong())
        info == 24 -> BigInteger.valueOf(u8().toLong())
        info == 25 -> BigInteger(1, take(2))
        info == 26 -> BigInteger(1, take(4))
        info == 27 -> BigInteger(1, take(8))
        else -> throw CborException("reserved additional info $info", itemStart)
    }

    fun length(arg: BigInteger, itemStart: Int): Int {
        if (arg > BigInteger.valueOf((end - pos).toLong())) throw CborException("length $arg exceeds the ${end - pos} bytes left", itemStart)
        return arg.toInt()
    }

    private fun decodeText(value: ByteArray, offset: Int): String = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw CborException("invalid UTF-8 text", offset)
    }

    fun read(depth: Int): CborValue {
        val itemStart = pos
        if (depth > limits.maxDepth) throw CborException("nesting deeper than ${limits.maxDepth}", itemStart)
        if (++items > limits.maxItems) throw CborException("more than ${limits.maxItems} items", itemStart)
        val initial = u8()
        val major = initial ushr 5
        val info = initial and 0x1F
        if (major == 7) return readMajor7(info, itemStart)
        if (info == 31) return readIndefinite(major, depth, itemStart)
        val arg = argument(info, itemStart)
        return when (major) {
            0 -> CborInt(arg)
            1 -> CborInt(BigInteger.ONE.negate().subtract(arg))
            2 -> CborBytes(take(length(arg, itemStart)))
            3 -> CborText(decodeText(take(length(arg, itemStart)), itemStart))
            4 -> CborArray(List(length(arg, itemStart)) { read(depth + 1) })
            5 -> CborMap(List(length(arg, itemStart)) { read(depth + 1) to read(depth + 1) })
            else -> CborTag(arg, read(depth + 1)) // major 6
        }
    }

    fun readIndefinite(major: Int, depth: Int, itemStart: Int): CborValue {
        fun atBreak(): Boolean {
            if (pos >= end) throw CborException("indefinite item has no break", itemStart)
            return (bytes[pos].toInt() and 0xFF) == BREAK
        }
        return when (major) {
            2, 3 -> {
                val out = java.io.ByteArrayOutputStream()
                while (!atBreak()) {
                    val chunkStart = pos
                    if ((bytes[pos].toInt() and 0x1F) == 31)
                        throw CborException("indefinite string chunk must have definite length", chunkStart)
                    val chunk = read(depth + 1)
                    when {
                        major == 2 && chunk is CborBytes -> out.write(chunk.value)
                        major == 3 && chunk is CborText -> out.write(chunk.value.toByteArray(Charsets.UTF_8))
                        else -> throw CborException("indefinite string chunk of the wrong type", chunkStart)
                    }
                }
                pos++
                if (major == 2) CborBytes(out.toByteArray()) else CborText(String(out.toByteArray(), Charsets.UTF_8))
            }
            4 -> {
                val list = mutableListOf<CborValue>()
                while (!atBreak()) list += read(depth + 1)
                pos++
                CborArray(list)
            }
            5 -> {
                val list = mutableListOf<Pair<CborValue, CborValue>>()
                while (!atBreak()) list += read(depth + 1) to read(depth + 1)
                pos++
                CborMap(list)
            }
            else -> throw CborException("indefinite length not allowed for major type $major", itemStart)
        }
    }

    fun readMajor7(info: Int, itemStart: Int): CborValue = when (info) {
        in 0..19 -> CborSimple(info)
        20 -> CborBool(false)
        21 -> CborBool(true)
        22 -> CborNull
        23 -> CborUndefined
        24 -> {
            val v = u8()
            if (v < 32) throw CborException("simple value $v must use the one-byte form", itemStart)
            CborSimple(v)
        }
        25 -> CborFloat(halfToDouble(BigInteger(1, take(2)).toInt()))
        26 -> CborFloat(java.lang.Float.intBitsToFloat(BigInteger(1, take(4)).toInt()).toDouble())
        27 -> CborFloat(java.lang.Double.longBitsToDouble(BigInteger(1, take(8)).toLong()))
        31 -> throw CborException("unexpected break", itemStart)
        else -> throw CborException("reserved additional info $info", itemStart)
    }
}

private fun halfToDouble(h: Int): Double {
    val sign = if (h and 0x8000 != 0) -1.0 else 1.0
    val exponent = (h shr 10) and 0x1F
    val mantissa = h and 0x3FF
    return sign * when (exponent) {
        0 -> mantissa * 2.0.pow(-24)
        31 -> if (mantissa == 0) Double.POSITIVE_INFINITY else Double.NaN
        else -> (mantissa + 1024) * 2.0.pow(exponent - 25)
    }
}

fun CborValue.toDiagnostic(maxBytes: Int = 64): String = StringBuilder().also { appendDiagnostic(it, this, maxBytes) }.toString()

private fun appendDiagnostic(sb: StringBuilder, v: CborValue, maxBytes: Int) {
    when (v) {
        is CborInt -> sb.append(v.value.toString())
        is CborBytes -> {
            sb.append("h'")
            v.value.take(maxBytes).forEach { sb.append("%02x".format(it)) }
            sb.append("'")
            if (v.value.size > maxBytes) sb.append("…(+${v.value.size - maxBytes} bytes)")
        }
        is CborText -> {
            sb.append('"')
            v.value.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
        is CborArray -> {
            sb.append('[')
            v.items.forEachIndexed { i, item -> if (i > 0) sb.append(", "); appendDiagnostic(sb, item, maxBytes) }
            sb.append(']')
        }
        is CborMap -> {
            sb.append('{')
            v.entries.forEachIndexed { i, (k, value) ->
                if (i > 0) sb.append(", ")
                appendDiagnostic(sb, k, maxBytes)
                sb.append(": ")
                appendDiagnostic(sb, value, maxBytes)
            }
            sb.append('}')
        }
        is CborTag -> { sb.append(v.tag.toString()).append('('); appendDiagnostic(sb, v.value, maxBytes); sb.append(')') }
        is CborBool -> sb.append(v.value.toString())
        CborNull -> sb.append("null")
        CborUndefined -> sb.append("undefined")
        is CborSimple -> sb.append("simple(${v.value})")
        is CborFloat -> sb.append(
            when {
                v.value.isNaN() -> "NaN"
                v.value == Double.POSITIVE_INFINITY -> "Infinity"
                v.value == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> v.value.toString()
            },
        )
    }
}
