package com.multiviewer.ui.menu

import androidx.compose.ui.input.key.Key

/**
 * A keyboard shortcut described independently of any menu-rendering backend (native AWT/Swing on
 * macOS, Compose-drawn on Windows/Linux) -- see MenuKeyboardShortcuts.kt for matching/display and
 * NativeMenuBarRenderer.kt for the AWT KeyShortcut conversion.
 *
 * [meta] means Cmd on macOS (via the native renderer) and Ctrl on Windows/Linux (via the custom
 * renderer's own key handler in Main.kt) -- it never means the literal Meta/Super key.
 */
data class AppKeyShortcut(val key: Key, val meta: Boolean = false, val shift: Boolean = false)

/** One entry in a menu: a clickable item, a checkbox item, a visual separator, or a one-level submenu. */
sealed class MenuEntry {
    data class Item(
        val label: String,
        val enabled: Boolean,
        val shortcut: AppKeyShortcut?,
        val onClick: () -> Unit,
    ) : MenuEntry()

    data class Checkbox(
        val label: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : MenuEntry()

    object Separator : MenuEntry()

    /**
     * A nested menu ("Create Motion Photo ▸"). One level only: [entries] never contain another
     * [Submenu] (the [AppSubmenuBuilder] offers no `submenu`). A disabled submenu never opens and its
     * items' shortcuts don't fire.
     */
    data class Submenu(
        val label: String,
        val enabled: Boolean,
        val entries: List<MenuEntry>,
    ) : MenuEntry()
}

/**
 * One top-level menu (e.g. "File") and its entries, in display order. [mnemonic] is the Alt+letter
 * that opens it in the custom (Windows/Linux) bar; the native macOS bar ignores it.
 */
data class AppMenu(val label: String, val entries: List<MenuEntry>, val mnemonic: Char? = null)

/** Entries shared by a top-level menu and a submenu: items, checkboxes and separators. */
abstract class MenuEntriesBuilder internal constructor() {
    protected val entries = mutableListOf<MenuEntry>()

    fun item(label: String, enabled: Boolean = true, shortcut: AppKeyShortcut? = null, onClick: () -> Unit) {
        entries.add(MenuEntry.Item(label, enabled, shortcut, onClick))
    }

    fun checkbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        entries.add(MenuEntry.Checkbox(label, checked, onCheckedChange))
    }

    fun separator() {
        entries.add(MenuEntry.Separator)
    }

}

/** Builder scope for one submenu's entries -- see [AppMenuBuilder.submenu]. No nested submenus. */
class AppSubmenuBuilder internal constructor() : MenuEntriesBuilder() {
    internal fun build(): List<MenuEntry> = entries.toList()
}

/** Builder scope for one menu's entries -- see [AppMenuBarBuilder.appMenu]. */
class AppMenuBuilder internal constructor(private val label: String, private val mnemonic: Char?) : MenuEntriesBuilder() {
    fun submenu(label: String, enabled: Boolean = true, block: AppSubmenuBuilder.() -> Unit) {
        entries.add(MenuEntry.Submenu(label, enabled, AppSubmenuBuilder().apply(block).build()))
    }

    internal fun build(): AppMenu = AppMenu(label, entries.toList(), mnemonic)
}

/** Builder scope for the whole menu bar -- see [buildAppMenuBar]. */
class AppMenuBarBuilder internal constructor() {
    private val menus = mutableListOf<AppMenu>()

    fun appMenu(label: String, mnemonic: Char? = null, block: AppMenuBuilder.() -> Unit) {
        menus.add(AppMenuBuilder(label, mnemonic).apply(block).build())
    }

    internal fun build(): List<AppMenu> = menus.toList()
}

/**
 * Describes the app's whole menu bar once, as plain data -- both the native (macOS) and custom
 * (Windows/Linux) renderers consume the same [List]<[AppMenu]> this produces, so the enabled/
 * checked/onClick logic in Main.kt is written exactly once regardless of platform.
 */
fun buildAppMenuBar(block: AppMenuBarBuilder.() -> Unit): List<AppMenu> = AppMenuBarBuilder().apply(block).build()
