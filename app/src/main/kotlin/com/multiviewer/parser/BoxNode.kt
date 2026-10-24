package com.multiviewer.parser

data class BoxField(
    val name: String,
    val value: String,
    val offset: Long,
    val length: Long,
)

enum class TableRowEncoding { UNSIGNED, SDTP_BITS, SIGNED_16, SIGNED_LAST_32 }

data class TableData(
    val columns: List<String>,
    val fieldWidths: List<Int>,
    val entriesStart: Long,
    val entryCount: Long,
    val rowEncoding: TableRowEncoding = TableRowEncoding.UNSIGNED,
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
