package com.multiviewer.ui

internal fun thumbnailPrefetchIndices(
    visibleIndices: Set<Int>,
    columnCount: Int,
    itemCount: Int,
): Set<Int> {
    require(columnCount > 0) { "columnCount must be positive" }
    if (itemCount <= 0) return emptySet()

    val lastVisible = visibleIndices.asSequence().filter { it in 0 until itemCount }.maxOrNull()
        ?: return emptySet()
    val nextRowStart = ((lastVisible.toLong() / columnCount) + 1L) * columnCount
    if (nextRowStart >= itemCount) return emptySet()

    val nextRowEnd = minOf(nextRowStart + columnCount, itemCount.toLong())
    return (nextRowStart until nextRowEnd).mapTo(mutableSetOf()) { it.toInt() }
}

internal fun thumbnailPriorityForIndex(
    index: Int,
    visibleIndices: Set<Int>,
    prefetchIndices: Set<Int>,
): Int = when {
    index in visibleIndices -> 0
    index in prefetchIndices -> 1
    else -> 2
}
