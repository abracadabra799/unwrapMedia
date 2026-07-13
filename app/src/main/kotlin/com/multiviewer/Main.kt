package com.multiviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        LaunchedEffect(Unit) {
            window.contentPane.dropTarget = DropTarget(window.contentPane, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    if (!event.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop()
                        return
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        files.firstOrNull()?.let { appState.openFile(it) }
                        event.dropComplete(true)
                    } catch (e: Exception) {
                        event.dropComplete(false)
                    }
                }
            })
        }

        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }

                    val currentTab = appState.tabs[appState.selectedTabIndex]
                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> com.multiviewer.ui.BoxTreeView(
                            root = currentTab.root!!,
                            selected = currentTab.selected,
                            onSelect = { currentTab.selected = it },
                        )
                    }
                }
            }
        }
    }
}
