package com.multiviewer.parser

enum class ValueInterpretation { PARSED, UNINTERPRETED, UNPARSABLE }

data class BoxField(
    val name: String,
    val value: String,
    val offset: Long,
    val length: Long,
    val interpretation: ValueInterpretation = ValueInterpretation.PARSED,
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
    val interpretation: ValueInterpretation = ValueInterpretation.PARSED,
)
