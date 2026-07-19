package com.multiviewer.parser

import java.io.File

data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)

fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo? {
    val videoNode = root.children.find { it.type == "mpvd" }
        ?: findFirst(root) { it.type == "sefd" }
            ?.children
            ?.find { it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = videoNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(videoNode.offset + videoNode.headerSize, videoNode.offset + videoNode.size, extension)
}

fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File) {
    val chunkSizeLimit = 1L shl 20 // 1 MB
    ByteReader.open(source).use { reader ->
        destination.outputStream().use { out ->
            var offset = video.start
            while (offset < video.end) {
                val chunkSize = minOf(chunkSizeLimit, video.end - offset).toInt()
                out.write(reader.readBytes(offset, chunkSize))
                offset += chunkSize
            }
        }
    }
}
