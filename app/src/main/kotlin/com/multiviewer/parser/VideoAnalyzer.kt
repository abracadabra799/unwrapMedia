package com.multiviewer.parser

import com.multiviewer.ui.BitratePoint
import com.multiviewer.ui.VideoAnalysisData

object VideoAnalyzer {
    fun analyze(file: java.io.File, root: BoxNode): VideoAnalysisData {
        val bitratePoints = mutableListOf<BitratePoint>()
        val boxWeights = mutableMapOf<String, Long>()
        
        // 1. Box Weights for Treemap
        root.children.forEach { child ->
            boxWeights[child.type] = (boxWeights[child.type] ?: 0L) + child.size
        }
        
        // 2. Bitrate Analysis (Looking for stts and stsz)
        ByteReader.open(file).use { reader ->
            fun findBitrateInfo(node: BoxNode) {
                if (node.type == "trak") {
                    val sttsNode = findNode(node, "stts")
                    val stszNode = findNode(node, "stsz")
                    if (sttsNode != null && stszNode != null) {
                        calculateBitrate(reader, sttsNode, stszNode, bitratePoints)
                    }
                }
                node.children.forEach { findBitrateInfo(it) }
            }
            findBitrateInfo(root)
        }
        
        return VideoAnalysisData(
            bitratePoints = bitratePoints.take(100).sortedBy { it.timestampSeconds },
            boxWeights = boxWeights
        )
    }

    private fun findNode(root: BoxNode, type: String): BoxNode? {
        if (root.type == type) return root
        for (child in root.children) {
            val found = findNode(child, type)
            if (found != null) return found
        }
        return null
    }

    private fun calculateBitrate(reader: ByteReader, stts: BoxNode, stsz: BoxNode, points: MutableList<BitratePoint>) {
        val sttsTable = stts.table ?: return
        val stszTable = stsz.table ?: return
        
        var currentTime = 0.0
        var sampleIndex = 0
        
        for (i in 0 until sttsTable.entryCount.toInt().coerceAtMost(50)) {
            val count = reader.readUInt32(sttsTable.entriesStart + i * 8)
            val delta = reader.readUInt32(sttsTable.entriesStart + i * 8 + 4)
            
            for (j in 0 until count.toInt().coerceAtMost(10)) {
                if (sampleIndex >= stszTable.entryCount) break
                val size = reader.readUInt32(stszTable.entriesStart + sampleIndex * 4)
                
                if (delta > 0L) {
                    val kbps = (size * 8.0 / 1000.0) / (delta / 1000.0)
                    points.add(BitratePoint(currentTime, kbps))
                }
                
                currentTime += delta / 1000.0
                sampleIndex++
            }
        }
    }
}
