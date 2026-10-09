package com.multiviewer.ui.menu

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MenuBarNavigationStateTest {
    @Test
    fun `activating a different header switches the open menu`() {
        val state = MenuBarNavigationState(openMenuIndex = 0).activate(2, menuCount = 4)

        assertEquals(2, state.openMenuIndex)
    }

    @Test
    fun `activating the open header closes the menu`() {
        val state = MenuBarNavigationState(openMenuIndex = 1).activate(1, menuCount = 4)

        assertEquals(null, state.openMenuIndex)
    }

    @Test
    fun `hover switches menus only while a menu is already open`() {
        assertEquals(2, MenuBarNavigationState(0).hover(2, menuCount = 4).openMenuIndex)
        assertEquals(null, MenuBarNavigationState().hover(2, menuCount = 4).openMenuIndex)
    }

    @Test
    fun `clicking a menu after hover switched to it keeps it open`() {
        val switchedByHover = MenuBarNavigationState(0).hover(2, menuCount = 4)

        assertEquals(2, switchedByHover.activate(2, menuCount = 4).openMenuIndex)
    }

    @Test
    fun `moving across headers wraps around and opens a menu`() {
        assertEquals(2, MenuBarNavigationState(3).move(-1, menuCount = 4).openMenuIndex)
        assertEquals(3, MenuBarNavigationState(0).move(-1, menuCount = 4).openMenuIndex)
        assertEquals(0, MenuBarNavigationState(3).move(1, menuCount = 4).openMenuIndex)
        assertEquals(3, MenuBarNavigationState().move(-1, menuCount = 4).openMenuIndex)
        assertEquals(0, MenuBarNavigationState().move(1, menuCount = 4).openMenuIndex)
    }

    @Test
    fun `closing clears the open menu`() {
        assertEquals(null, MenuBarNavigationState(2).close().openMenuIndex)
    }
}
