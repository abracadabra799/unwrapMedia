package com.multiviewer.parser

data class BoxField(
    val name: String,
    val value: String,
    val offset: Long,
    val length: Long,
)

data class TableData(
    val columns: List<String>,
    val rows: List<List<Long>>,
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
)
