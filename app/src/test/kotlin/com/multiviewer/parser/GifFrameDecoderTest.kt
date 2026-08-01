package com.multiviewer.parser

import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun logicalScreenDescriptor(width: Int, height: Int, globalColorTableFlag: Boolean, globalColorTableSize: Int): ByteArray {
    val packed = (if (globalColorTableFlag) 0x80 else 0x00) or (globalColorTableSize and 0x07)
    return uint16LE(width) + uint16LE(height) + byteArrayOf(packed.toByte(), 0x00, 0x00)
}

private fun imageDescriptor(left: Int, top: Int, width: Int, height: Int, imageData: ByteArray): ByteArray =
    byteArrayOf(0x2C) + uint16LE(left) + uint16LE(top) + uint16LE(width) + uint16LE(height) +
        byteArrayOf(0x00) + byteArrayOf(0x02) + imageData

private fun subBlock(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

private val SUB_BLOCK_TERMINATOR = byteArrayOf(0x00)

private fun graphicControlExtension(delayTimeUnits: Int): ByteArray {
    val gceData = subBlock(
        byteArrayOf(0x00, (delayTimeUnits and 0xFF).toByte(), ((delayTimeUnits shr 8) and 0xFF).toByte(), 0x00),
    ) + SUB_BLOCK_TERMINATOR
    return byteArrayOf(0x21, 0xF9.toByte()) + gceData
}

// Builds a minimal, real, spec-valid 2-frame animated GIF file: a 1x1 canvas, frame 0 is solid red
// at 50ms (delayTimeUnits=5, GIF's delay field is in 1/100s units), frame 1 is solid blue at
// 100ms (delayTimeUnits=10). The LZW image data for each frame -- [0x44, 0x01] for pixel index 0,
// [0x4C, 0x01] for pixel index 1 -- is the exact byte-for-byte encoding of
// [ClearCode(4), <pixel index>, EndCode(5)] at minCodeSize=2 (3-bit codes, since imageDescriptor
// below hardcodes minCodeSize to 0x02), packed LSB-first per the GIF spec's bit-packing rule.
// Worked out by hand and cross-checked against the standard incremental bit-buffer packing
// algorithm (bitBuffer |= code shl bitCount; flush a byte whenever bitCount >= 8). This needs to
// be genuine, decodable LZW data -- unlike GifWalkerTest's structural-parser tests, which only
// exercise the box-tree walker and never need real pixel data -- because this test exercises
// Skia's actual GIF pixel decoder.
private fun writeTwoFrameGif(): File {
    val header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) // "GIF89a"
    val lsd = logicalScreenDescriptor(width = 1, height = 1, globalColorTableFlag = true, globalColorTableSize = 0)
    val globalColorTable = byteArrayOf(
        0xFF.toByte(), 0x00, 0x00, // color index 0 = red
        0x00, 0x00, 0xFF.toByte(), // color index 1 = blue
    )
    val frame0ImageData = subBlock(byteArrayOf(0x44, 0x01)) + SUB_BLOCK_TERMINATOR
    val frame0 = imageDescriptor(left = 0, top = 0, width = 1, height = 1, imageData = frame0ImageData)
    val frame1ImageData = subBlock(byteArrayOf(0x4C, 0x01)) + SUB_BLOCK_TERMINATOR
    val frame1 = imageDescriptor(left = 0, top = 0, width = 1, height = 1, imageData = frame1ImageData)
    val trailer = byteArrayOf(0x3B)

    val bytes = header + lsd + globalColorTable +
        graphicControlExtension(5) + frame0 +
        graphicControlExtension(10) + frame1 +
        trailer

    val file = File.createTempFile("gif-frame-decoder-test-", ".gif")
    file.deleteOnExit()
    file.writeBytes(bytes)
    return file
}

class GifFrameDecoderTest {
    @Test
    fun `decodes both frames of a two-frame animated GIF with correct durations`() {
        val animation = decodeGifAnimation(writeTwoFrameGif())
        assertNotNull(animation)
        assertEquals(2, animation.frames.size)
        assertEquals(listOf(50, 100), animation.durationsMs)
        assertEquals(2, animation.totalFrameCount)
        assertFalse(animation.truncated)
    }

    @Test
    fun `frames decode to genuinely distinct pixels, not the same frame repeated`() {
        val animation = decodeGifAnimation(writeTwoFrameGif())
        assertNotNull(animation)
        val frame0Argb = animation.frames[0].asSkiaBitmap().getColor(0, 0)
        val frame1Argb = animation.frames[1].asSkiaBitmap().getColor(0, 0)
        val frame0Red = (frame0Argb shr 16) and 0xFF
        val frame0Blue = frame0Argb and 0xFF
        val frame1Red = (frame1Argb shr 16) and 0xFF
        val frame1Blue = frame1Argb and 0xFF
        assertTrue(frame0Red > 200 && frame0Blue < 50, "frame 0 should be red, was argb=0x${frame0Argb.toString(16)}")
        assertTrue(frame1Blue > 200 && frame1Red < 50, "frame 1 should be blue, was argb=0x${frame1Argb.toString(16)}")
    }

    @Test
    fun `maxFrames caps decoding and reports truncation`() {
        val animation = decodeGifAnimation(writeTwoFrameGif(), maxFrames = 1)
        assertNotNull(animation)
        assertEquals(1, animation.frames.size)
        assertEquals(2, animation.totalFrameCount)
        assertTrue(animation.truncated)
    }

    @Test
    fun `returns null for a file that is not a valid GIF`() {
        val file = File.createTempFile("gif-frame-decoder-test-not-a-gif-", ".gif")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertEquals(null, decodeGifAnimation(file))
    }
}
