package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

@Composable
fun PrimaryImagePopupWindow(tab: TabState, onCloseRequest: () -> Unit) {
    val forensic = tab.imageForensic
    val bitmap = forensic?.bitmap
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(1000.dp, 750.dp),
    )

    Window(
        onCloseRequest = onCloseRequest,
        title = "Primary Image - ${tab.file.name}",
        state = windowState,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (bitmap != null) {
                PixelInspectorPreview(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                    tileGrid = tab.tileGrid,
                    selectedTileIndex = tab.selectedTileIndex,
                    onTileClick = { index ->
                        val root = tab.root
                        val itemId = tab.tileGrid?.tileItemIds?.getOrNull(index)
                        val iloc = root?.let { r -> com.multiviewer.parser.findFirst(r) { it.type == "meta" } }
                            ?.let { meta -> com.multiviewer.parser.findFirst(meta) { it.type == "iloc" } }
                        val node = itemId?.let { id -> iloc?.children?.find { it.type == "item_$id" } }
                        if (node != null) tab.selected = node
                    },
                )

                val caption = formatResolutionWithOrientation(bitmap.width, bitmap.height, forensic?.orientation, forensic?.orientationCode)
                val tileInfoSuffix = if (tab.tileGrid != null) {
                    val selected = tab.selectedTileIndex?.let { " · Selected Tile: #${it + 1}/${tab.tileGrid?.tileItemIds?.size}" } ?: " · Tiles: ${tab.tileGrid?.tileItemIds?.size}"
                    selected
                } else ""

                PreviewCaption(
                    "$caption$tileInfoSuffix",
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            } else if (forensic?.isDecodingFallback == true) {
                DecodingIndicator("이미지 디코딩 중...", modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    "Primary Image Not Available",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed, fontSize = 14.sp),
                )
            }
        }
    }
}
