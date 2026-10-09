package com.multiviewer.ui

import java.io.File

/** Pair of files being prepared in the compare explorer; slots may be picked from any folders. */
data class CompareExplorerSelection(
    val slotA: File? = null,
    val slotB: File? = null,
) {
    val selectedCount: Int get() = listOfNotNull(slotA, slotB).size
    val isReadyToCompare: Boolean get() = slotA != null && slotB != null

    fun assign(slotIndex: Int, file: File): CompareExplorerSelection {
        val sameAsOther = when (slotIndex) {
            0 -> slotB?.absolutePath == file.absolutePath
            1 -> slotA?.absolutePath == file.absolutePath
            else -> return this
        }
        return when (slotIndex) {
            0 -> copy(slotA = file, slotB = if (sameAsOther) null else slotB)
            else -> copy(slotB = file, slotA = if (sameAsOther) null else slotA)
        }
    }

    /** Thumbnail selection preserves the existing oldest-first two-file selection behavior. */
    fun toggleThumbnail(file: File): CompareExplorerSelection {
        if (slotA?.absolutePath == file.absolutePath) return copy(slotA = slotB, slotB = null)
        if (slotB?.absolutePath == file.absolutePath) return copy(slotB = null)
        if (slotA == null) return copy(slotA = file)
        if (slotB == null) return copy(slotB = file)
        return copy(slotA = slotB, slotB = file)
    }

    fun indexOf(file: File): Int = when {
        slotA?.absolutePath == file.absolutePath -> 0
        slotB?.absolutePath == file.absolutePath -> 1
        else -> -1
    }

    fun filesForCompare(): List<File>? = if (isReadyToCompare) listOf(slotA!!, slotB!!) else null

    fun clear(): CompareExplorerSelection = CompareExplorerSelection()

    companion object {
        fun fromFiles(files: List<File>): CompareExplorerSelection =
            CompareExplorerSelection(files.getOrNull(0), files.getOrNull(1))
    }
}
