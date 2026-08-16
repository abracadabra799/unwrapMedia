package com.multiviewer.parser

private val PROFILE_NAMES = mapOf(
    33 to "422-10", 44 to "422-12", 55 to "444-10", 66 to "444-12",
    77 to "4444-10", 88 to "4444-12", 99 to "400-10",
    140 to "444-16C12", 144 to "4444-16C12",
)
private const val MB_SIZE = 16 // RFC 9924 SS4.2: MbWidth = MbHeight = 16

enum class ApvChromaFormat { YUV_400, YUV_422, YUV_444, YUV_4444, RESERVED }

private fun chromaFormatFor(chromaFormatIdc: Int): ApvChromaFormat = when (chromaFormatIdc) {
    0 -> ApvChromaFormat.YUV_400
    2 -> ApvChromaFormat.YUV_422
    3 -> ApvChromaFormat.YUV_444
    4 -> ApvChromaFormat.YUV_4444
    else -> ApvChromaFormat.RESERVED
}

data class ApvFrameHeader(
    val profileIdc: Int, val profileName: String?,
    val levelIdc: Int, val bandIdc: Int,
    val frameWidth: Int, val frameHeight: Int,
    val chromaFormat: ApvChromaFormat, val chromaFormatIdc: Int, val bitDepth: Int,
    val colorPrimaries: Int?, val transferCharacteristics: Int?, val matrixCoefficients: Int?, val fullRangeFlag: Boolean?,
    val tileWidthInMbs: Int, val tileHeightInMbs: Int, val tileCount: Int,
)

// Parses frame_header() (RFC 9924 SS5.3.5/SS5.3.6/SS5.3.8), verified field-by-field against a real
// frame's bytes during planning (see this plan's Technical Foundation section) -- stops right after
// tile_info(), never touches tile/coefficient data. Returns null on truncated input, or if
// use_q_matrix is set (quantization_matrix() isn't parsed -- see this plan's Global Constraints).
fun parseApvFrameHeader(framePayload: ByteArray): ApvFrameHeader? {
    return try {
        val reader = BitReader(framePayload)
        val profileIdc = reader.readBits(8)
        val levelIdc = reader.readBits(8)
        val bandIdc = reader.readBits(3)
        reader.readBits(5) // reserved_zero_5bits
        val frameWidth = reader.readBits(24)
        val frameHeight = reader.readBits(24)
        val chromaFormatIdc = reader.readBits(4)
        val bitDepthMinus8 = reader.readBits(4)
        reader.readBits(8) // capture_time_distance
        reader.readBits(8) // reserved_zero_8bits (end of frame_info())
        reader.readBits(8) // reserved_zero_8bits (frame_header()'s own)

        val colorDescriptionPresentFlag = reader.readFlag()
        var colorPrimaries: Int? = null
        var transferCharacteristics: Int? = null
        var matrixCoefficients: Int? = null
        var fullRangeFlag: Boolean? = null
        if (colorDescriptionPresentFlag) {
            colorPrimaries = reader.readBits(8)
            transferCharacteristics = reader.readBits(8)
            matrixCoefficients = reader.readBits(8)
            fullRangeFlag = reader.readFlag()
        }

        val useQMatrix = reader.readFlag()
        if (useQMatrix) return null // quantization_matrix() not parsed -- see Global Constraints

        val tileWidthInMbs = reader.readBits(20)
        val tileHeightInMbs = reader.readBits(20)
        // tile_size_present_in_fh_flag and any per-tile sizes are read by the caller only if needed
        // later; this parser stops here, matching the plan's "tile grid dimensions and a derived
        // count only" curation scope.

        val frameWidthInMbs = (frameWidth + MB_SIZE - 1) / MB_SIZE
        val frameHeightInMbs = (frameHeight + MB_SIZE - 1) / MB_SIZE
        val tileCols = (frameWidthInMbs + tileWidthInMbs - 1) / tileWidthInMbs
        val tileRows = (frameHeightInMbs + tileHeightInMbs - 1) / tileHeightInMbs

        ApvFrameHeader(
            profileIdc = profileIdc, profileName = PROFILE_NAMES[profileIdc],
            levelIdc = levelIdc, bandIdc = bandIdc,
            frameWidth = frameWidth, frameHeight = frameHeight,
            chromaFormat = chromaFormatFor(chromaFormatIdc), chromaFormatIdc = chromaFormatIdc, bitDepth = bitDepthMinus8 + 8,
            colorPrimaries = colorPrimaries, transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients, fullRangeFlag = fullRangeFlag,
            tileWidthInMbs = tileWidthInMbs, tileHeightInMbs = tileHeightInMbs, tileCount = tileCols * tileRows,
        )
    } catch (e: Exception) {
        null
    }
}
