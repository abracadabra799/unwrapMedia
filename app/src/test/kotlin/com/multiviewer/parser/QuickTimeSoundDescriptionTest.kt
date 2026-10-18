package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real ffmpeg MOV output, see docs/validation/content-check-codec-fixtures/generate.sh. */
class QuickTimeSoundDescriptionTest {
    private fun fixture(name: String) = File(javaClass.getResource("/content-check-codecs/$name")!!.toURI())
    private fun audioEntry(name: String) = assertNotNull(firstAudioSampleEntry(parseFile(fixture(name)))).second
    private fun field(node: BoxNode, name: String) = node.fields.first { it.name == name }.value

    private fun assertWaveWithEsds(entry: BoxNode) {
        val wave = assertNotNull(entry.children.firstOrNull { it.type == "wave" }, entry.children.map { it.type }.toString())
        assertTrue(wave.children.any { it.type == "esds" }, wave.children.map { it.type }.toString())
        val all = boxDescendants(entry).toList()
        assertEquals(emptyList(), all.flatMap { it.warnings }, "no warnings anywhere in the sound description")
    }

    @Test fun version1ChildrenFollowTheSixteenExtraBytes() {
        val entry = audioEntry("avc-aac-qt-v1.mov")
        assertEquals("1", field(entry, "qt_sound_description_version"))
        assertEquals("48000.0", field(entry, "samplerate"))
        assertEquals("1", field(entry, "channelcount"))
        assertWaveWithEsds(entry)
    }

    @Test fun version2ReadsTheFloat64RateAndChannelCount() {
        val entry = audioEntry("avc-aac-qt-v2.mov")
        assertEquals("2", field(entry, "qt_sound_description_version"))
        assertEquals("96000.0", field(entry, "samplerate"))
        assertEquals("1", field(entry, "channelcount"))
        assertWaveWithEsds(entry)
    }

    @Test fun isoMp4EntryIsUnchanged() {
        val root = parseFile(File(javaClass.getResource("/content-check-codecs/avc-aac.mp4")!!.toURI()))
        val entry = assertNotNull(firstAudioSampleEntry(root)).second
        assertTrue(entry.fields.none { it.name == "qt_sound_description_version" })
        assertTrue(entry.children.any { it.type == "esds" })
    }
}
