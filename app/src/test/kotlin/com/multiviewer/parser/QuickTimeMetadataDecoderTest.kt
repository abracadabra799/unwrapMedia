package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuickTimeMetadataDecoderTest {

    @Test
    fun `decodes meta with keys and ilst and enriches Apple QuickTime metadata`() {
        val bytes = buildQuickTimeMetaBox()
        val reader = byteReaderOf(bytes)
        registerAllDecoders()

        val nodes = parseBoxes(reader, 0, bytes.size.toLong())
        val meta = nodes.firstOrNull { it.type == "meta" }
        assertNotNull(meta, "meta box should exist")

        // Check keys box
        val keysBox = meta.children.find { it.type == "keys" }
        assertNotNull(keysBox, "keys box should exist")
        assertEquals(4, keysBox.fields.find { it.name == "entry_count" }?.value?.toIntOrNull())

        // Check ilst box
        val ilstBox = meta.children.find { it.type == "ilst" }
        assertNotNull(ilstBox, "ilst box should exist")

        // Check derived "Apple QuickTime Metadata" node
        val appleMetaNode = meta.children.find { it.type == "Apple QuickTime Metadata" }
        assertNotNull(appleMetaNode, "Apple QuickTime Metadata derived node should exist")

        // Check fields in Apple QuickTime Metadata node
        val makeField = appleMetaNode.fields.find { it.name.contains("Make") || it.name == "com.apple.quicktime.make" }
        assertNotNull(makeField)
        assertEquals("Apple", makeField.value)

        val modelField = appleMetaNode.fields.find { it.name.contains("Model") || it.name == "com.apple.quicktime.model" }
        assertNotNull(modelField)
        assertEquals("iPhone 15 Pro", modelField.value)

        val contentIdField = appleMetaNode.fields.find { it.name.contains("Content Identifier") || it.name == "com.apple.quicktime.content.identifier" }
        assertNotNull(contentIdField)
        assertEquals("E6B1B855-8D1F-4309-8C9B-1B05F4DF2803", contentIdField.value)

        // Unknown key preservation
        val unknownField = appleMetaNode.fields.find { it.name.contains("custom.unknown.key") }
        assertNotNull(unknownField, "Unknown key should be preserved")
        assertEquals("custom_value", unknownField.value)

        reader.close()
    }

    private fun buildQuickTimeMetaBox(): ByteArray {
        val keysBaos = ByteArrayOutputStream()
        val keysDos = DataOutputStream(keysBaos)
        // keys payload:
        // FullBox: version=0, flags=0 (4 bytes)
        // entry_count: 4 (4 bytes)
        // 1: "mdta", "com.apple.quicktime.make"
        // 2: "mdta", "com.apple.quicktime.model"
        // 3: "mdta", "com.apple.quicktime.content.identifier"
        // 4: "mdta", "com.example.custom.unknown.key"
        keysDos.writeInt(0) // version & flags
        keysDos.writeInt(4) // entry_count

        val keyStrings = listOf(
            "com.apple.quicktime.make",
            "com.apple.quicktime.model",
            "com.apple.quicktime.content.identifier",
            "com.example.custom.unknown.key",
        )

        for (k in keyStrings) {
            val kBytes = k.toByteArray(StandardCharsets.UTF_8)
            val kSize = 8 + kBytes.size
            keysDos.writeInt(kSize)
            keysDos.write("mdta".toByteArray(StandardCharsets.US_ASCII))
            keysDos.write(kBytes)
        }

        val keysBoxBytes = wrapBox("keys", keysBaos.toByteArray())

        // ilst box containing 4 items:
        // Item 1 (type = 0x00000001): data box type 1 (UTF-8), "Apple"
        // Item 2 (type = 0x00000002): data box type 1 (UTF-8), "iPhone 15 Pro"
        // Item 3 (type = 0x00000003): data box type 1 (UTF-8), "E6B1B855-8D1F-4309-8C9B-1B05F4DF2803"
        // Item 4 (type = 0x00000004): data box type 1 (UTF-8), "custom_value"
        val ilstBaos = ByteArrayOutputStream()
        val values = listOf(
            "Apple",
            "iPhone 15 Pro",
            "E6B1B855-8D1F-4309-8C9B-1B05F4DF2803",
            "custom_value",
        )

        for (i in 1..4) {
            val itemTypeInt = i
            val itemTypeStr = String(
                byteArrayOf(
                    ((itemTypeInt shr 24) and 0xFF).toByte(),
                    ((itemTypeInt shr 16) and 0xFF).toByte(),
                    ((itemTypeInt shr 8) and 0xFF).toByte(),
                    (itemTypeInt and 0xFF).toByte(),
                ),
                StandardCharsets.ISO_8859_1,
            )
            val dataBoxBytes = buildDataBox(typeCode = 1, strValue = values[i - 1])
            val itemBoxBytes = wrapBox(itemTypeStr, dataBoxBytes)
            ilstBaos.write(itemBoxBytes)
        }

        val ilstBoxBytes = wrapBox("ilst", ilstBaos.toByteArray())

        // hdlr box: "mdta"
        val hdlrBaos = ByteArrayOutputStream()
        val hdlrDos = DataOutputStream(hdlrBaos)
        hdlrDos.writeInt(0) // version & flags
        hdlrDos.writeInt(0) // pre_defined
        hdlrDos.write("mdta".toByteArray(StandardCharsets.US_ASCII)) // handler_type
        hdlrDos.writeInt(0) // reserved 1
        hdlrDos.writeInt(0) // reserved 2
        hdlrDos.writeInt(0) // reserved 3
        hdlrDos.writeByte(0) // name string null-terminated
        val hdlrBoxBytes = wrapBox("hdlr", hdlrBaos.toByteArray())

        // Meta box payload:
        // FullBox header (version=0, flags=0) if plainBoxLayout false, or hdlr + keys + ilst
        val metaBaos = ByteArrayOutputStream()
        val metaDos = DataOutputStream(metaBaos)
        metaDos.writeInt(0) // version & flags (4 bytes for FullBox meta)
        metaDos.write(hdlrBoxBytes)
        metaDos.write(keysBoxBytes)
        metaDos.write(ilstBoxBytes)

        return wrapBox("meta", metaBaos.toByteArray())
    }

    private fun buildDataBox(typeCode: Int, strValue: String): ByteArray {
        val valBytes = strValue.toByteArray(StandardCharsets.UTF_8)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        // 4 bytes: flags (type code in lower 3 bytes)
        dos.writeInt(typeCode and 0x00FFFFFF)
        // 4 bytes: locale
        dos.writeInt(0)
        dos.write(valBytes)
        return wrapBox("data", baos.toByteArray())
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val typeBytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        val size = 8 + payload.size
        dos.writeInt(size)
        dos.write(typeBytes)
        dos.write(payload)
        return baos.toByteArray()
    }
}
