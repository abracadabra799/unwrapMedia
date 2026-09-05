package com.multiviewer.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Locale

enum class MediaFilterCategory {
    ALL,
    VIDEO,
    IMAGE,
    AUDIO,
    RAW,
}

fun showOpenFolderDialog(appState: AppState) {
    val isMac = System.getProperty("os.name").lowercase(Locale.US).contains("mac")
    if (isMac) {
        val dialog = FileDialog(null as Frame?, "폴더 선택 (Select Folder)", FileDialog.LOAD)
        // Open at parent directory so the current directory and sibling directories are selectable items!
        val initialDir = appState.selectedFolder?.parentFile ?: appState.lastOpenedDirectory?.parentFile ?: appState.selectedFolder ?: appState.lastOpenedDirectory
        initialDir?.let { dir ->
            if (dir.exists() && dir.isDirectory) dialog.directory = dir.absolutePath
        }
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null) {
                val chosen = if (name != null) File(dir, name) else File(dir)
                if (chosen.exists() && chosen.isDirectory) {
                    appState.openFolder(chosen)
                } else if (chosen.exists() && chosen.isFile) {
                    val parent = chosen.parentFile
                    if (parent != null) appState.openFolder(parent, fileToOpen = chosen)
                } else {
                    val fallback = File(dir)
                    if (fallback.exists() && fallback.isDirectory) {
                        appState.openFolder(fallback)
                    }
                }
            }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    } else {
        // On Windows and Linux, JFileChooser with FILES_AND_DIRECTORIES mode allows selecting both folders AND files without greying out files!
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        } catch (_: Throwable) {}
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "폴더 선택 (Select Folder)"
            fileSelectionMode = javax.swing.JFileChooser.FILES_AND_DIRECTORIES
            val initialDir = appState.selectedFolder?.parentFile ?: appState.lastOpenedDirectory?.parentFile ?: appState.selectedFolder ?: appState.lastOpenedDirectory
            initialDir?.let { dir ->
                if (dir.exists()) currentDirectory = if (dir.isDirectory) dir else dir.parentFile
            }
        }
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            val selected = chooser.selectedFile ?: return
            if (selected.isDirectory) {
                appState.openFolder(selected)
            } else if (selected.isFile) {
                val parent = selected.parentFile
                if (parent != null) {
                    appState.openFolder(parent, fileToOpen = selected)
                }
            }
        }
    }
}

@Composable
fun FolderExplorerView(
    appState: AppState,
    currentTab: TabState?,
    language: AppLanguage,
) {
    val targetFolder = appState.selectedFolder ?: currentTab?.file?.parentFile
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MediaFilterCategory.ALL) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val allMediaFiles = remember(targetFolder?.absolutePath, refreshTrigger) {
        if (targetFolder != null && targetFolder.exists() && targetFolder.isDirectory) {
            try {
                targetFolder.listFiles { f ->
                    f.isFile && !f.isHidden && f.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS
                }?.sortedBy { it.name.lowercase(Locale.US) }?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val filteredFiles = remember(allMediaFiles, searchQuery, selectedCategory) {
        allMediaFiles.filter { file ->
            val matchesQuery = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            val ext = file.extension.lowercase(Locale.US)
            val matchesCategory = when (selectedCategory) {
                MediaFilterCategory.ALL -> true
                MediaFilterCategory.VIDEO -> ext in VIDEO_EXTENSIONS
                MediaFilterCategory.IMAGE -> ext in IMAGE_EXTENSIONS && ext !in listOf("cr2", "nef", "arw", "dng")
                MediaFilterCategory.AUDIO -> ext in AUDIO_EXTENSIONS
                MediaFilterCategory.RAW -> ext in RAW_PIXEL_EXTENSIONS || ext in RAW_AUDIO_EXTENSIONS || ext in listOf("cr2", "nef", "arw", "dng")
            }
            matchesQuery && matchesCategory
        }
    }

    // Listed above the files so the explorer can go down as well as up, instead of the native
    // folder dialog being the only way to change folders.
    val subfolders = remember(targetFolder?.absolutePath, refreshTrigger, searchQuery) {
        appState.getSubfolders(targetFolder)
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    }

    val listState = rememberLazyListState()

    // Arriving in a different folder starts at the top. listState survives the folder change (it is
    // remembered for the whole panel), so without this a new folder inherits the previous one's
    // scroll offset and opens part-way down -- with its first entries, including the subfolder rows,
    // scrolled out of sight.
    LaunchedEffect(targetFolder?.absolutePath) {
        listState.scrollToItem(0)
    }

    // Auto-scroll to currently active tab file when folder opens or active tab changes
    LaunchedEffect(currentTab?.file?.absolutePath, filteredFiles, subfolders) {
        val currentFile = currentTab?.file
        if (currentFile != null) {
            val idx = filteredFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }
            if (idx >= 0) {
                // Files sit below the subfolder rows, so the scroll index has to clear those first.
                listState.animateScrollToItem(idx + subfolders.size)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background),
    ) {
        if (targetFolder == null || !targetFolder.exists()) {
            // Empty folder placeholder
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "📁",
                        fontSize = 36.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (language == AppLanguage.KO) "선택된 폴더가 없습니다" else "No folder selected",
                        style = AppTypography.titleMedium.copy(color = AppColors.TextPrimary),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (language == AppLanguage.KO) "폴더를 열어 지원 미디어를 탐색하세요" else "Open a folder to explore media files",
                        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary),
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { showOpenFolderDialog(appState) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AppColors.Surface.copy(alpha = 0.5f),
                            contentColor = AppColors.NeonBlue,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                    ) {
                        Text(if (language == AppLanguage.KO) "📂 폴더 열기 / 선택" else "📂 Open Folder")
                    }
                }
            }
        } else {
            // Folder Header Bar
            Surface(
                color = AppColors.Surface,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📁 ${targetFolder.name}",
                            style = AppTypography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = AppColors.NeonBlue,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${allMediaFiles.size}개",
                            fontSize = 11.sp,
                            color = AppColors.TextSecondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        // Without this, entering a subfolder was a one-way trip: the list shows no
                        // way back, so the only escape was re-opening the native folder dialog.
                        IconButton(
                            onClick = { appState.navigateToParentFolder() },
                            enabled = targetFolder.parentFile?.isDirectory == true,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Text(
                                "⬆️",
                                fontSize = 11.sp,
                                color = if (targetFolder.parentFile?.isDirectory == true) AppColors.TextPrimary else AppColors.TextSecondary,
                            )
                        }
                        IconButton(
                            onClick = { refreshTrigger++ },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Text("🔄", fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Search Filter Field
                    Surface(
                        color = AppColors.Background,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🔍", fontSize = 10.sp, color = AppColors.TextSecondary)
                            Spacer(Modifier.width(4.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = AppTypography.bodySmall.copy(
                                    color = AppColors.TextPrimary,
                                    fontSize = 11.sp,
                                ),
                                cursorBrush = SolidColor(AppColors.NeonBlue),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            if (language == AppLanguage.KO) "파일명 검색..." else "Search files...",
                                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(18.dp),
                                ) {
                                    Text("✕", fontSize = 10.sp, color = AppColors.TextSecondary)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Category Filter Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryChip(
                            label = if (language == AppLanguage.KO) "전체" else "All",
                            selected = selectedCategory == MediaFilterCategory.ALL,
                            onClick = { selectedCategory = MediaFilterCategory.ALL },
                        )
                        CategoryChip(
                            label = "🎬",
                            selected = selectedCategory == MediaFilterCategory.VIDEO,
                            onClick = { selectedCategory = MediaFilterCategory.VIDEO },
                        )
                        CategoryChip(
                            label = "🖼️",
                            selected = selectedCategory == MediaFilterCategory.IMAGE,
                            onClick = { selectedCategory = MediaFilterCategory.IMAGE },
                        )
                        CategoryChip(
                            label = "🎵",
                            selected = selectedCategory == MediaFilterCategory.AUDIO,
                            onClick = { selectedCategory = MediaFilterCategory.AUDIO },
                        )
                        CategoryChip(
                            label = "🎞️ RAW",
                            selected = selectedCategory == MediaFilterCategory.RAW,
                            onClick = { selectedCategory = MediaFilterCategory.RAW },
                        )
                    }
                }
            }

            // File List -- the placeholder only stands in when there is nothing at all to show;
            // a folder holding only subfolders still has somewhere to go, so it lists those.
            if (filteredFiles.isEmpty() && subfolders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (searchQuery.isNotEmpty()) {
                            if (language == AppLanguage.KO) "검색 결과가 없습니다" else "No matching files"
                        } else {
                            if (language == AppLanguage.KO) "해당하는 지원 미디어 파일이 없습니다" else "No supported media files found"
                        },
                        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary),
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                    items(subfolders, key = { it.absolutePath }) { folder ->
                        Surface(
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { appState.openFolder(folder, openFirstFile = false) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📁", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    folder.name,
                                    style = AppTypography.bodySmall.copy(color = AppColors.NeonBlue, fontSize = 11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("›", fontSize = 12.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }

                    itemsIndexed(filteredFiles, key = { _, file -> file.absolutePath }) { index, file ->
                        val openedTabIndex = appState.tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
                        val isOpened = openedTabIndex >= 0
                        val isCurrentTab = currentTab?.file?.absolutePath == file.absolutePath
                        val ext = file.extension.lowercase(Locale.US)
                        val (icon, badgeColor) = when {
                            // Muted to match the theme's accents (see Theme.kt's DarkPalette): a
                            // long file list shows these badges on every row, so saturated ones
                            // turned the whole panel into competing colour.
                            ext in VIDEO_EXTENSIONS -> "🎬" to Color(0xFF61AFEF)
                            ext in AUDIO_EXTENSIONS -> "🎵" to Color(0xFF98C379)
                            ext in RAW_PIXEL_EXTENSIONS || ext in RAW_AUDIO_EXTENSIONS || ext in listOf("cr2", "nef", "arw", "dng") -> "🎞️" to Color(0xFFC678DD)
                            else -> "🖼️" to Color(0xFFD19A66)
                        }

                        ContextMenuArea(
                            items = {
                                buildList {
                                    add(
                                        ContextMenuItem(if (language == AppLanguage.KO) "열기" else "Open") {
                                            appState.openFile(file)
                                        }
                                    )
                                    if (isOpened) {
                                        add(
                                            ContextMenuItem(if (language == AppLanguage.KO) "탭 닫기" else "Close Tab") {
                                                appState.closeTabByFile(file)
                                            }
                                        )
                                    }
                                    add(
                                        ContextMenuItem(if (language == AppLanguage.KO) "파일 경로 복사" else "Copy File Path") {
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(file.absolutePath), null)
                                        }
                                    )
                                    add(
                                        ContextMenuItem(if (language == AppLanguage.KO) "시스템 탐색기에서 보기" else "Reveal in Finder/Explorer") {
                                            try {
                                                if (Desktop.isDesktopSupported()) {
                                                    Desktop.getDesktop().open(file.parentFile)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    )
                                }
                            },
                        ) {
                            Surface(
                                color = if (isCurrentTab) AppColors.Surface else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { appState.openFile(file) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(4.dp),
                                border = if (isCurrentTab) androidx.compose.foundation.BorderStroke(1.dp, AppColors.NeonGreen.copy(alpha = 0.5f)) else null,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(icon, fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = file.name,
                                                style = AppTypography.bodySmall.copy(
                                                    fontWeight = if (isCurrentTab) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrentTab) AppColors.NeonGreen else if (isOpened) AppColors.NeonBlue else AppColors.TextPrimary,
                                                    fontSize = 11.sp,
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (isCurrentTab) {
                                                Spacer(Modifier.width(4.dp))
                                                Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.NeonGreen)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = formatFileSize(file.length()),
                                                fontSize = 10.sp,
                                                color = AppColors.TextSecondary,
                                            )
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(2.dp),
                                                modifier = Modifier.padding(start = 4.dp),
                                            ) {
                                                Text(
                                                    text = ext.uppercase(Locale.US),
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (isOpened) {
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { appState.closeTabByFile(file) },
                                            modifier = Modifier.size(20.dp),
                                        ) {
                                            Text("✕", fontSize = 10.sp, color = AppColors.NeonRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) AppColors.NeonBlue.copy(alpha = 0.25f) else AppColors.Surface.copy(alpha = 0.4f),
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) AppColors.NeonBlue else AppColors.Border,
        ),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) AppColors.NeonBlue else AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
