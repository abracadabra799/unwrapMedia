package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode = ByteReader.open(path).use { reader -> parseFile(path, reader) }

fun parseFile(path: File, reader: ByteReader): BoxNode {
    registerAllDecoders()
    // Dispatch on the bytes (FormatSniffer.kt), never the extension. Unrecognized input still goes
    // to the ISO BMFF walker: AppState.openFile only lets that happen for files whose extension is
    // supported (e.g. .apv/.ivf, which have no magic check here).
    val children = when (sniffFormat(reader)) {
        ContainerFormat.JPEG -> parseJpegSegments(reader, 0, reader.length)
        ContainerFormat.PNG -> parsePngChunks(reader, 8, reader.length)
        ContainerFormat.BMP -> parseBmpHeaders(reader, 0, reader.length)
        ContainerFormat.GIF -> parseGifBlocks(reader, 6, reader.length)
        ContainerFormat.TIFF -> decodeTiff(reader, 0, reader.length)
        ContainerFormat.WEBP -> parseWebpChunks(reader, 0, reader.length)
        ContainerFormat.WAV -> parseWavChunks(reader, 0, reader.length)
        ContainerFormat.AVI -> parseAviChunks(reader, 0, reader.length)
        ContainerFormat.FLV -> parseFlv(reader, 0, reader.length)
        ContainerFormat.ASF -> parseAsf(reader, 0, reader.length)
        ContainerFormat.AAC -> parseAac(reader, 0, reader.length)
        ContainerFormat.MP3 -> parseMp3(reader, 0, reader.length)
        ContainerFormat.WEBM_MKV -> parseEbmlElements(reader, 0, reader.length)
        ContainerFormat.FLAC -> parseFlacBlocks(reader, 0, reader.length)
        ContainerFormat.OGG -> parseOggPages(reader, 0, reader.length)
        ContainerFormat.AIFF -> parseAiffChunks(reader, 0, reader.length)
        ContainerFormat.HEIF, ContainerFormat.AVIF, ContainerFormat.MP4, ContainerFormat.MOV, ContainerFormat.M4A, null ->
            parseBoxes(reader, 0, reader.length)
    }
    return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
}
