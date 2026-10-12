package com.multiviewer.parser.spatial

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialVideoAnalyzerTest {

    @Test
    fun `extractSpatialMetadata parses hero eye and optical parameters correctly`() {
        val eyesBox = BoxNode(
            type = "eyes",
            offset = 100L,
            headerSize = 8,
            size = 16L,
            fields = listOf(
                BoxField("hero_eye", "Left Eye", 108L, 1),
                BoxField("eye_views", "Stereo (Left + Right)", 109L, 1),
            ),
        )

        val metaBox = BoxNode(
            type = "meta",
            offset = 200L,
            headerSize = 8,
            size = 100L,
            fields = listOf(
                BoxField("com.apple.quicktime.spatial.eye-bidi-baseline", "64000", 220L, 4),
                BoxField("com.apple.quicktime.spatial.horizontal-field-of-view", "63400", 240L, 4),
                BoxField("com.apple.quicktime.spatial.disparity-adjustment", "-0.05", 260L, 4),
            ),
        )

        val root = BoxNode(
            type = "root",
            offset = 0L,
            headerSize = 0,
            size = 500L,
            children = listOf(eyesBox, metaBox),
        )

        val meta = extractSpatialMetadata(root)

        assertTrue(meta.hasSpatialBoxes)
        assertEquals("Left Eye", meta.heroEye)
        assertEquals("Stereo (Left + Right)", meta.stereoMode)
        assertEquals(64.0, meta.baselineMm) // 64000 um -> 64.0 mm
        assertEquals(63.4, meta.horizontalFovDeg) // 63400 millideg -> 63.4 deg
        assertEquals(-0.05, meta.disparityAdjustment)
    }

    @Test
    fun `extractSpatialMetadata reports no spatial boxes for standard 2D media`() {
        val root = BoxNode(
            type = "root",
            offset = 0L,
            headerSize = 0,
            size = 100L,
            children = listOf(
                BoxNode("moov", 0L, 8, 100L),
            ),
        )

        val meta = extractSpatialMetadata(root)
        assertFalse(meta.hasSpatialBoxes)
        assertEquals(null, meta.heroEye)
        assertEquals(null, meta.baselineMm)
    }
}
