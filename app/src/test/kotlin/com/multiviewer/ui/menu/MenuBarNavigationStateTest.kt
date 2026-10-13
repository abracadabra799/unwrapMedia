package com.multiviewer.ui.menu

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuBarNavigationStateTest {
    private val log = mutableListOf<String>()

    // 0 File(F): [Open, ---, (disabled) Close, Exit]   1 View(V): [checkbox Grid]   2 Help(H): [About]
    private val menus = listOf(
        AppMenu("File", listOf(
            MenuEntry.Item("Open", true, null) { log += "open" },
            MenuEntry.Separator,
            MenuEntry.Item("Close", false, null) { log += "close" },
            MenuEntry.Item("Exit", true, null) { log += "exit" },
        ), mnemonic = 'F'),
        AppMenu("보기", listOf(MenuEntry.Checkbox("Grid", false) { log += "grid=$it" }), mnemonic = 'V'),
        AppMenu("Help", listOf(MenuEntry.Item("About", true, null) { log += "about" }), mnemonic = 'H'),
    )

    private fun key(state: MenuBarNavigationState, k: Key, down: Boolean = true, alt: Boolean = false, ctrl: Boolean = false) =
        handleMenuKey(state, k, down, alt, ctrl, shift = false, menus = menus)

    // ---- mouse ----

    @Test
    fun `click opens, clicking the open header closes`() {
        val open = MenuBarNavigationState().activate(1, 3)
        assertEquals(1, open.openMenuIndex)
        assertNull(open.highlightedItem, "a mouse-opened menu has no highlighted item yet")
        assertNull(open.activate(1, 3).openMenuIndex)
    }

    @Test
    fun `hover switches only while a menu is open, and a click after hover keeps it open`() {
        assertNull(MenuBarNavigationState().hover(2, 3).openMenuIndex)
        val switched = MenuBarNavigationState().activate(0, 3).hover(2, 3)
        assertEquals(2, switched.openMenuIndex)
        assertEquals(2, switched.activate(2, 3).openMenuIndex)
    }

    @Test
    fun `hovering an item highlights only selectable ones`() {
        val open = MenuBarNavigationState().activate(0, 3)
        assertEquals(3, open.hoverItem(3, menus).highlightedItem)
        assertNull(open.hoverItem(1, menus).highlightedItem) // separator
        assertNull(open.hoverItem(2, menus).highlightedItem) // disabled
    }

    // ---- keyboard while a menu is open ----

    @Test
    fun `left and right open the neighbouring menu with its first item highlighted, wrapping`() {
        val open = MenuBarNavigationState().activate(0, 3)
        val right = key(open, Key.DirectionRight).state
        assertEquals(1, right.openMenuIndex)
        assertEquals(0, right.highlightedItem)
        val wrapped = key(open, Key.DirectionLeft).state
        assertEquals(2, wrapped.openMenuIndex)
        assertTrue(key(open, Key.DirectionRight).consumed)
    }

    @Test
    fun `up and down skip separators and disabled items and wrap`() {
        val open = MenuBarNavigationState().activate(0, 3)
        val first = key(open, Key.DirectionDown).state
        assertEquals(0, first.highlightedItem)
        val second = key(first, Key.DirectionDown).state
        assertEquals(3, second.highlightedItem) // skips separator(1) and disabled Close(2)
        assertEquals(0, key(second, Key.DirectionDown).state.highlightedItem)
        assertEquals(3, key(open, Key.DirectionUp).state.highlightedItem)
        assertEquals(3, key(first, Key.MoveEnd).state.highlightedItem)
        assertEquals(0, key(second, Key.MoveHome).state.highlightedItem)
    }

    @Test
    fun `enter runs the highlighted item and closes, a checkbox toggles`() {
        val atExit = MenuBarNavigationState().activate(0, 3).hoverItem(3, menus)
        val r = key(atExit, Key.Enter)
        assertNull(r.state.openMenuIndex)
        assertNotNull(r.action).invoke()
        assertEquals(listOf("exit"), log)
        val atGrid = MenuBarNavigationState().activate(1, 3).hoverItem(0, menus)
        key(atGrid, Key.Spacebar).action!!.invoke()
        assertEquals(listOf("exit", "grid=true"), log)
    }

    @Test
    fun `enter with nothing highlighted does nothing but stays consumed`() {
        val r = key(MenuBarNavigationState().activate(0, 3), Key.Enter)
        assertNull(r.action)
        assertTrue(r.consumed)
        assertEquals(0, r.state.openMenuIndex)
    }

    @Test
    fun `escape closes everything`() {
        val r = key(MenuBarNavigationState().activate(0, 3).hoverItem(0, menus), Key.Escape)
        assertFalse(r.state.isActive)
        assertTrue(r.consumed)
    }

    @Test
    fun `typing while a menu is open is swallowed, ctrl shortcuts close it and pass through`() {
        val open = MenuBarNavigationState().activate(0, 3)
        assertTrue(key(open, Key.Q).consumed)
        val ctrl = key(open, Key.O, ctrl = true)
        assertFalse(ctrl.consumed)
        assertFalse(ctrl.state.isActive)
    }

    // ---- Alt / mnemonics ----

    @Test
    fun `alt pressed and released alone focuses the bar with mnemonics, again leaves it`() {
        val armed = key(MenuBarNavigationState(), Key.AltLeft).state
        assertFalse(key(MenuBarNavigationState(), Key.AltLeft).consumed, "Alt down alone must not be swallowed")
        val focused = key(armed, Key.AltLeft, down = false).state
        assertEquals(0, focused.focusedIndex)
        assertNull(focused.openMenuIndex)
        assertTrue(focused.mnemonicsVisible)
        val left = key(key(focused, Key.AltLeft).state, Key.AltLeft, down = false).state
        assertFalse(left.isActive)
    }

    @Test
    fun `alt released after another key does not toggle the bar`() {
        val armed = key(MenuBarNavigationState(), Key.AltLeft).state
        val afterTab = key(armed, Key.Tab, alt = true).state
        assertFalse(key(afterTab, Key.AltLeft, down = false).state.isActive)
    }

    @Test
    fun `bar focus moves with arrows and opens with down or enter`() {
        val focused = MenuBarNavigationState(focusedIndex = 0, mnemonicsVisible = true)
        val moved = key(focused, Key.DirectionRight).state
        assertEquals(1, moved.focusedIndex)
        assertNull(moved.openMenuIndex)
        val opened = key(moved, Key.DirectionDown).state
        assertEquals(1, opened.openMenuIndex)
        assertEquals(0, opened.highlightedItem)
        assertEquals(1, key(moved, Key.Enter).state.openMenuIndex)
    }

    @Test
    fun `alt plus mnemonic opens that menu directly, case-insensitive`() {
        val r = key(MenuBarNavigationState(), Key.H, alt = true)
        assertTrue(r.consumed)
        assertEquals(2, r.state.openMenuIndex)
        assertEquals(0, r.state.highlightedItem)
        assertFalse(key(MenuBarNavigationState(), Key.Z, alt = true).consumed)
    }

    @Test
    fun `plain mnemonic letter works while the bar is focused`() {
        val focused = MenuBarNavigationState(focusedIndex = 0, mnemonicsVisible = true)
        assertEquals(1, key(focused, Key.V).state.openMenuIndex)
    }

    @Test
    fun `inactive bar ignores ordinary keys`() {
        val r = key(MenuBarNavigationState(), Key.DirectionDown)
        assertFalse(r.consumed)
        assertFalse(r.state.isActive)
    }
}
