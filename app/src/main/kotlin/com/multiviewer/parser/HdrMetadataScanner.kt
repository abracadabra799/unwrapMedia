package com.multiviewer.parser

import java.io.File

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

data class HdrScanResult(
    val codec: VideoCodec,
    val records: List<FrameHdrRecord>,
    val container: ContainerHdrStatic,
    val warnings: List<String>,
)

private const val PROGRESS_EVERY_SAMPLES = 64

// Sequential pass over every video sample. Reads only NAL/OBU headers and SEI/metadata payloads.
// Returns null for an unsupported tree or when isCancelled() turns true; one unreadable sample
// becomes a Malformed unit instead of failing the scan.
fun scanHdrMetadata(
    file: File,
    root: BoxNode,
    isCancelled: () -> Boolean = { false },
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): HdrScanResult? = ByteReader.open(file).use { reader ->
    val table = buildVideoSampleTable(reader, root) ?: return@use null
    val container = readContainerHdrStatic(reader, root)
    val total = table.samples.size
    val scanned = ArrayList<Pair<VideoSample, List<MetadataUnit>>>(total)
    for ((i, sample) in table.samples.withIndex()) {
        if (isCancelled()) return@use null
        val units = try {
            readFrameMetadataUnits(reader, table.codec, table.nalLengthSize, sample.offset, sample.size)
        } catch (e: Exception) {
            listOf(
                MetadataUnit(
                    MetadataSource.SAMPLE_STRUCTURE, -1, "unreadable sample", sample.offset, sample.size, 0,
                    DecodedMetadata.Malformed(e.message ?: e.toString()),
                ),
            )
        }
        scanned += sample to units
        if ((i + 1) % PROGRESS_EVERY_SAMPLES == 0 || i == total - 1) onProgress(i + 1, total)
    }
    val records = scanned
        .sortedWith(compareBy({ it.first.decodeTime + it.first.compositionOffset }, { it.first.index }))
        .mapIndexed { frameNumber, (sample, units) ->
            FrameHdrRecord(
                frameNumber, sample.index,
                (sample.decodeTime + sample.compositionOffset).toDouble() / table.timescale,
                sample.isSync, units,
            )
        }
    HdrScanResult(table.codec, records, container, table.warnings)
}
