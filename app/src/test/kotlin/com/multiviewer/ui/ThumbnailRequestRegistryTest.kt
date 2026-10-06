package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailRequestRegistryTest {
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
