package com.multiviewer.parser

import com.multiviewer.ui.MediaType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatSnifferTest {
    private fun fixture(name: String) = File(javaClass.getResource("/format-sniff/$name")!!.toURI())

    private fun sniff(name: String) = ByteReader.open(fixture(name)).use { sniffFormat(it) }

    @Test
    fun `real files are classified by content`() {
        assertEquals(ContainerFormat.JPEG, sniff("a.jpg"))
        assertEquals(ContainerFormat.PNG, sniff("a.png"))
        assertEquals(ContainerFormat.HEIF, sniff("a.heic"))
        assertEquals(ContainerFormat.AVIF, sniff("a.avif"))
        assertEquals(ContainerFormat.MP4, sniff("a.mp4"))
        assertEquals(ContainerFormat.MOV, sniff("a.mov"))
        assertEquals(ContainerFormat.M4A, sniff("a.m4a"))
        assertEquals(ContainerFormat.AAC, sniff("a.aac"))
        assertEquals(ContainerFormat.MP3, sniff("a.mp3"))
        assertEquals(ContainerFormat.MP3, sniff("a_noid3.mp3"))
        assertEquals(ContainerFormat.ASF, sniff("a.wma"))
    }

    @Test
    fun `media types follow the container`() {
        assertEquals(MediaType.IMAGE, ContainerFormat.HEIF.mediaType)
        assertEquals(MediaType.IMAGE, ContainerFormat.AVIF.mediaType)
        assertEquals(MediaType.VIDEO, ContainerFormat.MOV.mediaType)
        assertEquals(MediaType.AUDIO, ContainerFormat.M4A.mediaType)
    }

    @Test
    fun `ambiguous containers keep the extension's media type when it fits`() {
        assertEquals(MediaType.AUDIO, ContainerFormat.ASF.mediaTypeFor("wma"))
        assertEquals(MediaType.VIDEO, ContainerFormat.ASF.mediaTypeFor("wmv"))
        assertEquals(MediaType.VIDEO, ContainerFormat.ASF.mediaTypeFor("bin"))
        assertEquals(MediaType.AUDIO, ContainerFormat.MP4.mediaTypeFor("m4a"))
        assertEquals(MediaType.VIDEO, ContainerFormat.WEBM_MKV.mediaTypeFor("webm"))
        assertEquals(MediaType.IMAGE, ContainerFormat.HEIF.mediaTypeFor("mp4"))
    }

    @Test
    fun `unknown bytes are not recognized`() {
        assertNull(sniffFormat(byteReaderOf(hex("00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF"))))
        assertNull(sniffFormat(byteReaderOf(ByteArray(0))))
        // A box-shaped header that is not a known ISO BMFF opening box.
        assertNull(sniffFormat(byteReaderOf(hex("00 00 00 10 61 62 63 64 00 00 00 00 00 00 00 00"))))
    }

    @Test
    fun `text starting with BM is parsed as BMP but is not confident enough to open`() {
        val text = byteReaderOf("BMhello world, plain text file".toByteArray())
        assertEquals(ContainerFormat.BMP, sniffFormat(text))
        assertFalse(isConfidentSniff(ContainerFormat.BMP, text))
        // Minimal real-shaped header: BITMAPINFOHEADER (40).
        val bmp = hex("42 4D 3A 00 00 00 00 00 00 00 36 00 00 00 28 00 00 00") + ByteArray(40)
        assertTrue(isConfidentSniff(ContainerFormat.BMP, byteReaderOf(bmp)))
    }

    @Test
    fun `legacy QuickTime without ftyp is MOV`() {
        assertEquals(ContainerFormat.MOV, sniffFormat(byteReaderOf(hex("00 00 00 08 77 69 64 65 00 00 00 08 6D 64 61 74"))))
        assertEquals(ContainerFormat.MOV, sniffFormat(byteReaderOf(hex("00 00 00 10 6D 6F 6F 76 00 00 00 08 66 72 65 65"))))
    }

    @Test
    fun `lone MPEG audio sync bytes need a second frame to count as recognized`() {
        // One plausible MP3 header followed by garbage: parseFile may still treat it as MP3 when the
        // extension says so, but it must not make an unknown-extension file openable.
        val fake = hex("FF FB 50 C4") + ByteArray(600) { 0x41 }
        assertEquals(ContainerFormat.MP3, sniffFormat(byteReaderOf(fake)))
        assertFalse(isConfidentSniff(ContainerFormat.MP3, byteReaderOf(fake)))
        ByteReader.open(fixture("a_noid3.mp3")).use { assertTrue(isConfidentSniff(ContainerFormat.MP3, it)) }
        ByteReader.open(fixture("a.aac")).use { assertTrue(isConfidentSniff(ContainerFormat.AAC, it)) }
        ByteReader.open(fixture("a.mp3")).use { assertTrue(isConfidentSniff(ContainerFormat.MP3, it)) }
    }

    @Test
    fun `extension mismatch only for extensions outside the container's family`() {
        assertNull(formatMismatch(ContainerFormat.JPEG, "jpeg"))
        assertNull(formatMismatch(ContainerFormat.MOV, "mp4"))
        assertNull(formatMismatch(ContainerFormat.MP4, "mov"))
        assertNull(formatMismatch(ContainerFormat.TIFF, "dng"))
        assertEquals(ExtensionMismatch("jpg", ContainerFormat.HEIF), formatMismatch(ContainerFormat.HEIF, "jpg"))
        assertEquals(ExtensionMismatch("", ContainerFormat.PNG), formatMismatch(ContainerFormat.PNG, ""))
        assertEquals(ExtensionMismatch("bin", ContainerFormat.MP4), formatMismatch(ContainerFormat.MP4, "bin"))
    }
}
