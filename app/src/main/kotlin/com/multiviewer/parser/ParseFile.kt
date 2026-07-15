package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val children = parseBoxes(reader, 0, reader.length)
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}
