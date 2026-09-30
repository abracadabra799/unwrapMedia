# Platform-Aware Custom Menu Bar Design

## Problem

The app's menu bar (`MenuBar { Menu(...) { Item(...); Separator(); CheckboxItem(...) } }` in
`Main.kt`) uses `androidx.compose.ui.window`'s native menu API — a thin wrapper over the OS's own
native menu bar (`java.awt.MenuBar` under the hood). On macOS this integrates with Cocoa's
`NSMenu` and looks like any other modern Mac menu. On Windows (and Linux), the same API renders
via the legacy Win32 `HMENU` native menu system, which Windows itself hasn't visually modernized
in decades — thick padding, a wide left gutter permanently reserved for a checkbox/icon column
even when unused, and dated font rendering. Compose has no ability to control font, spacing, or
color for this native menu on any platform; the OS draws it entirely.

The user's Mac experience is fine as-is. The Windows experience is the actual complaint.

## Goal

Replace the menu bar with a Compose-drawn custom implementation on Windows/Linux only, styled with
the app's own Material3 theme (font, colors, dark/light mode) — while leaving macOS exactly as it
is today (native top-of-screen menu bar), matching the precedent IntelliJ/Android Studio itself
sets (native macOS menu bar, custom in-window menu bar on Windows/Linux).

## Non-Goals

- Changing macOS's menu rendering at all.
- Redesigning the menu's own information architecture (which items live under which top-level
  menu) — this is a rendering-layer change only; the existing 8 menus / 37 items / structure stay
  as-is.
- Nested submenus — the existing menu structure has none (flat: 8 top-level `Menu`s, each holding
  only `Item`/`Separator`/`CheckboxItem`, no `Menu`-inside-`Menu`), so the custom renderer doesn't
  need to support them. If a future menu item needs a submenu, this is a deliberately deferred
  concern, not a current requirement.
- A custom visual style distinct from the app's own theme — reuses the app's existing Material3
  `MaterialTheme.typography`/`colorScheme` as-is (dark/light mode included), no new design system.

## Architecture

### Platform detection

One check, computed once: `System.getProperty("os.name").lowercase().contains("mac")`.

### Describe once, render two ways

The ~250 lines of existing menu-building code in `Main.kt` (all the `appState`/`currentTab`/
`language`-dependent `enabled`/`checked`/`onClick` logic for all 37 items) stays where it is and
keeps its exact logic — only the calls it makes change, from the native API's `Menu`/`Item`/
`Separator`/`CheckboxItem` to a small custom DSL with matching call shapes:

```kotlin
appMenu(I18n.menuFile(language)) {
    item(I18n.menuOpen(language), shortcut = AppKeyShortcut(Key.O, meta = true)) { showOpenFileDialog(appState) }
    item(I18n.menuClose(language), enabled = appState.tabs.isNotEmpty(), shortcut = AppKeyShortcut(Key.W, meta = true)) { appState.closeTab(appState.selectedTabIndex) }
}
```

This DSL builds a plain data structure (not Compose UI directly):

```kotlin
data class AppKeyShortcut(val key: Key, val meta: Boolean = false, val shift: Boolean = false)

sealed class MenuEntry {
    data class Item(val label: String, val enabled: Boolean, val shortcut: AppKeyShortcut?, val onClick: () -> Unit) : MenuEntry()
    data class Checkbox(val label: String, val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : MenuEntry()
    object Separator : MenuEntry()
}

data class AppMenu(val label: String, val entries: List<MenuEntry>)
```

A builder scope (`appMenu(label) { item(...); separator(); checkbox(...) }`, collecting an
`AppMenu`) assembles the full `List<AppMenu>` once per recomposition — cheap, since it's just data.

### Two renderers consume the same `List<AppMenu>`

- **macOS renderer**: walks the list and calls the *existing* native API directly
  (`MenuBar { menus.forEach { m -> Menu(m.label) { m.entries.forEach { ... Item(...) / Separator() / CheckboxItem(...) } } } }`)
  — today's exact native behavior, unchanged, just now driven by the data structure instead of
  being hand-written inline.
- **Windows/Linux renderer**: a new composable rendering a `Row` across the top of the window
  content (inside the `Window`'s own content area, not a native menu — matching where IntelliJ
  puts its Windows/Linux menu bar). Each `AppMenu.label` is a clickable `Text` that toggles a
  Material3 `DropdownMenu` (a fully Compose-drawn component, already available via this project's
  existing `compose.material3` dependency) anchored under it, containing `DropdownMenuItem` rows
  for `Item`/`Checkbox` and a `HorizontalDivider` for `Separator` — styled via
  `MaterialTheme.typography`/`colorScheme`, no native OS involvement at all.

### Keyboard shortcuts on Windows/Linux

The native menu handles shortcut key-capture automatically (an OS-level accelerator table); a
custom in-window menu bar does not receive key events when it isn't focused/open. The custom
renderer therefore also installs one window-level `onKeyEvent` handler that flattens the same
`List<AppMenu>` into a `(AppKeyShortcut) -> (() -> Unit)?` lookup and invokes the matching item's
`onClick` directly, independent of whether any menu is currently open. `AppKeyShortcut(meta =
true)` displays and matches as `Ctrl` on Windows/Linux (mirroring what the native menu already
does today) — `shift` composes normally (e.g. `Ctrl+Shift+G`).

## Testing Plan

Compose UI has no existing automated-test harness in this codebase (matching every other window in
this app) — verification is manual/visual, per the project's established pattern:

- Manual: run on Windows (or a Windows VM/CI runner if available) and visually confirm the custom
  menu bar renders with the app's theme font/spacing, all 8 menus/37 items are present and
  functionally identical (enabled states, checkmarks, click actions) to today's native menu.
- Manual: confirm every existing keyboard shortcut still works with the menu closed.
- Manual: confirm macOS is visually and functionally unchanged (still the native top-of-screen
  menu bar) — a regression check, not new behavior.
- The `AppMenu`/`MenuEntry` data model and the DSL builder that assembles it are plain Kotlin data
  classes with no Compose/platform dependency — these ARE unit-testable (e.g. "building this DSL
  call produces this exact `List<AppMenu>`"), and should get real JUnit tests even though the two
  renderers themselves can't be.
