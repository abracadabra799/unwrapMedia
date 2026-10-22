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

    // ---- submenus ----

    // 0 Tools: [Before, Create ▸ [---, (disabled) X, Google, Samsung], (disabled) Off ▸ [Y], After]   1 Help: [About]
    private val subMenus = listOf(
        AppMenu("Tools", listOf(
            MenuEntry.Item("Before", true, null) { log += "before" },
            MenuEntry.Submenu("Create", true, listOf(
                MenuEntry.Separator,
                MenuEntry.Item("X", false, null) { log += "x" },
                MenuEntry.Item("Google", true, null) { log += "google" },
                MenuEntry.Item("Samsung", true, null) { log += "samsung" },
            )),
            MenuEntry.Submenu("Off", false, listOf(MenuEntry.Item("Y", true, null) { log += "y" })),
            MenuEntry.Item("After", true, null) { log += "after" },
        )),
        AppMenu("Help", listOf(MenuEntry.Item("About", true, null) { log += "about" })),
    )

    private fun subKey(state: MenuBarNavigationState, k: Key) =
        handleMenuKey(state, k, keyDown = true, alt = false, ctrl = false, shift = false, menus = subMenus)

    /** Tools open, keyboard highlight on the "Create ▸" row, submenu not yet open. */
    private fun atCreate() = MenuBarNavigationState(openMenuIndex = 0, focusedIndex = 0, highlightedItem = 1)

    @Test
    fun `right and enter open a highlighted submenu with its first selectable child`() {
        val start = atCreate()
        assertEquals(1, start.highlightedItem)
        assertNull(start.openSubmenuItem)
        for (k in listOf(Key.DirectionRight, Key.Enter)) {
            val r = subKey(start, k)
            assertTrue(r.consumed)
            assertNull(r.action)
            assertEquals(0, r.state.openMenuIndex)
            assertEquals(1, r.state.highlightedItem)
            assertEquals(1, r.state.openSubmenuItem)
            assertEquals(2, r.state.highlightedSubItem, "skips the separator and disabled X")
        }
    }

    @Test
    fun `up and down wrap inside an open submenu`() {
        val open = subKey(atCreate(), Key.DirectionRight).state
        val down = subKey(open, Key.DirectionDown).state
        assertEquals(3, down.highlightedSubItem)
        assertEquals(1, down.highlightedItem, "parent highlight stays on the submenu row")
        assertEquals(2, subKey(down, Key.DirectionDown).state.highlightedSubItem)
        assertEquals(3, subKey(open, Key.DirectionUp).state.highlightedSubItem)
    }

    @Test
    fun `left and escape close only the submenu`() {
        val open = subKey(subKey(atCreate(), Key.DirectionRight).state, Key.DirectionDown).state
        for (k in listOf(Key.DirectionLeft, Key.Escape)) {
            val r = subKey(open, k)
            assertTrue(r.consumed)
            assertEquals(0, r.state.openMenuIndex)
            assertEquals(1, r.state.highlightedItem)
            assertNull(r.state.openSubmenuItem)
            assertNull(r.state.highlightedSubItem)
        }
    }

    @Test
    fun `enter on a submenu child returns its action and closes the bar`() {
        val atSamsung = subKey(subKey(atCreate(), Key.DirectionRight).state, Key.DirectionDown).state
        val r = subKey(atSamsung, Key.Enter)
        assertFalse(r.state.isActive)
        assertNull(r.state.openSubmenuItem)
        assertNotNull(r.action).invoke()
        assertEquals(listOf("samsung"), log)
    }

    @Test
    fun `right on a plain item still moves to the next top-level menu`() {
        val atBefore = MenuBarNavigationState().activate(0, 2).hoverItem(0, subMenus)
        val r = subKey(atBefore, Key.DirectionRight).state
        assertEquals(1, r.openMenuIndex)
        assertEquals(0, r.highlightedItem)
        assertNull(r.openSubmenuItem)
        // and from inside an open submenu with a child highlighted, → also moves on
        val inSub = subKey(atCreate(), Key.DirectionRight).state
        val moved = subKey(inSub, Key.DirectionRight).state
        assertEquals(1, moved.openMenuIndex)
        assertNull(moved.openSubmenuItem)
        assertNull(moved.highlightedSubItem)
    }

    @Test
    fun `hovering a submenu row opens it, hovering another parent row closes it`() {
        val open = MenuBarNavigationState().activate(0, 2)
        val hovered = open.hoverItem(1, subMenus)
        assertEquals(1, hovered.openSubmenuItem)
        assertNull(hovered.highlightedSubItem, "a mouse-opened submenu has no highlighted child yet")
        val child = hovered.hoverSubItem(3, subMenus)
        assertEquals(3, child.highlightedSubItem)
        assertEquals(3, child.hoverSubItem(1, subMenus).highlightedSubItem, "a disabled child is not highlighted")
        assertNotNull(child.highlightedAction(subMenus)).invoke()
        assertEquals(listOf("samsung"), log)
        val other = child.hoverItem(3, subMenus)
        assertEquals(3, other.highlightedItem)
        assertNull(other.openSubmenuItem)
        assertNull(other.highlightedSubItem)
    }

    @Test
    fun `returning to the submenu row clears the child highlight so a click there runs nothing`() {
        val child = MenuBarNavigationState().activate(0, 2).hoverItem(1, subMenus).hoverSubItem(3, subMenus)
        val back = child.hoverItem(1, subMenus)
        assertEquals(1, back.openSubmenuItem, "the submenu stays open")
        assertNull(back.highlightedSubItem)
        assertNull(back.highlightedAction(subMenus))
    }

    @Test
    fun `disabled submenu never opens`() {
        val open = MenuBarNavigationState().activate(0, 2)
        val hovered = open.hoverItem(2, subMenus)
        assertNull(hovered.openSubmenuItem)
        assertFalse(hovered.highlightedItem == 2, "a disabled submenu row is not selectable")
        // keyboard skips it: After(3) follows Create(1)
        val atCreate = atCreate()
        assertEquals(3, subKey(atCreate, Key.DirectionDown).state.highlightedItem)
        // forcing the highlight onto it still does not open it
        val forced = MenuBarNavigationState(openMenuIndex = 0, focusedIndex = 0, highlightedItem = 2)
        assertNull(subKey(forced, Key.Enter).state.openSubmenuItem)
        assertNull(forced.openSubmenu(subMenus).openSubmenuItem)
    }
}
