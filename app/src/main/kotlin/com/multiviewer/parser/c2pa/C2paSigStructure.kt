package com.multiviewer.parser.c2pa

import java.io.ByteArrayOutputStream

fun buildCoseSigStructure(protectedBytes: ByteArray, payloadBytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    // 4-element array: 0x84
    out.write(0x84)

    // Element 1: "Signature1" (text, 10 bytes)
    out.write(0x6A)
    out.write("Signature1".toByteArray(Charsets.US_ASCII))

    // Element 2: body_protected (bstr)
    encodeByteString(protectedBytes, out)

    // Element 3: external_aad (bstr, empty)
    out.write(0x40)

    // Element 4: payload (bstr)
    encodeByteString(payloadBytes, out)

    return out.toByteArray()
}

private fun encodeByteString(bytes: ByteArray, out: ByteArrayOutputStream) {
    val len = bytes.size
    when {
        len < 24 -> out.write(0x40 or len)
        len <= 0xFF -> {
            out.write(0x58)
            out.write(len)
        }
        len <= 0xFFFF -> {
            out.write(0x59)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }
        else -> {
            out.write(0x5A)
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }
    }
    out.write(bytes)
}

fun ieeeP1363ToDer(sig: ByteArray): ByteArray {
    if (sig.isEmpty()) return sig
    val half = sig.size / 2
    val r = sig.copyOfRange(0, half)
    val s = sig.copyOfRange(half, sig.size)

    val rDer = derInteger(r)
    val sDer = derInteger(s)
    val totalPayload = rDer.size + sDer.size

    val out = ByteArrayOutputStream()
    out.write(0x30)
    if (totalPayload < 128) {
        out.write(totalPayload)
    } else {
        out.write(0x81)
        out.write(totalPayload)
    }
    out.write(rDer)
    out.write(sDer)
    return out.toByteArray()
}

private fun derInteger(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
        start++
    }
    val trimmed = if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes
    val needPadding = (trimmed[0].toInt() and 0x80) != 0

    val out = ByteArrayOutputStream()
    out.write(0x02)
    val len = trimmed.size + if (needPadding) 1 else 0
    out.write(len)
    if (needPadding) {
        out.write(0x00)
    }
    out.write(trimmed)
    return out.toByteArray()
}
