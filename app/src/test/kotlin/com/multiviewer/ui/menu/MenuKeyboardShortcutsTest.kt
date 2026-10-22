package com.multiviewer.ui.menu

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuKeyboardShortcutsTest {

    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): KeyEvent =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift, isAltPressed = alt)

    @Test
    fun `matches requires the same key and modifiers`() {
        val shortcut = AppKeyShortcut(Key.O, meta = true)
        assertTrue(shortcut.matches(keyDown(Key.O, ctrl = true)))
        assertFalse(shortcut.matches(keyDown(Key.O, ctrl = false)), "ctrl not pressed")
        assertFalse(shortcut.matches(keyDown(Key.W, ctrl = true)), "wrong key")
        assertFalse(shortcut.matches(keyDown(Key.O, ctrl = true, shift = true)), "unexpected shift")
    }

    @Test
    fun `matches rejects alt even when key and other modifiers agree`() {
        val shortcut = AppKeyShortcut(Key.O, meta = true)
        assertFalse(shortcut.matches(keyDown(Key.O, ctrl = true, alt = true)))
    }

    @Test
    fun `matches respects shift when the shortcut requires it`() {
        val shortcut = AppKeyShortcut(Key.G, meta = true, shift = true)
        assertTrue(shortcut.matches(keyDown(Key.G, ctrl = true, shift = true)))
        assertFalse(shortcut.matches(keyDown(Key.G, ctrl = true, shift = false)))
    }

    @Test
    fun `collectShortcuts gathers only Item entries with a non-null shortcut, in order`() {
        var openClicked = false
        var closeClicked = false
        val menus = buildAppMenuBar {
            appMenu("File") {
                item("Open", shortcut = AppKeyShortcut(Key.O, meta = true), onClick = { openClicked = true })
                item("Close", shortcut = AppKeyShortcut(Key.W, meta = true), onClick = { closeClicked = true })
                item("No Shortcut", onClick = {})
                checkbox("Dark theme", checked = true, onCheckedChange = {})
                separator()
            }
        }
        val shortcuts = menus.collectShortcuts()
        assertEquals(listOf(AppKeyShortcut(Key.O, meta = true), AppKeyShortcut(Key.W, meta = true)), shortcuts.map { it.first })
        shortcuts[0].second()
        assertTrue(openClicked)
        assertFalse(closeClicked)
    }

    @Test
    fun `displayString formats modifiers before the key`() {
        assertEquals("Ctrl+O", AppKeyShortcut(Key.O, meta = true).displayString())
        assertEquals("Ctrl+Shift+G", AppKeyShortcut(Key.G, meta = true, shift = true).displayString())
    }

    @Test
    fun `displayString falls back to Key toString for an unmapped key`() {
        val label = AppKeyShortcut(Key.Z, meta = true).displayString()
        assertTrue(label.startsWith("Ctrl+"), "expected a Ctrl+ prefix, got: $label")
    }

    @Test
    fun `collectShortcuts finds a shortcut inside an enabled submenu but not a disabled one`() {
        var enabledClicked = false
        val menus = buildAppMenuBar {
            appMenu("Tools") {
                submenu("On") { item("A", shortcut = AppKeyShortcut(Key.P, meta = true), onClick = { enabledClicked = true }) }
                submenu("Off", enabled = false) { item("B", shortcut = AppKeyShortcut(Key.B, meta = true)) {} }
            }
        }
        val shortcuts = menus.collectShortcuts()
        assertEquals(listOf(AppKeyShortcut(Key.P, meta = true)), shortcuts.map { it.first })
        shortcuts.single().second()
        assertTrue(enabledClicked)
    }
}
