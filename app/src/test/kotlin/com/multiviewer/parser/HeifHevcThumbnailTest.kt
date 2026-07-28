package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HeifHevcThumbnailTest {
    // Splits a real ffmpeg-produced Annex-B HEVC elementary stream into individual NAL units
    // (without their start codes), so the test can pick out real VPS/SPS/PPS/slice payloads to
    // build a synthetic HEIC-shaped fixture from -- not hand-crafted bytes, an actual encoder's
    // output.
    private fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.indices.map { idx ->
            val start = starts[idx]
            val end = if (idx + 1 < starts.size) {
                // Back up over the next start code (3 or 4 bytes, possibly with a leading 0x00).
                var e = starts[idx + 1] - 3
                if (e > start && data[e - 1] == 0.toByte()) e--
                e
            } else {
                data.size
            }
            data.copyOfRange(start, end)
        }
    }

    private fun nalType(nal: ByteArray): Int = (nal[0].toInt() and 0xFF) shr 1 and 0x3F

    private fun buildHvcCPayload(vps: ByteArray, sps: ByteArray, pps: ByteArray, lengthSizeMinusOne: Int): ByteArray {
        val header = ByteArray(23)
        header[0] = 1
        header[1] = 1
        header[12] = 90
        header[21] = (0xFC or lengthSizeMinusOne).toByte()
        header[22] = 3

        fun arrayBlock(nalUnitType: Int, nal: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(nalUnitType and 0x3F)
            out.write(0); out.write(1) // numNalus = 1
            out.write((nal.size shr 8) and 0xFF); out.write(nal.size and 0xFF)
            out.write(nal)
            return out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(arrayBlock(32, vps))
        out.write(arrayBlock(33, sps))
        out.write(arrayBlock(34, pps))
        return out.toByteArray()
    }

    @Test
    fun `extractHevcThumbnailAnnexB reconstructs a decodable Annex-B stream from a real encoder's NAL units`() {
        val rawH265 = File.createTempFile("hevc-thumb-source-", ".h265")
        rawH265.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=32x24:duration=1",
            "-frames:v", "1", "-c:v", "libx265", "-x265-params", "log-level=none",
            "-f", "hevc", rawH265.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val nals = splitAnnexBNalUnits(rawH265.readBytes())
        val vps = nals.first { nalType(it) == 32 }
        val sps = nals.first { nalType(it) == 33 }
        val pps = nals.first { nalType(it) == 34 }
        // HEVC NAL unit types 0-31 are VCL (the actual coded slice data); 32+ are non-VCL
        // (VPS/SPS/PPS/SEI/AUD/etc). ffmpeg's output here also includes a PREFIX_SEI (type 39)
        // *before* the real IDR slice -- picking "anything not VPS/SPS/PPS" would grab that SEI
        // instead of the slice, producing a stream with no actual picture data.
        val slice = nals.first { nalType(it) <= 31 }
        rawH265.delete()

        val hvcCPayload = buildHvcCPayload(vps, sps, pps, lengthSizeMinusOne = 3)

        // Synthetic "file": length-prefixed (4-byte BE) picture NAL, the item data an iloc extent
        // would point at.
        val itemBytes = ByteArrayOutputStream().apply {
            write((slice.size shr 24) and 0xFF); write((slice.size shr 16) and 0xFF)
            write((slice.size shr 8) and 0xFF); write(slice.size and 0xFF)
            write(slice)
        }.toByteArray()

        val hvcCOffset = 0L
        val itemOffset = hvcCPayload.size.toLong()
        val fileBytes = hvcCPayload + itemBytes
        val file = File.createTempFile("heic-hevc-thumb-fixture-", ".heic")
        file.deleteOnExit()
        file.writeBytes(fileBytes)

        val hvcC = BoxNode(type = "hvcC", offset = hvcCOffset, headerSize = 0, size = hvcCPayload.size.toLong())
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(hvcC))
        val ipmaItem = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 0)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", itemOffset.toString(), 0, 0), BoxField("length", itemBytes.size.toString(), 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val thmb = BoxNode(
            type = "thmb", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("from_item_ID", "1", 0, 0), BoxField("to_item_ID[0]", "99", 0, 0)),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(thmb))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf, iref, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val annexB = extractHevcThumbnailAnnexB(file, root)
        assertNotNull(annexB, "Expected a reconstructed Annex-B HEVC stream")

        // Prove it's genuinely decodable, not just non-null: feed it back through a real ffmpeg
        // and confirm it decodes to the original 32x24 picture.
        val reconstructed = File.createTempFile("hevc-thumb-reconstructed-", ".h265")
        reconstructed.deleteOnExit()
        reconstructed.writeBytes(annexB)
        val outPng = File.createTempFile("hevc-thumb-decoded-", ".png")
        outPng.deleteOnExit()
        val process = ProcessBuilder(
            "ffmpeg", "-y", "-f", "hevc", "-i", reconstructed.absolutePath, "-frames:v", "1", outPng.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor(10, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), "Expected ffmpeg to decode the reconstructed stream successfully")

        val decoded = javax.imageio.ImageIO.read(outPng)
        assertNotNull(decoded)
        assertEquals(32, decoded.width)
        assertEquals(24, decoded.height)

        file.delete()
        reconstructed.delete()
        outPng.delete()
    }
}
