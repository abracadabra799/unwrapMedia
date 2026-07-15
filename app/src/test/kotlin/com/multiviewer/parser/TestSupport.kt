package com.multiviewer.parser

import java.io.File

fun byteReaderOf(bytes: ByteArray): ByteReader {
    val tmp = File.createTempFile("multiviewer-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}
