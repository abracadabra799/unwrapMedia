package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

// Flat, rectangular controls for dense tool strips (hex viewer). Material3's FilledTonalButton is a
// full pill, which reads as bulky and toy-like in an editor toolbar; these are 2dp-cornered,
// borderless until hovered/active, and take their colors from the theme palette (light + dark).

private val FlatShape = RoundedCornerShape(2.dp)

/**
 * A text button for tool strips. [active] marks a toggle that's on (a panel shown, a mode
 * selected); [hint] is an optional muted suffix such as a keyboard shortcut.
 */
@Composable
fun FlatToolButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
    accent: Color = AppColors.NeonBlue,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        active -> accent.copy(alpha = 0.18f)
        hovered && enabled -> AppColors.TextPrimary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> AppColors.TextMuted
        active -> accent
        else -> AppColors.TextPrimary
    }
    Row(
        modifier = modifier
            .height(22.dp)
            .background(background, FlatShape)
            .then(if (active) Modifier.border(1.dp, accent.copy(alpha = 0.45f), FlatShape) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))) else Modifier)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = textColor, softWrap = false, maxLines = 1)
        if (hint != null) {
            Text(hint, fontSize = 10.sp, color = AppColors.TextMuted, softWrap = false, maxLines = 1)
        }
    }
}

/** A 1px vertical rule between groups of [FlatToolButton]s. */
@Composable
fun FlatToolSeparator() {
    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(14.dp).background(AppColors.Border))
}

/** A single-line monospace input with a square, hairline frame. */
@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(AppColors.NeonBlue),
        modifier = modifier
            .height(22.dp)
            .background(AppColors.Background, FlatShape)
            .border(1.dp, AppColors.Border, FlatShape)
            .padding(horizontal = 6.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 11.sp, color = AppColors.TextMuted, softWrap = false, maxLines = 1)
                inner()
            }
        },
    )
}
