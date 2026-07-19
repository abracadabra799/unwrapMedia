package com.multiviewer.parser

import java.io.File

fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file)
    }
    return MediaSummary(category, sections)
}

private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val hasVideoOrAudioTrack = moov.children.filter { it.type == "trak" }.any { trak ->
        val handlerType = findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
        handlerType == "vide" || handlerType == "soun"
    }
    return if (hasVideoOrAudioTrack) MediaCategory.VIDEO else MediaCategory.IMAGE
}

private fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
    if (predicate(node)) return node
    for (child in node.children) {
        val found = findFirst(child, predicate)
        if (found != null) return found
    }
    return null
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}

private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}

private fun buildVideoSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}
