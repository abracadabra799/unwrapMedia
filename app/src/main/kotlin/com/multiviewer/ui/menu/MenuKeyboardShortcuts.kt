package com.multiviewer.ui.menu

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * Flattens every [MenuEntry.Item] with a non-null shortcut into a lookup Main.kt's custom
 * (Windows/Linux) key handler can consult directly -- the native renderer doesn't need this, the
 * OS's own accelerator table handles shortcuts for it.
 */
fun List<AppMenu>.collectShortcuts(): List<Pair<AppKeyShortcut, () -> Unit>> =
    flatMap { it.entries }
        .filterIsInstance<MenuEntry.Item>()
        .mapNotNull { entry -> entry.shortcut?.let { it to entry.onClick } }

/**
 * Whether [keyEvent] triggers this shortcut. [AppKeyShortcut.meta] matches Ctrl here -- on
 * Windows/Linux (the only platform this is consulted on) there is no separate Meta/Super
 * convention to honor, Ctrl is the modifier every other app on those platforms uses.
 */
fun AppKeyShortcut.matches(keyEvent: KeyEvent): Boolean =
    keyEvent.key == key && keyEvent.isCtrlPressed == meta && keyEvent.isShiftPressed == shift && !keyEvent.isAltPressed

// Display names for the specific keys this app's menu shortcuts actually use -- Compose's Key
// has no built-in human-readable accessor, and a generic mapping for every possible Key isn't
// needed here (YAGNI): extend this map if a future shortcut uses a key not yet listed, the
// fallback covers it without crashing.
private val KEY_DISPLAY_NAMES = mapOf(
    Key.O to "O", Key.W to "W", Key.G to "G", Key.D to "D",
    Key.C to "C", Key.P to "P", Key.S to "S", Key.B to "B",
)

/** "Ctrl+Shift+G"-style label for the custom (Windows/Linux) menu to show beside an item. */
fun AppKeyShortcut.displayString(): String {
    val parts = mutableListOf<String>()
    if (meta) parts.add("Ctrl")
    if (shift) parts.add("Shift")
    parts.add(KEY_DISPLAY_NAMES[key] ?: key.toString())
    return parts.joinToString("+")
}
