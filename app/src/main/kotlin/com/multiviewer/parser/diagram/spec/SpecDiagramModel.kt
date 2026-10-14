package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

/**
 * A spec-grade byte layout diagram model, inspired by canonical format architecture diagrams
 * (e.g. BMPfileFormat.svg.webp).
 *
 * Each diagram maps actual parsed bytes into 4-byte (32-bit) aligned rows and semantic blocks,
 * with dark header banners, reference arrows, and alignment callouts.
 */
data class SpecDiagram(
    val title: String,
    val fileSize: Long,
    val blocks: List<SpecBlock>,
    val arrows: List<SpecArrow> = emptyList(),
    val brackets: List<SpecBracket> = emptyList(),
    val callouts: List<SpecCallout> = emptyList(),
    val columnBytes: Int = 4,
)

data class SpecBlock(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val offset: Long,
    val length: Long,
    val role: RegionRole,
    val rows: List<SpecRow>,
)

data class SpecRow(
    val cells: List<SpecCell>,
    val rowOffset: Long,
)

data class SpecCell(
    val id: String,
    val name: String,
    val value: String? = null,
    val byteLength: Long,
    val colSpan: Int = 1,
    val role: RegionRole = RegionRole.METADATA,
    val status: RegionStatus = RegionStatus.PARSED,
    val isPadding: Boolean = false,
)

data class SpecArrow(
    val fromCellId: String,
    val toBlockId: String,
    val color: Long = 0xFF202124L,
    val label: String? = null,
)

data class SpecBracket(
    val fromOffset: Long,
    val toOffset: Long,
    val label: String,
    val color: Long = 0xFF7C3AEDL,
)

data class SpecCallout(
    val targetCellOrBlockId: String,
    val text: String,
)
