package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.Cursor

private const val LEFT_PANEL_MIN_WIDTH_DP = 180f
private const val LEFT_PANEL_MAX_WIDTH_DP = 700f
// The right panel used to start at exactly its own minimum width, so there was no room left to
// drag-shrink it -- only grow. Default now sits above the floor instead of on top of it. The user
// can always drag it wider (or resize the whole window) when they need more room, so the default
// favors leaving more space to the center preview instead.
private const val RIGHT_PANEL_MIN_WIDTH_DP = 220f
// 350 -> 420 -> 530 -> 470 -> 380: the right panel now carries the Overview tab's full-width
// summary cards (see CoreMetadataDisplay), which reads more comfortably with more room -- and
// since the center panel takes whatever's left after left+right (weight(1f) in the Row below),
// the right panel's width is what actually trades off against the center (thumbnail/image/video
// preview) panel's real allocated width. Backed off from 470 to 380 because at the app's default
// (non-maximized) window size the center panel read as visibly cramped; a maximized window has
// enough extra width that this default barely matters there. Still user-draggable wider anytime.
private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 380f
private const val RIGHT_PANEL_MAX_WIDTH_DP = 1000f

// Thin draggable strip between two side-by-side panels. onDragDeltaDp receives the horizontal
// drag delta in dp -- the caller decides which panel (and which sign) that delta grows. A
// highlight/shadow pair (rather than one flat-color fill) gives it a raised-ridge look instead of
// reading as just another flat line among the panel borders.
@Composable
private fun VerticalResizeHandle(onDragDeltaDp: (Float) -> Unit) {
    val density = LocalDensity.current
    // Resize cursor on hover is the standard cross-platform signal for "this is draggable" --
    // beta testers reported not realizing the side panels could be resized at all.
    val resizeCursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }
    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDeltaDp(with(density) { dragAmount.x.toDp().value })
                }
            },
    ) {
        // 2dp lines (was 1dp) for better visibility.
        Box(modifier = Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight().background(AppColors.DividerHighlight))
        Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(AppColors.DividerShadow))
    }
}

@Composable
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
    rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP,
) {
    var containerHeightPx by remember { mutableStateOf(0) }
    // 0.75 -> 0.6: shrinks the top row's (left+center+right) real height allocation so the bottom
    // Hex & Raw Data Viewer panel gets more room by default -- still a user-draggable ratio, not a
    // fixed pixel height.
    var verticalSplit by remember { mutableStateOf(0.6f) }
    // Left (Structure) and right (Detailed Properties) panels start at their old fixed widths but
    // the user can drag either wider -- e.g. pretty-printed XMP in the right panel needs much more
    // horizontal room than 350dp to avoid wrapping mid-line.
    var leftPanelWidthDp by remember { mutableStateOf(300f) }
    var rightPanelWidthDp by remember { mutableStateOf(rightPanelDefaultWidthDp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .onGloballyPositioned { containerHeightPx = it.size.height },
    ) {
        Row(modifier = Modifier.weight(verticalSplit).fillMaxWidth()) {
            // Left Panel (Structure)
            Column(
                modifier = Modifier
                    .width(leftPanelWidthDp.dp)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Surface)
            ) {
                leftPanel()
            }

            VerticalResizeHandle { deltaDp ->
                leftPanelWidthDp = (leftPanelWidthDp + deltaDp).coerceIn(LEFT_PANEL_MIN_WIDTH_DP, LEFT_PANEL_MAX_WIDTH_DP)
            }

            // Center Panel (Visual Canvas)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
            ) {
                centerPanel()
            }

            VerticalResizeHandle { deltaDp ->
                rightPanelWidthDp = (rightPanelWidthDp - deltaDp).coerceIn(RIGHT_PANEL_MIN_WIDTH_DP, RIGHT_PANEL_MAX_WIDTH_DP)
            }

            // Right Panel (Properties)
            Column(
                modifier = Modifier
                    .width(rightPanelWidthDp.dp)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Surface)
            ) {
                rightPanel()
            }
        }

        DraggableDivider(
            orientation = Orientation.Horizontal,
            containerSizePx = containerHeightPx,
            getSplit = { verticalSplit },
            setSplit = { verticalSplit = it },
        )

        // Bottom Panel (Hex)
        Column(
            modifier = Modifier
                .weight(1f - verticalSplit)
                .fillMaxWidth()
                .border(1.dp, AppColors.Border)
                .background(AppColors.Panel)
        ) {
            bottomPanel()
        }
    }
}

@Composable
fun PanelHeader(title: String, color: androidx.compose.ui.graphics.Color = AppColors.TextPrimary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = AppTypography.labelLarge.copy(color = color)
        )
    }
}
