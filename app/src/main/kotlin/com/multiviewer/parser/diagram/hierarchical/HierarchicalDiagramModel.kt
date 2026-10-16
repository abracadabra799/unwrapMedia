package com.multiviewer.parser.diagram.hierarchical

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

/**
 * Multi-column hierarchical file specification diagram model, inspired by canonical specification
 * diagrams such as JEITA CP-3451 / ISO (e.g. `Downloads/fig2d.jpg`).
 *
 * Column 1 maps the top-level container / compressed file structure.
 * Projections connect container segments to expanded sub-columns (e.g. Structure of APP1,
 * Structure of SEF Trailer, Structure of moov, Structure of meta).
 */
data class HierarchicalDiagram(
    val title: String,
    val fileSize: Long,
    val columns: List<HierarchicalColumn>,
    val projections: List<HierarchicalProjection> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class HierarchicalColumn(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val sections: List<HierarchicalSection>,
)

data class HierarchicalSection(
    val id: String,
    val marker: String,
    val name: String,
    val description: String? = null,
    val offset: Long,
    val length: Long,
    val role: RegionRole = RegionRole.METADATA,
    val status: RegionStatus = RegionStatus.PARSED,
    val isOptional: Boolean = false,
    val isHeader: Boolean = false,
    val nodePath: List<Int>? = null,
)

data class HierarchicalProjection(
    val fromSectionId: String,
    val toColumnId: String,
    val color: Long = 0xFF334155L,
    val label: String? = null,
)
