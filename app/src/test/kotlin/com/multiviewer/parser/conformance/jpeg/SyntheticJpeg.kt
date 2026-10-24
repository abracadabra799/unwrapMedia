package com.multiviewer.parser.conformance.jpeg

/** Marker segment 0xFF<marker> + Lx + payload. */
internal fun seg(marker: Int, vararg payload: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) +
        payload.map { it.toByte() }.toByteArray()

internal fun seg(marker: Int, payload: ByteArray): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

internal val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
internal val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
internal fun rst(m: Int) = byteArrayOf(0xFF.toByte(), (0xD0 + m).toByte())

internal fun jfifApp0(major: Int = 1, minor: Int = 2, units: Int = 0, hd: Int = 1, vd: Int = 1) =
    seg(0xE0, 0x4A, 0x46, 0x49, 0x46, 0x00, major, minor, units, hd shr 8, hd and 0xFF, vd shr 8, vd and 0xFF, 0, 0)

/** DQT with one 8-bit table at [tq] (all elements 1). */
internal fun dqt(tq: Int = 0, pq: Int = 0) = seg(0xDB, byteArrayOf(((pq shl 4) or tq).toByte()) + ByteArray(if (pq == 0) 64 else 128) { 1 })

/** DHT with one table class [tc], destination [th]: one code of length 1 → value 0. */
internal fun dht(tc: Int, th: Int) = seg(0xC4, byteArrayOf(((tc shl 4) or th).toByte(), 1) + ByteArray(15) + byteArrayOf(0))

/** SOF[n] for [comps] = (id, h, v, tq). */
internal fun sof(marker: Int = 0xC0, p: Int = 8, y: Int = 16, x: Int = 16, comps: List<IntArray> = listOf(intArrayOf(1, 1, 1, 0))) =
    seg(marker, byteArrayOf(p.toByte(), (y shr 8).toByte(), y.toByte(), (x shr 8).toByte(), x.toByte(), comps.size.toByte()) +
        comps.flatMap { listOf(it[0].toByte(), ((it[1] shl 4) or it[2]).toByte(), it[3].toByte()) }.toByteArray())

/** SOS for [comps] = (selector, td, ta). */
internal fun sos(comps: List<IntArray> = listOf(intArrayOf(1, 0, 0)), ss: Int = 0, se: Int = 63, ah: Int = 0, al: Int = 0) =
    seg(0xDA, byteArrayOf(comps.size.toByte()) + comps.flatMap { listOf(it[0].toByte(), ((it[1] shl 4) or it[2]).toByte()) }.toByteArray() +
        byteArrayOf(ss.toByte(), se.toByte(), ((ah shl 4) or al).toByte()))

internal fun dri(ri: Int) = seg(0xDD, ri shr 8, ri and 0xFF)

/** Entropy-coded filler bytes (no 0xFF). */
internal fun ecs(n: Int = 4) = ByteArray(n) { 0x55 }

/** A minimal valid baseline JFIF image: 16x16 grey, one scan, 4 MCUs of 8x8. */
internal fun baseline(
    app: ByteArray = jfifApp0(),
    tables: ByteArray = dqt() + dht(0, 0) + dht(1, 0),
    frame: ByteArray = sof(),
    scan: ByteArray = sos() + ecs(),
    tail: ByteArray = EOI,
) = SOI + app + tables + frame + scan + tail

