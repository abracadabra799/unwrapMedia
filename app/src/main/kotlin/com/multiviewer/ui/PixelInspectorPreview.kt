package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    var mousePos by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { event ->
                mousePos = event.changes.first().position
            }
            .onPointerEvent(PointerEventType.Exit) {
                mousePos = null
            }
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        
        mousePos?.let { pos ->
            // Overlay with coordinates and RGB (Placeholder as we need to map pos to bitmap pixels)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(AppColors.Surface.copy(alpha = 0.8f))
                    .padding(4.dp)
            ) {
                Text(
                    text = "Pixel at (${pos.x.toInt()}, ${pos.y.toInt()}): R:? G:? B:?",
                    style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue)
                )
            }
        }
    }
}
