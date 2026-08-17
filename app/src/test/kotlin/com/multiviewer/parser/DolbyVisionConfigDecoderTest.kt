package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DolbyVisionConfigDecoderTest {

    @Test
    fun `decodes dvcC and dvvC Dolby Vision configuration boxes`() {
        registerAllDecoders()

        // Profile 8, level 4, RPU=1, EL=0, BL=1, compatibility_id=4 (HLG, Apple iPhone)
        val dvcCBytes = buildDolbyVisionBox("dvcC", major = 1, minor = 0, profile = 8, level = 4, rpu = 1, el = 0, bl = 1, compatId = 4)
        val reader = byteReaderOf(dvcCBytes)
        val node = parseBoxes(reader, 0, dvcCBytes.size.toLong()).first()

        assertEquals("dvcC", node.type)
        assertEquals("1", node.fields.find { it.name == "dv_version_major" }?.value)
        assertEquals("0", node.fields.find { it.name == "dv_version_minor" }?.value)
        assertEquals("8", node.fields.find { it.name == "dv_profile" }?.value)
        assertEquals("4", node.fields.find { it.name == "dv_level" }?.value)
        assertEquals("1", node.fields.find { it.name == "rpu_present_flag" }?.value)
        assertEquals("0", node.fields.find { it.name == "el_present_flag" }?.value)
        assertEquals("1", node.fields.find { it.name == "bl_present_flag" }?.value)
        assertTrue(node.fields.find { it.name == "dv_bl_signal_compatibility_id" }?.value?.contains("4") == true)
        assertTrue(node.summary?.contains("8") == true)
        reader.close()

        val dvvCBytes = buildDolbyVisionBox("dvvC", major = 1, minor = 0, profile = 8, level = 7, rpu = 1, el = 0, bl = 1, compatId = 1)
        val reader2 = byteReaderOf(dvvCBytes)
        val node2 = parseBoxes(reader2, 0, dvvCBytes.size.toLong()).first()
        assertEquals("dvvC", node2.type)
        assertEquals("8", node2.fields.find { it.name == "dv_profile" }?.value)
        assertEquals("7", node2.fields.find { it.name == "dv_level" }?.value)
        reader2.close()
    }

    @Test
    fun `warns on truncated dolby vision box`() {
        registerAllDecoders()
        val shortBytes = byteArrayOf(0, 0, 0, 10, 'd'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), 'C'.code.toByte(), 1, 0)
        val reader = byteReaderOf(shortBytes)
        val node = parseBoxes(reader, 0, shortBytes.size.toLong()).first()
        assertEquals("dvcC", node.type)
        assertTrue(node.warnings.isNotEmpty())
        reader.close()
    }

    private fun buildDolbyVisionBox(
        type: String,
        major: Int,
        minor: Int,
        profile: Int,
        level: Int,
        rpu: Int,
        el: Int,
        bl: Int,
        compatId: Int,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val size = 8 + 24
        dos.writeInt(size)
        dos.write(type.toByteArray(StandardCharsets.US_ASCII))

        dos.writeByte(major)
        dos.writeByte(minor)

        // 16 bits: profile(7), level(6), rpu(1), el(1), bl(1)
        val profileBits = (profile and 0x7F) shl 9
        val levelBits = (level and 0x3F) shl 3
        val rpuBits = (rpu and 0x1) shl 2
        val elBits = (el and 0x1) shl 1
        val blBits = (bl and 0x1)
        val combined16 = profileBits or levelBits or rpuBits or elBits or blBits
        dos.writeShort(combined16)

        // byte 4: compatId (4 bits) + reserved (4 bits)
        val byte4 = (compatId and 0x0F) shl 4
        dos.writeByte(byte4)

        // 19 bytes reserved/padding to reach 24 bytes
        dos.write(ByteArray(19))

        return baos.toByteArray()
    }
}
