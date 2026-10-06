package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailRequestRegistryTest {
    @Test
    fun `prefetch reservation deduplicates without placeholder listeners`() {
        val registry = ThumbnailRequestRegistry<String>()
        val delivered = mutableListOf<String>()

        assertTrue(registry.ensure("prefetched"))
        assertFalse(registry.ensure("prefetched"))
        assertFalse(registry.add("prefetched", delivered::add))

        registry.publish("prefetched", "final", isFinal = true).forEach { it("final") }
        assertEquals(listOf("final"), delivered)
    }

    @Test
    fun `non-final preview keeps listeners for the final bitmap`() {
        val registry = ThumbnailRequestRegistry<String>()
        val delivered = mutableListOf<String>()
        registry.add("image", delivered::add)
        registry.add("image", delivered::add)

        registry.publish("image", "preview", isFinal = false).forEach { it("preview") }

        assertEquals(listOf("preview", "preview"), delivered)
        registry.publish("image", "final", isFinal = true).forEach { it("final") }
        assertEquals(listOf("preview", "preview", "final", "final"), delivered)
    }

    @Test
    fun `final bitmap completes and removes all listeners`() {
        val registry = ThumbnailRequestRegistry<String>()
        val delivered = mutableListOf<String>()
        registry.add("image", delivered::add)
        registry.add("image", delivered::add)

        registry.publish("image", "final", isFinal = true).forEach { it("final") }

        assertEquals(listOf("final", "final"), delivered)
        assertEquals(emptyList(), registry.publish("image", "late", isFinal = true))
    }
}
