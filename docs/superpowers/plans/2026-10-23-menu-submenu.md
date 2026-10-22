# Menu Submenu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One-level submenus in the shared menu DSL (native macOS + custom Windows/Linux bar), used to group the
three motion-photo creation items under "모션포토 생성 ▸".

**Architecture:** `MenuEntry.Submenu` in `AppMenuDsl.kt`; native renderer nests `Menu`; shortcut flattening
recurses; `MenuBarNavigationState` gets pure submenu transitions; `CustomMenuBar.kt` renders a child popup.

Spec: `docs/superpowers/specs/2026-10-23-menu-submenu-design.md`.

## Global Constraints

- One nesting level only. Disabled submenu: not selectable, never opens, its shortcuts do not fire.
- Keyboard (custom bar): →/Enter open a highlighted submenu (first selectable child highlighted); ↑/↓ wrap inside it; ←/Esc close only the submenu; Enter on a child runs it and closes the bar; → on a plain item keeps moving to the next top-level menu.
- Labels via `I18n`, bilingual: "모션포토 생성" / "Create Motion Photo"; "구글 스펙 준수 (권장)" / "Google spec (recommended)"; "삼성 모션포토 (SEF 포함)" / "Samsung motion photo (with SEF)"; "MicroVideo v1 (레거시)" / "MicroVideo v1 (legacy)".
- Material3 only (`androidx.compose.material3.Text`); squared UI style (no pill shapes) consistent with existing menu popup.
- Work in `.worktrees/google-motion-photo-spec`, branch `feat/google-motion-photo-spec`; check `git branch --show-current` before each commit; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 7: Submenus and the "모션포토 생성 ▸" submenu

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/menu/AppMenuDsl.kt`, `NativeMenuBarRenderer.kt`, `MenuKeyboardShortcuts.kt`, `MenuBarNavigationState.kt`, `CustomMenuBar.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (Tools menu motion-photo creation items → one `submenu`), `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` (labels)
- Test: `app/src/test/kotlin/com/multiviewer/ui/menu/AppMenuDslTest.kt`, `MenuKeyboardShortcutsTest.kt`, `MenuBarNavigationStateTest.kt`

**Interfaces:**
- Produces: `MenuEntry.Submenu(label: String, enabled: Boolean, entries: List<MenuEntry>)`; `AppMenuBuilder.submenu(label: String, enabled: Boolean = true, block: AppSubmenuBuilder.() -> Unit)` where `AppSubmenuBuilder` offers `item`, `checkbox`, `separator` (no `submenu`); `MenuBarNavigationState.openSubmenuItem: Int?`, `highlightedSubItem: Int?`.

- [ ] **Step 1: Failing tests** — add to the three test files exactly the cases listed in the spec's Testing section (DSL build; shortcut found in enabled submenu / not in disabled; navigation: →/Enter open with first selectable child, ↑/↓ wrap, ←/Esc close only the submenu, Enter on child returns its onClick and closes, → on plain item moves top-level, hovering another parent row closes the submenu, disabled submenu never opens). Read the existing tests first and follow their style and helpers. Run `./gradlew :app:test --tests 'com.multiviewer.ui.menu.*'` → RED (compile errors), record output.
- [ ] **Step 2: Implement** DSL + native renderer + shortcut recursion + navigation state (pure), then `CustomMenuBar.kt` rendering: Submenu row with trailing `▸`, child popup to the right of the row, hover open/close, keyboard handled through the state. Keep existing behaviour for menus without submenus unchanged.
- [ ] **Step 3: Main.kt** — replace the three flat creation items (Google spec / Samsung compatible / MicroVideo v1) with one `submenu(I18n.menuCreateMotionPhoto(language), enabled = isImage) { … }` containing the three items with the new labels; the MicroVideo item keeps `enabled = isImage && !isHeic`. Remove now-unused I18n functions only if nothing else uses them.
- [ ] **Step 4: GREEN** — `./gradlew :app:test --tests 'com.multiviewer.ui.menu.*'`, then `./gradlew :app:test` (ThumbnailDiskCacheTest flaky) and `./gradlew :app:compileKotlin`.
- [ ] **Step 5: Commit** — `feat: add menu submenus and group motion photo creation`.
