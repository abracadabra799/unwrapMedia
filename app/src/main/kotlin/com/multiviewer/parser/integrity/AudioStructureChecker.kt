package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.isAdtsMagic
import java.io.File
import java.util.concurrent.CancellationException

internal fun checkAudioScanActive() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Audio structure scan cancelled")
}

data class AudioStructureReport(
    val format: String,
    val items: List<IntegrityCheckItem>,
    val parserWarnings: List<IntegrityCheckItem>,
) {
    val overall: CheckStatus
        get() = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            items.any { it.status == CheckStatus.SKIP } -> CheckStatus.SKIP
            items.any { it.status == CheckStatus.PASS } -> CheckStatus.PASS
            else -> CheckStatus.SKIP
        }
}

object AudioStructureChecker {
    fun check(file: File, root: BoxNode): AudioStructureReport = ByteReader.open(file).use { reader ->
        checkAudioScanActive()
        val format = detectAudioFormat(reader)
        val items = when (format) {
            "WAVE" -> checkWaveStructure(reader)
            "AIFF", "AIFC" -> checkAiffStructure(reader)
            "FLAC" -> checkFlacStructure(reader)
            "MP3" -> checkMp3Structure(reader)
            "ADTS" -> checkAdtsStructure(reader)
            "OGG" -> checkOggStructure(reader)
            "M4A" -> checkM4aStructure(reader, root)
            "ASF" -> checkAsfStructure(reader)
            "UNKNOWN" -> listOf(IntegrityCheckItem(
                "audio.format", "Audio format", CheckStatus.SKIP,
                "Unrecognized audio format; byte-structure checks are unavailable",
            ))
            else -> listOf(IntegrityCheckItem(
                "audio.structure.pending", "$format structure", CheckStatus.SKIP,
                "$format structure validation is not available yet",
            ))
        }
        AudioStructureReport(format, items, ImageIntegrityChecker.parserWarningItems(root))
    }
}

internal fun detectAudioFormat(reader: ByteReader): String {
    val length = reader.length
    if (length >= 12) {
        val head = reader.readFourCC(0)
        val type = reader.readFourCC(8)
        if (head == "RIFF" && type == "WAVE") return "WAVE"
        if (head == "FORM" && (type == "AIFF" || type == "AIFC")) return type
    }
    if (length >= 4) {
        when (reader.readFourCC(0)) {
            "fLaC" -> return "FLAC"
            "OggS" -> return "OGG"
        }
    }
    if (length >= 8 && reader.readFourCC(4) == "ftyp") return "M4A"
    if (isAsfMagic(reader)) return "ASF"
    if (isAdtsMagic(reader)) return "ADTS"
    if (length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") return "MP3"
    if (length >= 2 && reader.readUInt8(0) == 0xFF && (reader.readUInt8(1) and 0xE0) == 0xE0) return "MP3"
    return "UNKNOWN"
}

private fun isAsfMagic(reader: ByteReader): Boolean {
    if (reader.length < 24) return false
    val expected = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
    )
    return reader.readBytes(0, expected.size).contentEquals(expected)
}
