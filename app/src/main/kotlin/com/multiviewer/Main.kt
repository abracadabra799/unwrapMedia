package com.multiviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import java.awt.FileDialog
import java.awt.Frame

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(java.io.File(directory, fileName))
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
                        currentTab.root != null -> Text("Loaded ${currentTab.file.name}: ${currentTab.root!!.children.size} top-level boxes")
                    }
                }
            }
        }
    }
}
