package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailLoadingCoordinatorTest {
    private val file = File("sample.jpg")

    @Test
    fun `embedded preview is delivered before final decode`() {
        val events = mutableListOf<String>()
        coordinator(preview = { events += "preview"; "small" }, decode = { events += "decode"; "large" }, events = events)
            .load(file)
        assertEquals(listOf("preview", "publish:small:false", "decode", "persist:large", "publish:large:true"), events)
    }

    @Test
    fun `preview miss delivers only the final bitmap`() {
        val events = mutableListOf<String>()
        coordinator(preview = { events += "preview"; null }, decode = { events += "decode"; "large" }, events = events)
            .load(file)
        assertEquals(listOf("preview", "decode", "persist:large", "publish:large:true"), events)
    }

    @Test
    fun `final result is cached but embedded preview is not persisted`() {
        val persisted = mutableListOf<String>()
        coordinator(preview = { "small" }, decode = { "large" }, persist = { _, value -> persisted += value })
            .load(file)
        assertEquals(listOf("large"), persisted)
    }

    @Test
    fun `preview remains terminal when final decode fails without being persisted`() {
        val events = mutableListOf<String>()
        coordinator(preview = { "small" }, decode = { null }, events = events).load(file)
        assertEquals(listOf("publish:small:false", "publish:small:true"), events)
    }

    @Test
    fun `total decode failure discards listeners`() {
        val events = mutableListOf<String>()
        coordinator(preview = { null }, decode = { null }, events = events).load(file)
        assertEquals(listOf("finish"), events)
    }

    @Test
    fun `preview extractor exception falls through to final decoder`() {
        val events = mutableListOf<String>()
        coordinator(preview = { throw IllegalStateException("bad preview") }, decode = { events += "decode"; "large" }, events = events)
            .load(file)
        assertEquals(listOf("decode", "persist:large", "publish:large:true"), events)
    }

    @Test
    fun `final decoder exception still publishes preview as terminal result`() {
        val events = mutableListOf<String>()
        coordinator(preview = { "small" }, decode = { throw IllegalStateException("decode failed") }, events = events)
            .load(file)
        assertEquals(listOf("publish:small:false", "publish:small:true"), events)
    }

    @Test
    fun `persistence exception does not prevent final publication`() {
        val events = mutableListOf<String>()
        coordinator(preview = { null }, decode = { "large" }, events = events,
            persist = { _, _ -> throw IllegalStateException("cache unavailable") }).load(file)
        assertEquals(listOf("publish:large:true"), events)
    }

    private fun coordinator(
        preview: (File) -> String?,
        decode: (File) -> String?,
        events: MutableList<String> = mutableListOf(),
        persist: (File, String) -> Unit = { _, value -> events += "persist:$value" },
    ) = ThumbnailLoadCoordinator(
        preview = preview,
        decodeFinal = decode,
        persistFinal = persist,
        publish = { _, value, isFinal -> events += "publish:$value:$isFinal" },
        finishWithoutBitmap = { events += "finish" },
    )
}
