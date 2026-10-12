package com.multiviewer.parser

private const val T35_COUNTRY_USA = 0xB5
private const val T35_PROVIDER_SAMSUNG = 0x003C
private const val HDR10PLUS_PROVIDER_ORIENTED = 0x0001
private const val HDR10PLUS_APPLICATION_IDENTIFIER = 4
private const val HDR10PLUS_HEADER_BYTES = 6

// `t35` starts at itu_t_t35_country_code. HDR10+ is identified by country 0xB5, terminal provider
// 0x003C, provider-oriented code 0x0001 and application_identifier 4 (ST 2094-40 / CTA-861-G).
fun isHdr10PlusT35(t35: ByteArray): Boolean =
    t35.size >= HDR10PLUS_HEADER_BYTES &&
        (t35[0].toInt() and 0xFF) == T35_COUNTRY_USA &&
        u16At(t35, 1) == T35_PROVIDER_SAMSUNG &&
        u16At(t35, 3) == HDR10PLUS_PROVIDER_ORIENTED &&
        (t35[5].toInt() and 0xFF) == HDR10PLUS_APPLICATION_IDENTIFIER

private fun u16At(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

private class WindowStats(val maxscl: List<Int>, val averageMaxrgb: Int, val distribution: List<Pair<Int, Int>>, val fractionBrightPixels: Int)

// Field order follows ST 2094-40 Annex and matches ffmpeg's ff_parse_itu_t_t35_to_dynamic_hdr10_plus;
// verified against ffprobe 8.1.2 on the hevc_hdr10plus.mp4 fixture. Returns null when the header
// doesn't match or the payload ends early (BitReader throws IllegalStateException past its end).
fun decodeHdr10Plus(t35: ByteArray): Hdr10PlusMetadata? {
    if (!isHdr10PlusT35(t35)) return null
    return try {
        val r = BitReader(t35, startByteOffset = HDR10PLUS_HEADER_BYTES)
        val applicationVersion = r.readBits(8)
        val numWindows = r.readBits(2)
        if (numWindows < 1) return null
        repeat(numWindows - 1) {
            repeat(6) { r.readBits(16) } // window corners and ellipse centre
            r.readBits(8)                // rotation_angle
            repeat(3) { r.readBits(16) } // ellipse axes
            r.readBits(1)                // overlap_process_option
        }
        val targeted = r.readBits(27).toLong()
        if (r.readFlag()) skipPeakLuminanceMatrix(r)
        val stats = List(numWindows) {
            val maxscl = List(3) { r.readBits(17) }
            val average = r.readBits(17)
            val count = r.readBits(4)
            val distribution = List(count) {
                val percentage = r.readBits(7)
                percentage to r.readBits(17)
            }
            WindowStats(maxscl, average, distribution, r.readBits(10))
        }
        if (r.readFlag()) skipPeakLuminanceMatrix(r)
        val windows = stats.map { s ->
            val toneMapping = r.readFlag()
            var kneeX: Int? = null
            var kneeY: Int? = null
            var anchors = emptyList<Int>()
            if (toneMapping) {
                kneeX = r.readBits(12)
                kneeY = r.readBits(12)
                val anchorCount = r.readBits(4)
                anchors = List(anchorCount) { r.readBits(10) }
            }
            Hdr10PlusWindow(s.maxscl, s.averageMaxrgb, s.distribution, s.fractionBrightPixels, toneMapping, kneeX, kneeY, anchors)
        }
        val saturation = if (r.readFlag()) r.readBits(6) else null
        Hdr10PlusMetadata(applicationVersion, numWindows, targeted, windows, saturation)
    } catch (e: IllegalStateException) {
        null
    }
}

private fun skipPeakLuminanceMatrix(r: BitReader) {
    val rows = r.readBits(5)
    val cols = r.readBits(5)
    repeat(rows * cols) { r.readBits(4) }
}
