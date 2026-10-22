# Menu submenus + "모션포토 생성 ▸" — design

Date: 2026-10-23. Status: approved (user: "서브메뉴로 대체"). Same branch as the motion-photo builder modes.

## Goal

Replace the three flat motion-photo creation items in the Tools menu with one submenu:

```
모션포토 생성 / Create Motion Photo  ▸  구글 스펙 준수 (권장) / Google spec (recommended)
                                         삼성 모션포토 (SEF 포함) / Samsung motion photo (with SEF)
                                         MicroVideo v1 (레거시) / MicroVideo v1 (legacy)
```

The submenu is enabled when the current tab is an image; inside it the MicroVideo item keeps its existing rule
(disabled for HEIC). Labels via `I18n` (bilingual).

## Design

The menu bar is data-driven (`ui/menu/AppMenuDsl.kt`) and rendered natively on macOS
(`NativeMenuBarRenderer.kt`) and by a Compose VS Code-style bar on Windows/Linux (`CustomMenuBar.kt` +
pure `MenuBarNavigationState.kt`).

- **DSL:** new `MenuEntry.Submenu(label: String, enabled: Boolean, entries: List<MenuEntry>)` and
  `AppMenuBuilder.submenu(label, enabled = true) { item(...) ... }`. One nesting level is enough (submenu entries may be
  Item / Checkbox / Separator, not further Submenus — the builder for submenu scope does not offer `submenu`).
- **Native (macOS):** `Menu(label, enabled = …) { … }` nested inside the parent `Menu`, same entry mapping.
- **Shortcuts:** `MenuKeyboardShortcuts` flattening recurses into submenus (a shortcut inside a submenu still
  works; disabled submenu ⇒ its items are not triggered).
- **Custom bar (Windows/Linux), VS Code behaviour:**
  - A Submenu row shows a trailing `▸`; hovering it (or clicking it) opens the child popup to the right of the row,
    top-aligned with it; hovering another row of the parent closes the child.
  - Keyboard: with a Submenu row highlighted, → or Enter opens it and highlights its first selectable entry; in an
    open submenu ↑/↓ move within it, ← or Esc closes only the submenu (highlight returns to the Submenu row), Enter
    activates the item and closes the whole bar. → on a non-submenu row keeps today's "next top-level menu".
  - State: `MenuBarNavigationState` gains `openSubmenuItem: Int?` (index in the open menu) and
    `highlightedSubItem: Int?`; all transitions stay pure and unit-tested.
  - Disabled submenu rows are not selectable and do not open.

## Testing

- `AppMenuDslTest`: submenu builds into `MenuEntry.Submenu` with its entries in order.
- `MenuKeyboardShortcutsTest`: a shortcut inside an enabled submenu is found; inside a disabled submenu it is not.
- `MenuBarNavigationStateTest`: → / Enter opens a highlighted submenu with its first selectable item; ↑/↓ wrap inside
  the submenu; ← and Esc close only the submenu; Enter on a submenu item returns its onClick and closes; → on a plain
  item still moves to the next top-level menu; hover on another parent row closes the submenu; disabled submenu
  never opens.
- Manual (owed to the user): macOS native submenu and Windows custom-bar submenu look and keyboard behaviour.
