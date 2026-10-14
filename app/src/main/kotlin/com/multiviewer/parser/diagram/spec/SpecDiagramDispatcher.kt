package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode

object SpecDiagramDispatcher {

    fun build(root: BoxNode, fileSize: Long, fileName: String): SpecDiagram {
        val lowerName = fileName.lowercase()
        val types = root.children.map { it.type }.toSet()

        return when {
            lowerName.endsWith(".bmp") || types.contains("BITMAPFILEHEADER") -> {
                BmpSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || types.contains("SOI") -> {
                JpegSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".heic") || lowerName.endsWith(".heif") || lowerName.endsWith(".avif") || lowerName.endsWith(".hif") ||
                types.contains("meta") && !types.contains("moov") -> {
                HeicSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".m4v") || lowerName.endsWith(".m4a") ||
                types.contains("moov") -> {
                Mp4SpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".png") || types.contains("IHDR") -> {
                PngSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".tif") || lowerName.endsWith(".tiff") || lowerName.endsWith(".dng") ||
                types.any { it.startsWith("IFD") } -> {
                TiffSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            lowerName.endsWith(".webp") || types.contains("VP8X") || types.contains("VP8") || types.contains("VP8L") -> {
                WebpSpecDiagramBuilder.build(root, fileSize, fileName)
            }
            else -> {
                UniversalSpecDiagramBuilder.build(root, fileSize, fileName)
            }
        }
    }
}
