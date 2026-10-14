package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode

object SpecDiagramDispatcher {

    fun build(root: BoxNode, fileSize: Long, fileName: String): SpecDiagram {
        val isBmp = fileName.endsWith(".bmp", ignoreCase = true) ||
            root.children.any { it.type == "BITMAPFILEHEADER" }

        return if (isBmp) {
            BmpSpecDiagramBuilder.build(root, fileSize, fileName)
        } else {
            UniversalSpecDiagramBuilder.build(root, fileSize, fileName)
        }
    }
}
