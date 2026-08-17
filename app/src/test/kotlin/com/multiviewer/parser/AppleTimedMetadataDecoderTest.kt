package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleTimedMetadataDecoderTest {

    @Test
    fun `decodes mebx track with keyd and timed samples`() {
        val trakBytes = buildMebxTrack()
        val reader = byteReaderOf(trakBytes)
        registerAllDecoders()

        val nodes = parseBoxes(reader, 0, trakBytes.size.toLong())
        val trak = nodes.firstOrNull { it.type == "trak" }
        assertNotNull(trak, "trak box should exist")

        val timedMetaNode = decodeTimedMetadataTrack(reader, trak)
        assertNotNull(timedMetaNode, "Timed Metadata node should be produced")
        assertEquals("Timed Metadata", timedMetaNode.type)
        assertEquals(2, timedMetaNode.children.size)

        // Sample 0: orientation 90 degrees
        val sample0 = timedMetaNode.children[0]
        assertTrue(sample0.summary?.contains("DTS=0") == true || sample0.type.contains("Sample 0"))

        // Sample 1: orientation 180 degrees
        val sample1 = timedMetaNode.children[1]
        assertTrue(sample1.type.contains("Sample 1"))

        reader.close()
    }

    @Test
    fun `enforces sample and byte limits on timed metadata decoding`() {
        val trakBytes = buildMebxTrack()
        val reader = byteReaderOf(trakBytes)
        registerAllDecoders()
        val trak = parseBoxes(reader, 0, trakBytes.size.toLong()).first { it.type == "trak" }

        val timedMetaNode = decodeTimedMetadataTrack(reader, trak, TimedMetadataLimits(maxSamples = 1))
        assertNotNull(timedMetaNode)
        assertEquals(1, timedMetaNode.children.size)
        assertTrue(timedMetaNode.warnings.any { it.contains("limit") || it.contains("exceeded") || it.contains("maxSamples") })

        reader.close()
    }

    private fun buildMebxTrack(): ByteArray {
        // We'll construct a self-contained byte array representing trak box + media data payload
        // trak:
        //   mdia:
        //     mdhd: timescale = 600, duration = 1200 (2 seconds)
        //     hdlr: handler_type = "mebx"
        //     minf:
        //       stbl:
        //         stsd:
        //           mebx (sample entry):
        //             keyd: 1 key: "com.apple.quicktime.video-orientation"
        //         stts: 2 samples, delta = 600 each
        //         stsz: sample_size = 0, 2 entries (size 4, size 4)
        //         stsc: 1 chunk, 2 samples_per_chunk
        //         stco: chunk 1 offset = sampleDataOffset
        // Media data area at sampleDataOffset:
        //   Sample 0: int32 90 (degrees)
        //   Sample 1: int32 180 (degrees)

        val out = ByteArrayOutputStream()

        // mdhd: version=0, creation=0, mod=0, timescale=600, duration=1200
        val mdhdBaos = ByteArrayOutputStream()
        val mdhdDos = DataOutputStream(mdhdBaos)
        mdhdDos.writeInt(0) // version=0, flags=0
        mdhdDos.writeInt(0) // creation_time
        mdhdDos.writeInt(0) // modification_time
        mdhdDos.writeInt(600) // timescale
        mdhdDos.writeInt(1200) // duration
        mdhdDos.writeShort(0) // language
        mdhdDos.writeShort(0) // pre_defined
        val mdhdBox = wrapBox("mdhd", mdhdBaos.toByteArray())

        // hdlr: handler_type = "mebx"
        val hdlrBaos = ByteArrayOutputStream()
        val hdlrDos = DataOutputStream(hdlrBaos)
        hdlrDos.writeInt(0) // version & flags
        hdlrDos.writeInt(0) // pre_defined
        hdlrDos.write("mebx".toByteArray(StandardCharsets.US_ASCII))
        hdlrDos.writeInt(0)
        hdlrDos.writeInt(0)
        hdlrDos.writeInt(0)
        hdlrDos.writeByte(0)
        val hdlrBox = wrapBox("hdlr", hdlrBaos.toByteArray())

        // keyd inside mebx sample entry inside stsd
        val keydBaos = ByteArrayOutputStream()
        val keydDos = DataOutputStream(keydBaos)
        keydDos.writeInt(0) // version & flags
        keydDos.writeInt(1) // entry_count = 1
        val keyStr = "com.apple.quicktime.video-orientation".toByteArray(StandardCharsets.UTF_8)
        keydDos.writeInt(8 + keyStr.size)
        keydDos.write("mdta".toByteArray(StandardCharsets.US_ASCII))
        keydDos.write(keyStr)
        val keydBox = wrapBox("keyd", keydBaos.toByteArray())

        // mebx sample entry: 8 bytes reserved/data_ref_index + keydBox
        val mebxEntryBaos = ByteArrayOutputStream()
        val mebxEntryDos = DataOutputStream(mebxEntryBaos)
        mebxEntryDos.write(ByteArray(6)) // reserved
        mebxEntryDos.writeShort(1) // data_reference_index
        mebxEntryDos.write(keydBox)
        val mebxEntryBox = wrapBox("mebx", mebxEntryBaos.toByteArray())

        // stsd: version=0, flags=0, entry_count=1 + mebxEntryBox
        val stsdBaos = ByteArrayOutputStream()
        val stsdDos = DataOutputStream(stsdBaos)
        stsdDos.writeInt(0) // version & flags
        stsdDos.writeInt(1) // entry_count = 1
        stsdDos.write(mebxEntryBox)
        val stsdBox = wrapBox("stsd", stsdBaos.toByteArray())

        // stts: 1 entry: count=2, delta=600
        val sttsBaos = ByteArrayOutputStream()
        val sttsDos = DataOutputStream(sttsBaos)
        sttsDos.writeInt(0) // version & flags
        sttsDos.writeInt(1) // entry_count = 1
        sttsDos.writeInt(2) // sample_count = 2
        sttsDos.writeInt(600) // sample_delta = 600
        val sttsBox = wrapBox("stts", sttsBaos.toByteArray())

        // stsz: sample_size=0, entry_count=2, sizes: 4, 4
        val stszBaos = ByteArrayOutputStream()
        val stszDos = DataOutputStream(stszBaos)
        stszDos.writeInt(0) // version & flags
        stszDos.writeInt(0) // sample_size = 0 (variable)
        stszDos.writeInt(2) // entry_count = 2
        stszDos.writeInt(4) // sample 0 size = 4
        stszDos.writeInt(4) // sample 1 size = 4
        val stszBox = wrapBox("stsz", stszBaos.toByteArray())

        // stsc: 1 entry: first_chunk=1, samples_per_chunk=2, sample_desc=1
        val stscBaos = ByteArrayOutputStream()
        val stscDos = DataOutputStream(stscBaos)
        stscDos.writeInt(0) // version & flags
        stscDos.writeInt(1) // entry_count = 1
        stscDos.writeInt(1) // first_chunk = 1
        stscDos.writeInt(2) // samples_per_chunk = 2
        stscDos.writeInt(1) // sample_description_index = 1
        val stscBox = wrapBox("stsc", stscBaos.toByteArray())

        // stco will be calculated after assembling trak header size
        // Calculate size of everything before sample data:
        // trak (8) + mdia (8) + mdhd (mdhdBox.size) + hdlr (hdlrBox.size) + minf (8) + stbl (8) +
        // stsd (stsdBox.size) + stts (sttsBox.size) + stsz (stszBox.size) + stsc (stscBox.size) + stco (stcoBox.size: 8 + 4 + 4 + 4 = 20)
        val stcoBoxSize = 8 + 4 + 4 + 4 // 20 bytes
        val stblPayloadSize = stsdBox.size + sttsBox.size + stszBox.size + stscBox.size + stcoBoxSize
        val minfPayloadSize = 8 + stblPayloadSize // "stbl" box size
        val mdiaPayloadSize = mdhdBox.size + hdlrBox.size + 8 + minfPayloadSize // "minf" box size
        val trakPayloadSize = 8 + mdiaPayloadSize // "mdia" box size
        val trakTotalSize = 8 + trakPayloadSize

        val sampleDataOffset = trakTotalSize.toLong()

        // stco: 1 chunk at sampleDataOffset
        val stcoBaos = ByteArrayOutputStream()
        val stcoDos = DataOutputStream(stcoBaos)
        stcoDos.writeInt(0) // version & flags
        stcoDos.writeInt(1) // entry_count = 1
        stcoDos.writeInt(sampleDataOffset.toInt())
        val stcoBox = wrapBox("stco", stcoBaos.toByteArray())

        val stblBaos = ByteArrayOutputStream()
        stblBaos.write(stsdBox)
        stblBaos.write(sttsBox)
        stblBaos.write(stszBox)
        stblBaos.write(stscBox)
        stblBaos.write(stcoBox)
        val stblBox = wrapBox("stbl", stblBaos.toByteArray())

        val minfBaos = ByteArrayOutputStream()
        minfBaos.write(stblBox)
        val minfBox = wrapBox("minf", minfBaos.toByteArray())

        val mdiaBaos = ByteArrayOutputStream()
        mdiaBaos.write(mdhdBox)
        mdiaBaos.write(hdlrBox)
        mdiaBaos.write(minfBox)
        val mdiaBox = wrapBox("mdia", mdiaBaos.toByteArray())

        val trakBaos = ByteArrayOutputStream()
        trakBaos.write(mdiaBox)
        val trakBox = wrapBox("trak", trakBaos.toByteArray())

        out.write(trakBox)

        // Append sample data payload
        val sampleDos = DataOutputStream(out)
        sampleDos.writeInt(90) // sample 0: 90 degrees
        sampleDos.writeInt(180) // sample 1: 180 degrees

        return out.toByteArray()
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
