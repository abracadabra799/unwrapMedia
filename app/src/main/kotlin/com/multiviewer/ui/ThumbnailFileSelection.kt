package com.multiviewer.ui

import java.io.File

/** Maintains a pairwise selection, dropping the oldest item when a third is selected. */
internal fun toggleThumbnailFileSelection(selectedFiles: List<File>, file: File): List<File> {
    val existingIndex = selectedFiles.indexOfFirst { it.absolutePath == file.absolutePath }
    if (existingIndex >= 0) {
        return selectedFiles.filterIndexed { index, _ -> index != existingIndex }
    }
    return if (selectedFiles.size < 2) selectedFiles + file else selectedFiles.drop(1) + file
}
