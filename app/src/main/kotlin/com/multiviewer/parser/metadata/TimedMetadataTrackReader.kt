package com.multiviewer.parser.metadata

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SensorType {
    GYRO,
    ACCEL,
    GPS,
    ORIENTATION,
    GENERIC,
}

data class SensorSamplePoint(
    val sampleIndex: Int,
    val timestampSeconds: Double,
    val type: SensorType,
    val x: Double,
    val y: Double,
    val z: Double,
    val label: String? = null,
)

data class TimedMetadataReport(
    val trackId: Long,
    val trackFormat: String, // "camm", "gpmd", "mebx", "meta"
    val totalSamples: Int,
    val durationSeconds: Double,
    val sampleRateHz: Double,
    val samples: List<SensorSamplePoint>,
)

private fun findBoxes(node: BoxNode, type: String): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    fun walk(curr: BoxNode) {
        if (curr.type == type) result.add(curr)
        curr.children.forEach { walk(it) }
    }
    walk(node)
    return result
}

data class TimedTrackInfo(
    val trackNode: BoxNode,
    val trackId: Long,
    val format: String,
)

fun findTimedMetadataTracks(root: BoxNode): List<TimedTrackInfo> {
    val traks = findBoxes(root, "trak")
    val result = mutableListOf<TimedTrackInfo>()

    for (trak in traks) {
        val tkhd = trak.children.find { it.type == "tkhd" }
        val trackId = tkhd?.fields?.find { it.name == "track_id" }?.value?.toLongOrNull() ?: 1L

        val mdia = trak.children.find { it.type == "mdia" } ?: continue
        val hdlr = mdia.children.find { it.type == "hdlr" }
        val handlerType = hdlr?.fields?.find { it.name == "handler_type" }?.value?.lowercase()?.trim() ?: ""

        val stbl = findBoxes(mdia, "stbl").firstOrNull()
        val stsd = stbl?.children?.find { it.type == "stsd" }
        val sampleEntryType = stsd?.children?.firstOrNull()?.type?.lowercase()?.trim() ?: ""

        val format = when {
            handlerType == "camm" || sampleEntryType == "camm" -> "camm"
            handlerType == "gpmd" || sampleEntryType == "gpmd" -> "gpmd"
            handlerType in listOf("mebx", "mdta", "meta") -> handlerType
            else -> null
        }

        if (format != null) {
            result.add(TimedTrackInfo(trak, trackId, format))
        }
    }
    return result
}

fun readTimedMetadataReport(
    file: File,
    root: BoxNode,
    maxSamplesToRead: Int = 100_000,
): TimedMetadataReport? {
    val tracks = findTimedMetadataTracks(root)
    if (tracks.isEmpty()) return null
    val targetTrack = tracks.first()

    return ByteReader.open(file).use { reader ->
        val mdia = targetTrack.trackNode.children.find { it.type == "mdia" } ?: return@use null
        val mdhd = mdia.children.find { it.type == "mdhd" }
        val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()?.coerceAtLeast(1L) ?: 1000L

        val stbl = findBoxes(mdia, "stbl").firstOrNull() ?: return@use null

        // Timing from stts
        val stts = stbl.children.find { it.type == "stts" }
        val dtsList = mutableListOf<Long>()
        if (stts != null && stts.size - stts.headerSize >= 8) {
            val pStart = stts.offset + stts.headerSize
            val count = reader.readUInt32(pStart + 4)
            var currentDts = 0L
            var pos = pStart + 8
            val pEnd = stts.offset + stts.size
            for (i in 0 until count.toInt()) {
                if (pos + 8 > pEnd) break
                val sCount = reader.readUInt32(pos)
                val sDelta = reader.readUInt32(pos + 4)
                for (c in 0 until sCount.toInt()) {
                    dtsList.add(currentDts)
                    currentDts += sDelta
                    if (dtsList.size >= maxSamplesToRead) break
                }
                pos += 8
                if (dtsList.size >= maxSamplesToRead) break
            }
        }

        // Sizes from stsz
        val stsz = stbl.children.find { it.type == "stsz" } ?: return@use null
        val pStartStsz = stsz.offset + stsz.headerSize
        val pEndStsz = stsz.offset + stsz.size
        if (pEndStsz - pStartStsz < 12) return@use null
        val uniformSize = reader.readUInt32(pStartStsz + 4)
        val sampleCount = reader.readUInt32(pStartStsz + 8).toInt().coerceAtMost(maxSamplesToRead)

        val sizes = mutableListOf<Long>()
        if (uniformSize > 0) {
            repeat(sampleCount) { sizes.add(uniformSize) }
        } else {
            var pos = pStartStsz + 12
            for (i in 0 until sampleCount) {
                if (pos + 4 > pEndStsz) break
                sizes.add(reader.readUInt32(pos))
                pos += 4
            }
        }

        // Chunk offsets from stco / co64
        val stco = stbl.children.find { it.type == "stco" }
        val co64 = stbl.children.find { it.type == "co64" }
        val chunkOffsets = mutableListOf<Long>()
        if (stco != null) {
            val ps = stco.offset + stco.headerSize
            val cnt = reader.readUInt32(ps + 4).toInt()
            var pos = ps + 8
            val pe = stco.offset + stco.size
            for (i in 0 until cnt) {
                if (pos + 4 > pe) break
                chunkOffsets.add(reader.readUInt32(pos))
                pos += 4
            }
        } else if (co64 != null) {
            val ps = co64.offset + co64.headerSize
            val cnt = reader.readUInt32(ps + 4).toInt()
            var pos = ps + 8
            val pe = co64.offset + co64.size
            for (i in 0 until cnt) {
                if (pos + 8 > pe) break
                chunkOffsets.add(reader.readUInt64(pos))
                pos += 8
            }
        }

        // stsc
        data class Stsc(val firstChunk: Int, val samplesPerChunk: Int)
        val stscList = mutableListOf<Stsc>()
        val stscNode = stbl.children.find { it.type == "stsc" }
        if (stscNode != null) {
            val ps = stscNode.offset + stscNode.headerSize
            val pe = stscNode.offset + stscNode.size
            val cnt = reader.readUInt32(ps + 4).toInt()
            var pos = ps + 8
            for (i in 0 until cnt) {
                if (pos + 12 > pe) break
                stscList.add(Stsc(reader.readUInt32(pos).toInt(), reader.readUInt32(pos + 4).toInt()))
                pos += 12
            }
        }

        // Compute sample offsets
        val sampleOffsets = mutableListOf<Long>()
        if (chunkOffsets.isNotEmpty() && stscList.isNotEmpty() && sizes.isNotEmpty()) {
            var sampleIdx = 0
            for (chunkIdx in 1..chunkOffsets.size) {
                val entry = stscList.lastOrNull { it.firstChunk <= chunkIdx } ?: stscList.first()
                var currentOff = chunkOffsets[chunkIdx - 1]
                for (s in 0 until entry.samplesPerChunk) {
                    if (sampleIdx >= sizes.size) break
                    sampleOffsets.add(currentOff)
                    currentOff += sizes[sampleIdx]
                    sampleIdx++
                }
                if (sampleIdx >= sizes.size) break
            }
        }

        val totalToProcess = minOf(sizes.size, sampleOffsets.size)
        val points = mutableListOf<SensorSamplePoint>()

        for (i in 0 until totalToProcess) {
            val offset = sampleOffsets[i]
            val size = sizes[i]
            val dts = dtsList.getOrElse(i) { i.toLong() }
            val tSec = dts.toDouble() / timescale.toDouble()

            if (offset <= 0 || offset + size > reader.length || size < 4) continue

            when (targetTrack.format) {
                "camm" -> {
                    // 2 bytes reserved, 2 bytes type
                    val type = reader.readUInt16(offset + 2)
                    when (type) {
                        0 -> { // Gyro (3 x float32: rad/s)
                            if (size >= 16) {
                                val bytes = reader.readBytes(offset + 4, 12)
                                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                val gx = buf.float.toDouble()
                                val gy = buf.float.toDouble()
                                val gz = buf.float.toDouble()
                                points.add(SensorSamplePoint(i, tSec, SensorType.GYRO, gx, gy, gz, "Gyro (rad/s)"))
                            }
                        }
                        1 -> { // Accel (3 x float32: m/s^2)
                            if (size >= 16) {
                                val bytes = reader.readBytes(offset + 4, 12)
                                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                val ax = buf.float.toDouble()
                                val ay = buf.float.toDouble()
                                val az = buf.float.toDouble()
                                points.add(SensorSamplePoint(i, tSec, SensorType.ACCEL, ax, ay, az, "Accel (m/s²)"))
                            }
                        }
                        5 -> { // GPS (lat, lon, alt)
                            if (size >= 36) {
                                val bytes = reader.readBytes(offset + 4, 32)
                                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                val _time = buf.double
                                val _fix = buf.int
                                val lat = buf.double
                                val lon = buf.double
                                val alt = buf.double
                                points.add(SensorSamplePoint(i, tSec, SensorType.GPS, lat, lon, alt, "GPS (Lat/Lon/Alt)"))
                            }
                        }
                        else -> {
                            points.add(SensorSamplePoint(i, tSec, SensorType.GENERIC, 0.0, 0.0, 0.0, "CAMM Type $type"))
                        }
                    }
                }
                "gpmd" -> {
                    // GoPro KLV: read first 4 bytes fourcc
                    val fourcc = reader.readFourCC(offset)
                    when (fourcc) {
                        "ACCL" -> {
                            val ax = if (size >= 14) reader.readUInt16(offset + 8).toShort().toDouble() / 100.0 else 0.0
                            val ay = if (size >= 14) reader.readUInt16(offset + 10).toShort().toDouble() / 100.0 else 0.0
                            val az = if (size >= 14) reader.readUInt16(offset + 12).toShort().toDouble() / 100.0 else 0.0
                            points.add(SensorSamplePoint(i, tSec, SensorType.ACCEL, ax, ay, az, "GoPro ACCL"))
                        }
                        "GYRO" -> {
                            val gx = if (size >= 14) reader.readUInt16(offset + 8).toShort().toDouble() / 100.0 else 0.0
                            val gy = if (size >= 14) reader.readUInt16(offset + 10).toShort().toDouble() / 100.0 else 0.0
                            val gz = if (size >= 14) reader.readUInt16(offset + 12).toShort().toDouble() / 100.0 else 0.0
                            points.add(SensorSamplePoint(i, tSec, SensorType.GYRO, gx, gy, gz, "GoPro GYRO"))
                        }
                        else -> {
                            points.add(SensorSamplePoint(i, tSec, SensorType.GENERIC, 0.0, 0.0, 0.0, "GPMD $fourcc"))
                        }
                    }
                }
                else -> {
                    // Generic / mebx / mdta
                    points.add(SensorSamplePoint(i, tSec, SensorType.GENERIC, 0.0, 0.0, 0.0, targetTrack.format))
                }
            }
        }

        val durationSec = if (points.isNotEmpty()) {
            (points.last().timestampSeconds - points.first().timestampSeconds).coerceAtLeast(0.01)
        } else 1.0
        val sampleRate = if (durationSec > 0 && points.size > 1) points.size.toDouble() / durationSec else 0.0

        TimedMetadataReport(
            trackId = targetTrack.trackId,
            trackFormat = targetTrack.format,
            totalSamples = points.size,
            durationSeconds = durationSec,
            sampleRateHz = sampleRate,
            samples = points,
        )
    }
}
