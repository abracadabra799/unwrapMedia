package com.multiviewer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.ui.AppColors

/**
 * Compose-drawn menu bar for Windows/Linux styled after Android Studio / IntelliJ New UI:
 * - Compact height (30dp) with subtle background
 * - Rounded hover pill highlights for menu headers (4dp radius)
 * - Sleek typography with 12sp medium labels
 * - JetBrains-style dark dropdown popup styling with crisp border and shortcut alignment
 */
@Composable
fun CustomAppMenuBar(menus: List<AppMenu>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(AppColors.Panel)
            .height(30.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menus.forEach { menu -> MenuDropdown(menu) }
    }
}

@Composable
private fun MenuDropdown(menu: AppMenu) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val headerBgColor = when {
        expanded -> AppColors.Surface
        isHovered -> AppColors.TextPrimary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    Box {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(headerBgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = menu.label,
                fontSize = 12.sp,
                fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Medium,
                color = if (expanded || isHovered) AppColors.TextPrimary else AppColors.TextSecondary,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
        ) {
            menu.entries.forEach { entry ->
                when (entry) {
                    is MenuEntry.Item -> DropdownMenuItem(
                        text = {
                            Text(
                                text = entry.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = if (entry.enabled) AppColors.TextPrimary else AppColors.TextMuted,
                            )
                        },
                        onClick = {
                            expanded = false
                            entry.onClick()
                        },
                        enabled = entry.enabled,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        trailingIcon = entry.shortcut?.let { shortcut ->
                            {
                                Text(
                                    text = shortcut.displayString(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (entry.enabled) AppColors.TextSecondary else AppColors.TextMuted,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = AppColors.TextPrimary,
                            disabledTextColor = AppColors.TextMuted,
                        ),
                    )
                    is MenuEntry.Checkbox -> DropdownMenuItem(
                        text = {
                            Text(
                                text = entry.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = AppColors.TextPrimary,
                            )
                        },
                        onClick = {
                            expanded = false
                            entry.onCheckedChange(!entry.checked)
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        leadingIcon = {
                            if (entry.checked) {
                                Text(
                                    "✓",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.NeonBlue,
                                )
                            } else {
                                Spacer(Modifier.size(12.dp))
                            }
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = AppColors.TextPrimary,
                        ),
                    )
                    MenuEntry.Separator -> HorizontalDivider(
                        color = AppColors.Border,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
