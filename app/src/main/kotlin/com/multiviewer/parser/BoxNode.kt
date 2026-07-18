package com.multiviewer.parser

data class BoxField(
    val name: String,
    val value: String,
    val offset: Long,
    val length: Long,
)

data class TableData(
    val columns: List<String>,
    val fieldWidths: List<Int>,
    val entriesStart: Long,
    val entryCount: Long,
)

data class GridData(
    val columns: Int,
    val rows: Int,
    val values: List<String>,
)

data class BoxNode(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    val children: List<BoxNode> = emptyList(),
    val fields: List<BoxField> = emptyList(),
    val warnings: List<String> = emptyList(),
    val summary: String? = null,
    val table: TableData? = null,
    val grid: GridData? = null,
)
