package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.parseFile
import java.io.File

class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)
}

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            val root = parseFile(file)
            tab.root = root
            tab.mediaSummary = try {
                buildMediaSummary(root, file)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }
}
