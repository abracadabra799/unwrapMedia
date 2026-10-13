package com.multiviewer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.ui.AppColors

// VS Code's menu selection colour (Dark Modern and Light Modern both use #0078D4 with white text).
private val SelectionBackground = Color(0xFF0078D4)
private val SelectionForeground = Color.White
private val MenuShape = RoundedCornerShape(5.dp)

/** Where the header row and each header sit in window (root) coordinates, for placing the dropdown. */
@Stable
class MenuBarLayout {
    internal val headerBounds = mutableStateMapOf<Int, Rect>()
    internal var barBottom by mutableStateOf(0f)
}

@Composable
fun rememberMenuBarLayout(): MenuBarLayout = remember { MenuBarLayout() }

/**
 * Compose-drawn menu bar for Windows/Linux, modelled on VS Code's custom title bar menus: click a
 * header to open it, then hover or ←/→ to move between menus with each one opening as it is reached.
 * Only the headers are drawn here -- the dropdown is [CustomMenuOverlay], drawn above the window
 * content in the same window (no popup window), so the window keeps keyboard focus throughout.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CustomAppMenuBar(
    menus: List<AppMenu>,
    navigationState: MenuBarNavigationState,
    onNavigationStateChange: (MenuBarNavigationState) -> Unit,
    layout: MenuBarLayout,
    modifier: Modifier = Modifier,
) {
    val state by rememberUpdatedState(navigationState)
    Row(
        modifier = modifier
            .background(AppColors.Panel)
            .height(30.dp)
            .padding(horizontal = 4.dp)
            .onGloballyPositioned { layout.barBottom = it.boundsInRoot().bottom },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menus.forEachIndexed { index, menu ->
            var hovered by remember { mutableStateOf(false) }
            val open = navigationState.openMenuIndex == index
            val focused = navigationState.focusedIndex == index
            val background = when {
                open || focused -> AppColors.TextPrimary.copy(alpha = 0.12f)
                hovered -> AppColors.TextPrimary.copy(alpha = 0.08f)
                else -> Color.Transparent
            }
            val label = mnemonicLabel(menu.label, menu.mnemonic)
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(background)
                    .onGloballyPositioned { layout.headerBounds[index] = it.boundsInRoot() }
                    // Switch only on real pointer movement: Compose re-sends Enter when layout changes
                    // under a still pointer (position == previousPosition), which would otherwise hop
                    // menus on its own. A real enter -- however fast the flick -- has moved.
                    .onPointerEvent(PointerEventType.Enter) { event ->
                        hovered = true
                        if (event.changes.any { it.position != it.previousPosition }) {
                            onNavigationStateChange(state.hover(index, menus.size))
                        }
                    }
                    .onPointerEvent(PointerEventType.Move) {
                        hovered = true
                        if (state.openMenuIndex != null && state.openMenuIndex != index) {
                            onNavigationStateChange(state.hover(index, menus.size))
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
                    .onPointerEvent(PointerEventType.Press) { onNavigationStateChange(state.activate(index, menus.size)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = buildAnnotatedString {
                        append(label.text)
                        val u = label.underlineIndex
                        if (navigationState.mnemonicsVisible && u != null && u < label.text.length) {
                            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), u, u + 1)
                        }
                    },
                    fontSize = 13.sp,
                    letterSpacing = 0.sp,
                    color = if (open || focused || hovered) AppColors.TextPrimary else AppColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The open menu's dropdown plus a click-catching scrim over the content below the bar. Place it as
 * the last child of a root-filling Box so it draws above the window content. A press on the scrim
 * closes the menu and is consumed, like VS Code (it doesn't also click whatever is underneath).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CustomMenuOverlay(
    menus: List<AppMenu>,
    navigationState: MenuBarNavigationState,
    onNavigationStateChange: (MenuBarNavigationState) -> Unit,
    layout: MenuBarLayout,
) {
    val openIndex = navigationState.openMenuIndex ?: return
    val menu = menus.getOrNull(openIndex) ?: return
    val header = layout.headerBounds[openIndex] ?: return
    val state by rememberUpdatedState(navigationState)
    val onChange by rememberUpdatedState(onNavigationStateChange)
    val density = LocalDensity.current
    val barBottomDp = with(density) { layout.barBottom.toDp() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = barBottomDp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                event.changes.forEach { it.consume() }
                                onChange(state.close())
                            }
                        }
                    }
                },
        )

        // Positioned under its header, pushed left if it would run off the window's right edge.
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val maxHeight = (constraints.maxHeight - layout.barBottom.toInt() - 8.dp.roundToPx()).coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = maxHeight))
                    val x = minOf(header.left.toInt(), constraints.maxWidth - placeable.width).coerceAtLeast(0)
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, layout.barBottom.toInt()) }
                },
        ) {
            Column(
                Modifier
                    .shadow(10.dp, MenuShape)
                    .background(AppColors.Surface, MenuShape)
                    .border(1.dp, AppColors.Border, MenuShape)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 220.dp, max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                menu.entries.forEachIndexed { i, entry ->
                    when (entry) {
                        MenuEntry.Separator -> Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 0.dp, vertical = 4.dp)
                                .height(1.dp)
                                .background(AppColors.Border),
                        )
                        else -> MenuRow(
                            entry = entry,
                            highlighted = navigationState.highlightedItem == i,
                            onHover = { onChange(state.hoverItem(i, menus)) },
                            onClick = {
                                val action = state.hoverItem(i, menus).highlightedAction(menus)
                                if (action != null) {
                                    onChange(state.close())
                                    action()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MenuRow(entry: MenuEntry, highlighted: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val enabled = entry.isSelectable()
    val label = when (entry) {
        is MenuEntry.Item -> entry.label
        is MenuEntry.Checkbox -> entry.label
        MenuEntry.Separator -> ""
    }
    val checked = entry is MenuEntry.Checkbox && entry.checked
    val shortcut = (entry as? MenuEntry.Item)?.shortcut?.displayString()
    val foreground = when {
        highlighted -> SelectionForeground
        enabled -> AppColors.TextPrimary
        else -> AppColors.TextMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (highlighted) SelectionBackground else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .onPointerEvent(PointerEventType.Move) { if (!highlighted) onHover() }
            .onPointerEvent(PointerEventType.Release) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", fontSize = 13.sp, letterSpacing = 0.sp, color = foreground)
        }
        Text(
            label,
            fontSize = 13.sp,
            letterSpacing = 0.sp,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (shortcut != null) {
            Spacer(Modifier.width(32.dp))
            Text(shortcut, fontSize = 12.sp, letterSpacing = 0.sp, color = if (highlighted) SelectionForeground else AppColors.TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.width(16.dp))
    }
}
