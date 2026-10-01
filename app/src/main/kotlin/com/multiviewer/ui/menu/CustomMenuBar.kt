package com.multiviewer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.ui.AppColors

/**
 * Compose-drawn menu bar for Windows/Linux, where the native AWT/Swing menu (used on macOS via
 * NativeAppMenuBar) renders with the OS's own dated Win32 HMENU styling that Compose can't
 * restyle. Same [AppMenu] data as the native renderer -- see AppMenuDsl.kt.
 */
@Composable
fun CustomAppMenuBar(menus: List<AppMenu>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.background(AppColors.Panel).padding(vertical = 2.dp)) {
        menus.forEach { menu -> MenuDropdown(menu) }
    }
}

@Composable
private fun MenuDropdown(menu: AppMenu) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = menu.label,
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu.entries.forEach { entry ->
                when (entry) {
                    is MenuEntry.Item -> DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            expanded = false
                            entry.onClick()
                        },
                        enabled = entry.enabled,
                        trailingIcon = entry.shortcut?.let { shortcut ->
                            {
                                Text(
                                    shortcut.displayString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        },
                    )
                    is MenuEntry.Checkbox -> DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            expanded = false
                            entry.onCheckedChange(!entry.checked)
                        },
                        leadingIcon = if (entry.checked) {
                            { Text("✓") }
                        } else {
                            null
                        },
                    )
                    MenuEntry.Separator -> HorizontalDivider(color = AppColors.Border)
                }
            }
        }
    }
}
