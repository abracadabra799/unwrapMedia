package com.multiviewer.parser

enum class VideoCodec { H264, HEVC, AV1, VP9, APV, VVC }

enum class MetadataSource(val label: String) {
    H264_SEI("SEI"),
    HEVC_PREFIX_SEI("Prefix SEI"),
    HEVC_SUFFIX_SEI("Suffix SEI"),
    AV1_METADATA_OBU("Metadata OBU"),
    HEVC_UNSPECIFIED_NAL("NAL"),
    SAMPLE_STRUCTURE("Sample"),
}

// Physical units: CIE 1931 chromaticity and cd/m^2. The *Step fields carry the source encoding's
// quantization step (SEI / mdcv: 0.00002 chroma, 0.0001 cd/m^2; AV1 HDR_MDCV: 0.16 chroma, 24.8 max
// and 18.14 min luminance fixed point) so differently-encoded values can be compared within one
// step of the coarser encoding -- see sameMasteringDisplay.
data class MasteringDisplay(
    val redX: Double, val redY: Double,
    val greenX: Double, val greenY: Double,
    val blueX: Double, val blueY: Double,
    val whiteX: Double, val whiteY: Double,
    val maxLuminance: Double, val minLuminance: Double,
    val chromaStep: Double, val maxLuminanceStep: Double, val minLuminanceStep: Double,
)

data class ContentLightLevel(val maxCll: Int, val maxFall: Int)

// maxscl / averageMaxrgb / distribution percentiles are in the bitstream's 0.1 cd/m^2 units.
data class Hdr10PlusWindow(
    val maxscl: List<Int>,
    val averageMaxrgb: Int,
    val distribution: List<Pair<Int, Int>>,
    val fractionBrightPixels: Int,
    val toneMappingFlag: Boolean,
    val kneePointX: Int?,
    val kneePointY: Int?,
    val bezierCurveAnchors: List<Int>,
) {
    val maxSclNits: Double get() = maxscl.max() / 10.0
    val averageMaxrgbNits: Double get() = averageMaxrgb / 10.0
}

data class Hdr10PlusMetadata(
    val applicationVersion: Int,
    val numWindows: Int,
    val targetedSystemDisplayMaximumLuminance: Long,
    val windows: List<Hdr10PlusWindow>,
    val colorSaturationWeight: Int?,
)

sealed interface DecodedMetadata {
    data class Mastering(val value: MasteringDisplay) : DecodedMetadata
    data class LightLevel(val value: ContentLightLevel) : DecodedMetadata
    data class Hdr10Plus(val value: Hdr10PlusMetadata) : DecodedMetadata
    data class ItuT35(val countryCode: Int, val providerCode: Int?, val hexPreview: String) : DecodedMetadata
    data class UserDataUnregistered(val uuid: String, val asciiPreview: String) : DecodedMetadata
    data class RecoveryPoint(val recoveryCount: Int, val exactMatch: Boolean, val brokenLink: Boolean) : DecodedMetadata
    data class AlternativeTransfer(val preferredTransferCharacteristics: Int) : DecodedMetadata
    data object PresenceOnly : DecodedMetadata
    data class Malformed(val reason: String) : DecodedMetadata
}

// One SEI message, AV1 metadata OBU, or other reported unit. unitOffset/unitSize locate the
// containing NAL unit (from its header byte, length prefix excluded) or the whole OBU, so the hex
// viewer can highlight it -- SEI payload offsets themselves shift once emulation prevention is
// removed, so the containing unit is the honest range to show.
data class MetadataUnit(
    val source: MetadataSource,
    val typeCode: Int,
    val name: String,
    val unitOffset: Long,
    val unitSize: Long,
    val payloadSize: Int,
    val decoded: DecodedMetadata,
)
