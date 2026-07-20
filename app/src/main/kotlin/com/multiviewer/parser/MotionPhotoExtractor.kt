package com.multiviewer.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)

fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo? {
    val videoNode = root.children.find { it.type == "mpvd" }
        ?: findFirst(root) { it.type == "sefd" }
            ?.children
            ?.filter { it.children.firstOrNull()?.type == "ftyp" }
            ?.let { candidates -> candidates.find { it.type == "MotionPhoto_Data" } ?: candidates.firstOrNull() }
    if (videoNode != null) {
        val majorBrand = videoNode.children.find { it.type == "ftyp" }
            ?.fields?.find { it.name == "major_brand" }?.value
        val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
        return EmbeddedVideo(videoNode.offset + videoNode.headerSize, videoNode.offset + videoNode.size, extension)
    }
    return findGoogleMotionPhotoVideo(root)
}

fun findMotionPhotoPreview(root: BoxNode): EmbeddedVideo? {
    val previewNode = findFirst(root) { it.type == "sefd" }
        ?.children
        ?.find { it.type == "MotionPhoto_AutoPlay" && it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = previewNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(previewNode.offset + previewNode.headerSize, previewNode.offset + previewNode.size, extension)
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

private data class DirectoryVideoInfo(val length: Long, val mimeType: String?)

private fun findGoogleMotionPhotoVideo(root: BoxNode): EmbeddedVideo? {
    val xmpText = findFirst(root) { it.fields.any { field -> field.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value
        ?: return null
    return try {
        val document = parseXmpDocument(xmpText)
        val fromDirectory = findMotionPhotoInDirectory(document)
        val length = fromDirectory?.length ?: findMicroVideoOffset(document) ?: return null
        if (length <= 0 || length > root.size) return null
        val extension = if (fromDirectory?.mimeType == "video/quicktime") "mov" else "mp4"
        EmbeddedVideo(root.size - length, root.size, extension)
    } catch (e: Throwable) {
        // Untrusted input: a crafted/deeply-nested XMP document can throw StackOverflowError
        // or OutOfMemoryError (both are Error, not Exception), not just parse exceptions.
        null
    }
}

private fun parseXmpDocument(xmpText: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.isNamespaceAware = true
    val builder = factory.newDocumentBuilder()
    return builder.parse(InputSource(StringReader(xmpText)))
}

private fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
    val items = document.getElementsByTagNameNS("*", "li")
    for (i in 0 until items.length) {
        val li = items.item(i) as? Element ?: continue
        val semantic = findPropertyValue(li, "Semantic") ?: continue
        if (semantic != "MotionPhoto") continue
        val length = findPropertyValue(li, "Length")?.toLongOrNull() ?: continue
        return DirectoryVideoInfo(length, findPropertyValue(li, "Mime"))
    }
    return null
}

private fun findMicroVideoOffset(document: Document): Long? {
    val descriptions = document.getElementsByTagNameNS("*", "Description")
    for (i in 0 until descriptions.length) {
        val description = descriptions.item(i) as? Element ?: continue
        findPropertyValue(description, "MicroVideoOffset")?.toLongOrNull()?.let { return it }
    }
    return null
}

private fun findPropertyValue(element: Element, localName: String): String? {
    val attributes = element.attributes
    for (i in 0 until attributes.length) {
        val attribute = attributes.item(i)
        if (attribute.localName == localName) return attribute.nodeValue
    }
    val children = element.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i) as? Element ?: continue
        if (child.localName == localName) return child.textContent.trim()
        findPropertyValue(child, localName)?.let { return it }
    }
    return null
}
