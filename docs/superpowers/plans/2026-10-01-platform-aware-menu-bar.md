# Platform-Aware Custom Menu Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's native Windows/Linux menu bar (dated Win32 HMENU rendering, no font/spacing control) with a Compose-drawn, Material3-styled custom menu bar, while leaving macOS exactly as it is today (native Cocoa NSMenu via the existing `androidx.compose.ui.window.MenuBar` API).

**Architecture:** Describe the menu bar once as plain data (`List<AppMenu>`, built via a small DSL whose call shapes mirror the native `Menu`/`Item`/`Separator`/`CheckboxItem` API) and render it two ways: `NativeAppMenuBar` (macOS, wraps the existing native calls) and `CustomAppMenuBar` (Windows/Linux, a Compose `Row` of `DropdownMenu`s styled with the app's own `MaterialTheme`). A window-level key handler in `Main.kt` supplies keyboard-shortcut dispatch for the custom renderer, since it isn't a real OS menu and gets no accelerator table for free.

**Tech Stack:** Kotlin, Compose Desktop (Compose Multiplatform 1.7.3), Material3, JUnit 5 / kotlin.test (existing project conventions).

## Global Constraints

- Platform detection: exactly one check, computed once -- `System.getProperty("os.name").lowercase().contains("mac")`. True = macOS = native renderer. False = Windows/Linux = custom renderer.
- macOS rendering and behavior must not change at all -- same native `MenuBar`/`Menu`/`Item`/`Separator`/`CheckboxItem` calls as today, just driven by the shared `List<AppMenu>` data instead of hand-written inline calls.
- The existing menu-building logic in `Main.kt` (all `appState`/`currentTab`/`language`-dependent `enabled`/`checked`/`onClick` computations, including existing comments) is preserved verbatim -- only the DSL call shapes change (`Menu`->`appMenu`, `Item`->`item`, `Separator()`->`separator()`, `CheckboxItem`->`checkbox`, `KeyShortcut`->`AppKeyShortcut`). No menu/item is added, removed, reordered, or re-gated as part of this work.
- No nested submenus -- the existing structure is flat (8 top-level menus, each holding only `Item`/`Separator`/`CheckboxItem`) and the custom renderer does not need to support `Menu`-inside-`Menu`.
- The custom renderer reuses the app's existing Material3 `MaterialTheme.typography`/`colorScheme` and this project's own `AppColors`/`AppTypography` (`com.multiviewer.ui.Theme.kt`) -- no new design system, no new color/font constants.
- `AppKeyShortcut(meta = true)` displays and matches as `Ctrl` on Windows/Linux (there is no separate Meta/Super convention to honor there) -- `shift` composes normally (e.g. `Ctrl+Shift+G`).
- The `AppMenu`/`MenuEntry`/`AppKeyShortcut` data model and the shortcut-lookup/matching/display logic are plain Kotlin with no Compose/platform dependency -- these get real JUnit tests. The two renderers (`NativeAppMenuBar`, `CustomAppMenuBar`) are Compose UI composables with no automated test harness, matching every other window in this app -- verification for those is manual/visual only.

---

### Task 1: `AppMenu`/`MenuEntry`/`AppKeyShortcut` data model and DSL builder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/menu/AppMenuDsl.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/menu/AppMenuDslTest.kt`

**Interfaces:**
- Produces: `data class AppKeyShortcut(val key: Key, val meta: Boolean = false, val shift: Boolean = false)`; `sealed class MenuEntry` with `data class Item(val label: String, val enabled: Boolean, val shortcut: AppKeyShortcut?, val onClick: () -> Unit)`, `data class Checkbox(val label: String, val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)`, `object Separator`; `data class AppMenu(val label: String, val entries: List<MenuEntry>)`; `fun buildAppMenuBar(block: AppMenuBarBuilder.() -> Unit): List<AppMenu>` with builder-scope functions `appMenu(label, block)`, and inside that, `item(label, enabled = true, shortcut = null, onClick)`, `checkbox(label, checked, onCheckedChange)`, `separator()`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/menu/AppMenuDslTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.menu.AppMenuDslTest"`
Expected: FAIL to compile (`AppMenuDsl.kt` doesn't exist yet -- `buildAppMenuBar`, `AppKeyShortcut`, `MenuEntry` are unresolved references).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/menu/AppMenuDsl.kt`:

```kotlin
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

/** One entry in a menu: a clickable item, a checkbox item, or a visual separator. */
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
}

/** One top-level menu (e.g. "File") and its entries, in display order. */
data class AppMenu(val label: String, val entries: List<MenuEntry>)

/** Builder scope for one menu's entries -- see [AppMenuBarBuilder.appMenu]. */
class AppMenuBuilder internal constructor(private val label: String) {
    private val entries = mutableListOf<MenuEntry>()

    fun item(label: String, enabled: Boolean = true, shortcut: AppKeyShortcut? = null, onClick: () -> Unit) {
        entries.add(MenuEntry.Item(label, enabled, shortcut, onClick))
    }

    fun checkbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        entries.add(MenuEntry.Checkbox(label, checked, onCheckedChange))
    }

    fun separator() {
        entries.add(MenuEntry.Separator)
    }

    internal fun build(): AppMenu = AppMenu(label, entries.toList())
}

/** Builder scope for the whole menu bar -- see [buildAppMenuBar]. */
class AppMenuBarBuilder internal constructor() {
    private val menus = mutableListOf<AppMenu>()

    fun appMenu(label: String, block: AppMenuBuilder.() -> Unit) {
        menus.add(AppMenuBuilder(label).apply(block).build())
    }

    internal fun build(): List<AppMenu> = menus.toList()
}

/**
 * Describes the app's whole menu bar once, as plain data -- both the native (macOS) and custom
 * (Windows/Linux) renderers consume the same [List]<[AppMenu]> this produces, so the enabled/
 * checked/onClick logic in Main.kt is written exactly once regardless of platform.
 */
fun buildAppMenuBar(block: AppMenuBarBuilder.() -> Unit): List<AppMenu> = AppMenuBarBuilder().apply(block).build()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.menu.AppMenuDslTest"`
Expected: PASS (6 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/menu/AppMenuDsl.kt app/src/test/kotlin/com/multiviewer/ui/menu/AppMenuDslTest.kt
git commit -m "feat: AppMenu/MenuEntry/AppKeyShortcut data model and DSL builder"
```

---

### Task 2: Shortcut lookup, matching, and display

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcuts.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcutsTest.kt`

**Interfaces:**
- Consumes: `AppMenu`, `MenuEntry`, `AppKeyShortcut` from Task 1 (`com.multiviewer.ui.menu`).
- Produces: `fun List<AppMenu>.collectShortcuts(): List<Pair<AppKeyShortcut, () -> Unit>>`; `fun AppKeyShortcut.matches(keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean`; `fun AppKeyShortcut.displayString(): String`. Task 5 (Main.kt) calls `collectShortcuts()` + `matches()` from its window-level key handler, and Task 4 (`CustomAppMenuBar`) calls `displayString()` to show the shortcut hint beside each item.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcutsTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.menu.MenuKeyboardShortcutsTest"`
Expected: FAIL to compile (`collectShortcuts`, `matches`, `displayString` are unresolved references).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcuts.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.menu.MenuKeyboardShortcutsTest"`
Expected: PASS (6 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcuts.kt app/src/test/kotlin/com/multiviewer/ui/menu/MenuKeyboardShortcutsTest.kt
git commit -m "feat: menu shortcut lookup, matching, and display formatting"
```

---

### Task 3: Native (macOS) menu bar renderer

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/menu/NativeMenuBarRenderer.kt`

**Interfaces:**
- Consumes: `AppMenu`, `MenuEntry`, `AppKeyShortcut` from Task 1.
- Produces: `@Composable fun FrameWindowScope.NativeAppMenuBar(menus: List<AppMenu>)`. Task 5 (Main.kt) calls this when `isMacOS`.

No test for this task -- it is a thin Compose UI wrapper over `androidx.compose.ui.window.MenuBar`/`Menu`/`Item`/`Separator`/`CheckboxItem`, matching this project's existing convention that Compose UI composables have no automated test harness (see Task 1/2's own tests, which cover everything that reaches this renderer as plain data before it does).

- [ ] **Step 1: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/menu/NativeMenuBarRenderer.kt`:

```kotlin
package com.multiviewer.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar

private fun AppKeyShortcut.toNativeKeyShortcut(): KeyShortcut = KeyShortcut(key, meta = meta, shift = shift)

/**
 * Renders [menus] via the OS's own native menu bar (Cocoa NSMenu on macOS) -- today's exact
 * behavior, just driven by the shared [AppMenu] data instead of hand-written Menu/Item calls.
 * macOS only; see CustomMenuBar.kt for the Windows/Linux renderer.
 */
@Composable
fun FrameWindowScope.NativeAppMenuBar(menus: List<AppMenu>) {
    MenuBar {
        menus.forEach { menu ->
            Menu(menu.label) {
                menu.entries.forEach { entry ->
                    when (entry) {
                        is MenuEntry.Item -> Item(
                            entry.label,
                            enabled = entry.enabled,
                            shortcut = entry.shortcut?.toNativeKeyShortcut(),
                            onClick = entry.onClick,
                        )
                        is MenuEntry.Checkbox -> CheckboxItem(
                            entry.label,
                            checked = entry.checked,
                            onCheckedChange = entry.onCheckedChange,
                        )
                        MenuEntry.Separator -> Separator()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (this file isn't wired into `Main.kt` yet, so nothing functional to exercise -- a clean compile is the whole bar for this step).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/menu/NativeMenuBarRenderer.kt
git commit -m "feat: native (macOS) menu bar renderer over the shared AppMenu data"
```

---

### Task 4: Custom (Windows/Linux) menu bar renderer

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/menu/CustomMenuBar.kt`

**Interfaces:**
- Consumes: `AppMenu`, `MenuEntry` from Task 1; `displayString()` from Task 2; `AppColors`/`AppTypography` from `com.multiviewer.ui.Theme` (existing).
- Produces: `@Composable fun CustomAppMenuBar(menus: List<AppMenu>, modifier: Modifier = Modifier)`. Task 5 (Main.kt) calls this when `!isMacOS`, placed above the main window content.

No test for this task, same reasoning as Task 3.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/menu/CustomMenuBar.kt`:

```kotlin
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
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/menu/CustomMenuBar.kt
git commit -m "feat: custom Material3 menu bar renderer for Windows/Linux"
```

---

### Task 5: Wire both renderers into Main.kt

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt:1-40` (imports), `:298-657` (menu bar construction), `:915,:1203` (wrap main content for the custom bar), `:339-380` (window-level key handler)

**Interfaces:**
- Consumes: everything from Tasks 1-4 (`com.multiviewer.ui.menu.*`).

Read the file first (`Main.kt` has grown past what any task brief should re-paste) and make these five changes in place. Every snippet below is written against the file's current content -- if a prior task's commit shifted line numbers, match by the surrounding code shown, not the line numbers.

- [ ] **Step 1: Update imports**

Remove these two imports (both become unused once Step 3 below removes the last direct call sites in this file):

```kotlin
import androidx.compose.ui.input.key.KeyShortcut
```
```kotlin
import androidx.compose.ui.window.MenuBar
```

Add these imports (near the other `com.multiviewer.*` imports):

```kotlin
import com.multiviewer.ui.menu.AppKeyShortcut
import com.multiviewer.ui.menu.AppMenu
import com.multiviewer.ui.menu.CustomAppMenuBar
import com.multiviewer.ui.menu.NativeAppMenuBar
import com.multiviewer.ui.menu.buildAppMenuBar
import com.multiviewer.ui.menu.collectShortcuts
import com.multiviewer.ui.menu.matches
```

- [ ] **Step 2: Hoist platform detection and the published menu list**

In `runGuiApplication`, immediately after `val appState = remember { AppState() }`, add:

```kotlin
    // Computed once: true on macOS (native NSMenu via NativeAppMenuBar), false on Windows/Linux
    // (CustomAppMenuBar -- the native Win32 HMENU menu Compose would otherwise render there has no
    // font/spacing control and looks dated). See docs/superpowers/specs/2026-09-30-platform-aware-menu-bar-design.md.
    val isMacOS = remember { System.getProperty("os.name").lowercase().contains("mac") }
    // Published by the menu-building code inside Window's content below (Step 3) via SideEffect,
    // so the onKeyEvent handler (Step 5), declared as a sibling parameter of the SAME Window(...)
    // call and therefore unable to see that content lambda's own local vals, can still look up
    // shortcuts against the latest menu state.
    var menuBar by remember { mutableStateOf<List<AppMenu>>(emptyList()) }
```

- [ ] **Step 3: Replace the `MenuBar { ... }` block with DSL-based construction**

Replace the entire existing block -- from `MenuBar {` through its matching closing `}` (everything from the `Menu(I18n.menuFile(language))` menu through the `Menu(I18n.menuHelp(language))` menu) -- with:

```kotlin
        val builtMenuBar = buildAppMenuBar {
            appMenu(I18n.menuFile(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true
                val hasGainmap = currentTab != null && !currentTab.isLoading && currentTab.gainmapInfo?.hasGainmap == true

                item(I18n.menuOpen(language), shortcut = AppKeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                item(I18n.menuClose(language), enabled = appState.tabs.isNotEmpty(), shortcut = AppKeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
            // Track extraction, previously tacked onto the end of 파일 alongside open/close.
            // The motion photo extractions stay in 모션포토: they belong with the create/analyze
            // commands for that format rather than with plain track extraction.
            appMenu(I18n.menuExtract(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true

                item(
                    I18n.menuExtractVideoTrack(language),
                    enabled = hasVideoTrack,
                    onClick = { currentTab?.let { extractVideoTrackFromCurrentFile(appState, it) } },
                )
                item(
                    I18n.menuExtractAudioTrack(language),
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
            // Every gain map command in one place -- they were split between 파일 (extract) and
            // 분석 (the two viewers), which meant hunting through two menus for one feature.
            appMenu(I18n.menuGainmap(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val hasGainmap = currentTab != null && !currentTab.isLoading && currentTab.gainmapInfo?.hasGainmap == true
                val hasGainmapImage = hasGainmap && currentTab?.gainmapInfo?.hasGainmapImage == true

                item(
                    I18n.menuViewGainmapImage(language),
                    enabled = hasGainmapImage,
                    shortcut = AppKeyShortcut(Key.G, meta = true),
                    onClick = { currentTab?.isGainmapImagePopupOpen = true },
                )
                item(
                    I18n.menuExtractGainmapImage(language),
                    enabled = hasGainmapImage,
                    onClick = { currentTab?.let { extractGainmapImage(appState, it, language) } },
                )
                item(
                    I18n.menuViewGainmapXmp(language),
                    enabled = hasGainmap,
                    shortcut = AppKeyShortcut(Key.G, meta = true, shift = true),
                    onClick = { currentTab?.isGainmapXmpPopupOpen = true },
                )
                separator()
                // Not gated on hasGainmap, unlike everything above it: this lists whatever XMP the
                // file actually holds, which is worth looking at on files with no gain map at all.
                item(
                    I18n.menuViewFileXmp(language),
                    enabled = currentTab != null && !currentTab.isLoading,
                    onClick = { currentTab?.isFileXmpPopupOpen = true },
                )
            }
            appMenu(I18n.menuAnalyze(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val hasActiveFile = currentTab != null && !currentTab.isLoading && currentTab.root != null
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true

                item(
                    I18n.menuDumpStructure(language),
                    enabled = hasActiveFile,
                    shortcut = AppKeyShortcut(Key.D, meta = true, shift = true),
                    onClick = { dumpStructureWindowOpen = true },
                )
                item(
                    I18n.menuCheckStructure(language),
                    enabled = hasActiveFile,
                    shortcut = AppKeyShortcut(Key.C, meta = true, shift = true),
                    onClick = { checkStructureWindowOpen = true },
                )
                separator()
                item(
                    I18n.menuGenerateAiPrompt(language),
                    enabled = hasActiveFile,
                    shortcut = AppKeyShortcut(Key.P, meta = true, shift = true),
                    onClick = { appState.aiPromptWindowOpen = true },
                )
                separator()
                item(
                    I18n.menuAvSyncAnalysis(language),
                    enabled = isVideo,
                    shortcut = AppKeyShortcut(Key.S, meta = true, shift = true),
                    onClick = { avSyncWindowOpen = true },
                )
                item(
                    I18n.menuBitstreamCorruption(language),
                    enabled = isVideo,
                    shortcut = AppKeyShortcut(Key.B, meta = true, shift = true),
                    onClick = { bitstreamCorruptionWindowOpen = true },
                )
                val hasSefData = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } } != null
                item(
                    I18n.menuSefIntegrityCheck(language),
                    enabled = hasSefData,
                    onClick = { sefIntegrityWindowOpen = true },
                )
                item(
                    I18n.menuViewFrameIntervals(language),
                    enabled = hasVideoTrack,
                    onClick = { frameIntervalWindowOpen = true },
                )
            }
            appMenu(I18n.menuMotionPhoto(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isImage = currentTab != null && !currentTab.isLoading && currentTab.type == MediaType.IMAGE
                val isHeic = currentTab?.file?.extension?.lowercase(java.util.Locale.US) in setOf("heic", "heif")
                item(
                    I18n.menuCreateMotionPhotoV2(language),
                    enabled = isImage,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V2_MOTION_PHOTO) } },
                )
                item(
                    I18n.menuCreateMotionPhotoV1(language),
                    enabled = isImage && !isHeic,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V1_MICRO_VIDEO) } },
                )
                separator()
                item(
                    I18n.menuExtractMotionVideo(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                item(
                    I18n.menuExtractPreviewVideo(language),
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
                separator()
                item(
                    I18n.menuMotionFrameDropAnalysis(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { motionPhotoFrameIntervalWindowOpen = true },
                )
                separator()
                val hasMotionPhoto = currentTab?.root?.let { r ->
                    (findFirst(r) { it.type == "sefd" }?.let { sefd ->
                        // Mirrors MotionPhotoIntegrityAnalyzer.kt's detectedFormats gate: MotionPhoto_Data
                        // is mandatory for an intact trailer, but a structurally broken one (warnings
                        // non-empty -- SefdBoxDecoder bails with no children in that case) must still
                        // enable the menu so its CRITICAL diagnosis stays reachable.
                        sefd.children.any { it.type == "MotionPhoto_Data" } || sefd.warnings.isNotEmpty()
                    } == true) ||
                        findFirst(r) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
                        findFirst(r) {
                            it.fields.any { f ->
                                f.name == "xmp" && (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true))
                            }
                        } != null
                } ?: false
                item(
                    I18n.menuMotionPhotoIntegrityCheck(language),
                    enabled = hasMotionPhoto,
                    onClick = { motionPhotoIntegrityWindowOpen = true },
                )
            }
            appMenu(I18n.menuTools(language)) {
                item(
                    I18n.menuCompareFiles(language),
                    shortcut = AppKeyShortcut(Key.D, meta = true),
                    onClick = { imageCompareWindowOpen = true },
                )
                item(
                    I18n.menuQualityBenchmark(language),
                    onClick = { qualityCompareWindowOpen = true },
                )
            }
            // The "탐색" menu used to sit here. Removed as confusing: its panel toggle duplicated the
            // 구조 트리 / 폴더 탐색 tabs right below, and "탐색" read as being about the folder explorer
            // while actually holding file-stepping commands.
            appMenu(I18n.menuView(language)) {
                checkbox(
                    I18n.menuDarkTheme(language),
                    checked = themeMode == ThemeMode.DARK,
                    onCheckedChange = {
                        themeMode = ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
                checkbox(
                    I18n.menuLightTheme(language),
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = {
                        themeMode = ThemeMode.LIGHT
                        saveThemeMode(themeMode)
                    },
                )
                separator()
                checkbox(
                    I18n.menuPixelGrid(language),
                    checked = showPixelGrid,
                    onCheckedChange = {
                        showPixelGrid = it
                        saveShowPixelGrid(it)
                    },
                )
                val codecViewCurrentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                item(
                    I18n.menuMotionVectors(language),
                    enabled = codecViewCurrentTab?.type == MediaType.VIDEO &&
                        codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, codecViewCurrentTab.videoCodecName),
                    onClick = { codecViewPopupWindowMode = CodecViewMode.MOTION_VECTORS },
                )
                item(
                    I18n.menuQpHeatmap(language),
                    enabled = codecViewCurrentTab?.type == MediaType.VIDEO &&
                        codecViewSupportedFor(CodecViewMode.QP_HEATMAP, codecViewCurrentTab.videoCodecName),
                    onClick = { codecViewPopupWindowMode = CodecViewMode.QP_HEATMAP },
                )
                separator()
                checkbox(
                    I18n.menuKorean(language),
                    checked = language == AppLanguage.KO,
                    onCheckedChange = {
                        language = AppLanguage.KO
                        saveLanguage(language)
                    },
                )
                checkbox(
                    I18n.menuEnglish(language),
                    checked = language == AppLanguage.EN,
                    onCheckedChange = {
                        language = AppLanguage.EN
                        saveLanguage(language)
                    },
                )
            }
            appMenu(I18n.menuHelp(language)) {
                item(
                    I18n.menuOnlineRepo(language),
                    onClick = {
                        try {
                            val config = com.multiviewer.update.UpdateConfig.load()
                            val uri = java.net.URI(config.repoUrl.ifBlank { com.multiviewer.update.UpdateConfig.DEFAULT_REPO_URL })
                            java.awt.Desktop.getDesktop().browse(uri)
                        } catch (_: Exception) {}
                    },
                )
                separator()
                item(
                    I18n.menuAbout(language),
                    onClick = { aboutWindowOpen = true },
                )
            }
        }
        SideEffect { menuBar = builtMenuBar }
        if (isMacOS) {
            NativeAppMenuBar(builtMenuBar)
        }
```

- [ ] **Step 4: Place the custom bar above the main window content**

Find `Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {` (the one directly inside `CompositionLocalProvider`, holding the empty-state placeholder / tab row / tab content -- NOT any of the `Dialog(...)` blocks above it). Wrap it:

Before that line, insert:

```kotlin
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isMacOS) {
                    CustomAppMenuBar(builtMenuBar, modifier = Modifier.fillMaxWidth())
                    HorizontalDivider(color = AppColors.Border)
                }
```

Change that line's own modifier from `Modifier.fillMaxSize()` to `Modifier.weight(1f).fillMaxWidth()`:

```kotlin
                Surface(modifier = Modifier.weight(1f).fillMaxWidth(), color = AppColors.Background) {
```

And after that `Surface(...) { ... }` block's own matching closing `}` (the first `}` that closes it, immediately followed by the end of `CompositionLocalProvider`'s content), add the `Column`'s closing brace:

```kotlin
            }
```

- [ ] **Step 5: Extend the window-level key handler**

In the `Window(...)` call's `onKeyEvent` lambda, add the custom-renderer shortcut dispatch before the existing Ctrl/Cmd+C hex-copy check:

```kotlin
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                // Windows/Linux only: the custom menu bar isn't a native OS menu, so it has no
                // OS-level accelerator table of its own -- this is what makes its shortcuts work
                // even while no menu is open. macOS doesn't need this: NativeAppMenuBar's native
                // menu bar already gets shortcut dispatch for free from Cocoa's own NSMenu.
                if (!isMacOS) {
                    val matched = menuBar.collectShortcuts().find { (shortcut, _) -> shortcut.matches(keyEvent) }
                    if (matched != null) {
                        matched.second()
                        return@Window true
                    }
                }
                val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                if (isCtrlOrMeta && !keyEvent.isShiftPressed && !keyEvent.isAltPressed && keyEvent.key == Key.C) {
                    val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                    if (currentTab != null && !currentTab.isLoading) {
                        val activeField = currentTab.selected?.fields?.let { fields -> currentTab.selectedField?.takeIf { it in fields } }
                        val hexHighlightRange = currentTab.parameterSetHighlightRange
                            ?: currentTab.tileHighlightRange
                            ?: activeField?.let { it.offset until (it.offset + it.length) }
                            ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                            ?: currentTab.selectedFrame?.let { frame ->
                                frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                            }
                        if (hexHighlightRange != null && hexHighlightRange.first >= 0) {
                            try {
                                java.io.RandomAccessFile(currentTab.file, "r").use { raf ->
                                    val buf = readRangeBytes(raf, hexHighlightRange)
                                    val dump = formatHexDump(buf, hexHighlightRange.first)
                                    if (com.multiviewer.util.ClipboardUtil.copyToClipboard(dump)) {
                                        appState.statusMessage = "선택된 박스/마커 데이터(Hex Dump)가 클립보드에 복사되었습니다."
                                        return@Window true
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            false
        },
```

(Everything from `val isCtrlOrMeta = ...` to the end is the pre-existing hex-copy logic, unchanged -- only the new `if (!isMacOS) { ... }` block above it is new.)

- [ ] **Step 6: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. Fix any unresolved-reference or unused-import errors before moving on -- a clean compile is required before Step 7.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, same pass count as before this task (this task touches no test files and no behavior the existing suite exercises beyond compilation).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: wire native/custom menu bar renderers into Main.kt by platform"
```

---

### Task 6: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` and `./gradlew test` -- expect `BUILD SUCCESSFUL` on both (should already be true from Task 5, confirming the state after all tasks together).

- [ ] **Step 2 (manual, GUI, macOS regression check)**: Launch the app (`./gradlew run` or the project's existing run path) on this machine (macOS). Confirm:
  - The menu bar still renders as the native top-of-screen macOS menu bar (NOT the custom in-window bar) -- `isMacOS` must resolve `true` here.
  - All 8 menus are present with the same items, in the same order, as before this change.
  - A few representative items from different menus work: File > Open, View > Dark/Light theme toggle (checkmark reflects state), Tools > Compare Files.
  - A representative keyboard shortcut still works with the menu closed (e.g. Cmd+O opens the file dialog) -- this exercises the UNCHANGED native path, not the new custom-renderer key handler.

- [ ] **Step 3 (manual, GUI, owed to the user -- no Windows/Linux machine in this environment)**: On Windows (or Linux), confirm:
  - The custom in-window menu bar renders below the title bar, styled with the app's Material3 theme (not the native Win32 menu).
  - All 8 menus/items are present and functionally identical (enabled states, checkmarks, click actions) to the native menu.
  - Keyboard shortcuts work with the custom menu closed (e.g. Ctrl+O opens the file dialog, Ctrl+Shift+G opens gain map XMP).
  - Window resizing / theme switching (dark/light) doesn't break the custom bar's layout.

- [ ] **Step 4**: Report Step 3 as outstanding to the user (consistent with this project's established pattern of noting a GUI-manual-pass as owed when no suitable machine is available in this environment).
