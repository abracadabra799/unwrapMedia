package com.multiviewer.ui.menu

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMenuDslTest {

    @Test
    fun `buildAppMenuBar collects menus in declaration order`() {
        val menus = buildAppMenuBar {
            appMenu("File") { item("Open") {} }
            appMenu("Edit") { item("Copy") {} }
        }
        assertEquals(listOf("File", "Edit"), menus.map { it.label })
    }

    @Test
    fun `appMenu collects entries in declaration order`() {
        var clicked = false
        val menus = buildAppMenuBar {
            appMenu("File") {
                item("Open", onClick = { clicked = true })
                separator()
                checkbox("Dark theme", checked = true, onCheckedChange = {})
            }
        }
        val entries = menus.single().entries
        assertEquals(3, entries.size)
        val item = entries[0] as MenuEntry.Item
        assertEquals("Open", item.label)
        assertTrue(item.enabled)
        item.onClick()
        assertTrue(clicked)
        assertEquals(MenuEntry.Separator, entries[1])
        val checkbox = entries[2] as MenuEntry.Checkbox
        assertEquals("Dark theme", checkbox.label)
        assertTrue(checkbox.checked)
    }

    @Test
    fun `item defaults to enabled with no shortcut`() {
        val menus = buildAppMenuBar { appMenu("File") { item("Open") {} } }
        val item = menus.single().entries.single() as MenuEntry.Item
        assertTrue(item.enabled)
        assertEquals(null, item.shortcut)
    }

    @Test
    fun `item honors explicit enabled and shortcut`() {
        val shortcut = AppKeyShortcut(Key.O, meta = true)
        val menus = buildAppMenuBar {
            appMenu("File") { item("Open", enabled = false, shortcut = shortcut) {} }
        }
        val item = menus.single().entries.single() as MenuEntry.Item
        assertFalse(item.enabled)
        assertEquals(shortcut, item.shortcut)
    }

    @Test
    fun `checkbox onCheckedChange receives the new state`() {
        var received: Boolean? = null
        val menus = buildAppMenuBar {
            appMenu("View") { checkbox("Grid", checked = false, onCheckedChange = { received = it }) }
        }
        (menus.single().entries.single() as MenuEntry.Checkbox).onCheckedChange(true)
        assertEquals(true, received)
    }

    @Test
    fun `AppKeyShortcut equality is structural`() {
        assertEquals(AppKeyShortcut(Key.O, meta = true), AppKeyShortcut(Key.O, meta = true))
        assertFalse(AppKeyShortcut(Key.O, meta = true) == AppKeyShortcut(Key.O, meta = true, shift = true))
    }

    @Test
    fun `submenu builds into MenuEntry Submenu with its entries in order`() {
        var clicked = false
        val menus = buildAppMenuBar {
            appMenu("Motion") {
                item("Before") {}
                submenu("Create", enabled = false) {
                    item("Google", onClick = { clicked = true })
                    separator()
                    checkbox("Flag", checked = true, onCheckedChange = {})
                    item("Legacy", enabled = false) {}
                }
            }
        }
        val entries = menus.single().entries
        assertEquals(2, entries.size)
        val submenu = entries[1] as MenuEntry.Submenu
        assertEquals("Create", submenu.label)
        assertFalse(submenu.enabled)
        assertEquals(4, submenu.entries.size)
        assertEquals("Google", (submenu.entries[0] as MenuEntry.Item).label)
        assertEquals(MenuEntry.Separator, submenu.entries[1])
        assertEquals("Flag", (submenu.entries[2] as MenuEntry.Checkbox).label)
        assertFalse((submenu.entries[3] as MenuEntry.Item).enabled)
        (submenu.entries[0] as MenuEntry.Item).onClick()
        assertTrue(clicked)
    }

    @Test
    fun `submenu defaults to enabled`() {
        val menus = buildAppMenuBar { appMenu("M") { submenu("S") { item("A") {} } } }
        assertTrue((menus.single().entries.single() as MenuEntry.Submenu).enabled)
    }
}
