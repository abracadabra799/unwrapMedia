package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameThumbnailDecoderTest {
    @Test
    fun `missingThumbnailRange returns null for an empty visible range`() {
        assertNull(missingThumbnailRange(IntRange.EMPTY, prefetchMargin = 5, frameCount = 100, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns null when frameCount is zero`() {
        assertNull(missingThumbnailRange(0..10, prefetchMargin = 5, frameCount = 0, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns the visible range expanded by the prefetch margin when nothing is cached`() {
        assertEquals(15..35, missingThumbnailRange(20..30, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns null when the whole expanded range is already cached`() {
        val cached = (15..35).toSet()
        assertNull(missingThumbnailRange(20..30, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = cached))
    }

    @Test
    fun `missingThumbnailRange clamps against zero at the start of the video`() {
        // visible 5..10 expanded by margin 10 would be -5..20; the negative end clamps to 0.
        assertEquals(0..20, missingThumbnailRange(5..10, prefetchMargin = 10, frameCount = 1000, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange clamps against frameCount minus one at the end of the video`() {
        // visible 95..99 expanded by margin 10 would be 85..109; frameCount=100 means valid
        // indices are 0..99, so the end clamps to 99.
        assertEquals(85..99, missingThumbnailRange(95..99, prefetchMargin = 10, frameCount = 100, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange spans from the lowest to the highest missing index when partially cached`() {
        // Expanded range is 10..20; only 14 is missing (10-13 and 15-20 already cached) --
        // returns the single-index range 14..14, not the full 10..20 span.
        val cached = (10..20).toSet() - 14
        assertEquals(14..14, missingThumbnailRange(15..15, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = cached))
    }
}
