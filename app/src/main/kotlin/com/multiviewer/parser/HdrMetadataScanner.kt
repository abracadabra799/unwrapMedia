package com.multiviewer.parser

// One frame in presentation order (frameNumber), with its decode-order sample index.
data class FrameHdrRecord(
    val frameNumber: Int,
    val sampleIndex: Int,
    val ptsSeconds: Double,
    val isSync: Boolean,
    val units: List<MetadataUnit>,
) {
    val hdr10Plus: Hdr10PlusMetadata?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.Hdr10Plus)?.value }
    val masteringDisplay: MasteringDisplay?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.Mastering)?.value }
    val contentLightLevel: ContentLightLevel?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.LightLevel)?.value }
}
