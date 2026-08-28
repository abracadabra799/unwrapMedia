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
private const val LEFT_PANEL_DEFAULT_WIDTH_DP = 370f
private const val LEFT_PANEL_MAX_WIDTH_DP = 800f
// The right panel used to start at exactly its own minimum width, so there was no room left to
// drag-shrink it -- only grow. Default now sits above the floor instead of on top of it. The user
// can always drag it wider (or resize the whole window) when they need more room, so the default
// favors leaving more space to the center preview instead.
private const val RIGHT_PANEL_MIN_WIDTH_DP = 240f
// 350 -> 420 -> 530 -> 470 -> 380 -> 450: the right panel carries Overview summary cards, Gain Map info,
// and detailed properties tables, which read much more comfortably with extra horizontal width.
private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 450f
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
    leftPanelDefaultWidthDp: Float = LEFT_PANEL_DEFAULT_WIDTH_DP,
    rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP,
) {
    var containerHeightPx by remember { mutableStateOf(0) }
    // 0.75 -> 0.6: shrinks the top row's (left+center+right) real height allocation so the bottom
    // Hex & Raw Data Viewer panel gets more room by default -- still a user-draggable ratio, not a
    // fixed pixel height.
    var verticalSplit by remember { mutableStateOf(0.6f) }
    // Left (Structure) and right (Detailed Properties) panels start at their default widths but
    // the user can drag either wider.
    var leftPanelWidthDp by remember { mutableStateOf(leftPanelDefaultWidthDp) }
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

            // Right Panel (Properties) -- rendered immediately beside the structure tree rather
            // than at the far edge, so Overview/Detailed Properties sits next to the structure
            // node driving it. Still called "rightPanel" (its logical role, matched by every
            // DashboardLayout call site) even though it no longer renders on the physical right.
            Column(
                modifier = Modifier
                    .width(rightPanelWidthDp.dp)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Surface)
            ) {
                rightPanel()
            }

            VerticalResizeHandle { deltaDp ->
                // This panel is now left-of-handle (like leftPanelWidthDp above), so a rightward
                // drag grows it too -- sign flipped from the old right-edge position.
                rightPanelWidthDp = (rightPanelWidthDp + deltaDp).coerceIn(RIGHT_PANEL_MIN_WIDTH_DP, RIGHT_PANEL_MAX_WIDTH_DP)
            }

            // Bottom Panel (Hex) -- rendered here now, directly beside Properties, so the byte
            // view sits next to the field that jumps it to an offset. Takes whatever width
            // remains, same slot the Center Panel used to occupy.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Panel)
            ) {
                bottomPanel()
            }
        }

        DraggableDivider(
            orientation = Orientation.Horizontal,
            containerSizePx = containerHeightPx,
            getSplit = { verticalSplit },
            setSplit = { verticalSplit = it },
        )

        // Center Panel (Visual Canvas: thumbnail/image/video player/frame analysis/waveform/
        // spectrogram) -- now full-width along the bottom, the old Hex panel's former spot.
        Column(
            modifier = Modifier
                .weight(1f - verticalSplit)
                .fillMaxWidth()
                .border(1.dp, AppColors.Border)
        ) {
            centerPanel()
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
