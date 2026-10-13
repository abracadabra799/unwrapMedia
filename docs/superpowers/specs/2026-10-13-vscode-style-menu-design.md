# VS Code–style custom menu bar (Windows/Linux) — design

Date: 2026-10-13. User request (Korean): "VS Code처럼 상위 메뉴를 선택하면 이후 좌우로 이동해서 상위 메뉴로 포커스가
가면 저절로 하위 메뉴가 펼쳐지게, 전체적으로 다 VS Code처럼". macOS keeps the native NSMenu bar (unchanged).

## Why the current bar can't do it
`CustomAppMenuBar` opens each menu with Material3 `DropdownMenu`, a focusable popup window: while it is open
the main window's `onKeyEvent` never sees ←/→, and switching menus recreates the popup with an animation.

## Behaviour (mirrors VS Code's custom title bar menus)
- Mouse: click a header to open / click again to close; while a menu is open, hovering another header
  switches instantly; hovering an item highlights it; clicking an enabled item runs it and closes; clicking
  anywhere outside closes the menu and the click is consumed.
- Keyboard while a menu is open: ←/→ open the neighbouring menu with its first item highlighted (wrap);
  ↑/↓ move the highlight over selectable items (enabled items and checkboxes; separators and disabled items
  are skipped; wraps); Home/End; Enter/Space run the highlighted item; Esc closes.
- Alt pressed and released alone toggles menu-bar focus: first header focused, mnemonics underlined; ←/→ move
  the focus, ↓/Enter/Space open the focused menu. Alt+mnemonic opens that menu directly.
- Mnemonics: 파일(F) 추출(E) 게인맵(G) 분석(A) 모션포토(M) 도구(T) 보기(V) 도움말(H). A label that contains the
  letter (English) underlines it; otherwise "(X)" is appended (VS Code's Korean convention).

## Implementation
- `MenuBarNavigationState` becomes the whole model (open menu, focused header, highlighted item, mnemonic
  visibility, Alt arming) with pure transitions; `handleMenuKey(...)` maps a key event to a transition and an
  optional action. Both unit-tested.
- Rendering: headers report their bounds; the dropdown is drawn by `CustomMenuOverlay` in a root `Box` above
  the window content (no popup window), with a scrim below the bar. Keys go through the window's
  `onPreviewKeyEvent` so focused content (hex view, text fields) can't swallow them while a menu is active.
- Look: VS Code Dark Modern — header pill hover (white 10%), dropdown with 5dp corners, 1dp border, shadow,
  4dp vertical padding, 26dp rows, check gutter, right-aligned dim shortcuts, selection #0078D4/white.

## Verification (macOS, forced custom bar)
`UNWRAP_CUSTOM_MENU_BAR=1 ./gradlew :app:run` draws the Windows/Linux bar on macOS for development. Checked with
real mouse/keyboard events (cliclick + System Events): click opens; →/← open neighbours with the first item
highlighted; smooth pointer movement over another header switches menus without a click; Alt alone focuses
the bar with underlined mnemonics; →→↓ opens 게인맵; Enter ran "게인맵 분석기"; Alt+G opens 게인맵 directly; Esc
closes; a click outside closes and does not reach the tab underneath.
Found while testing: Compose re-sends header Enter events during relayout under a still pointer, which made
menus hop on their own -- hover switching now requires the pointer to have actually moved.
