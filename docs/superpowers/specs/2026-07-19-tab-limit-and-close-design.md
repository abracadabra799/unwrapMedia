# Tab Limit and Close Button — Design

## Background

The app currently lets `AppState.openFile` add an unbounded number of file tabs, with no way to close one short of quitting the app. The user wants two related, small behavior changes: cap the number of simultaneously open files at 2, and let the user close an open file tab via a UI control.

## Goal

1. `AppState.openFile` refuses to open a third distinct file while 2 are already open, showing an inline message instead of silently doing nothing or evicting an existing tab.
2. Each file tab gets a close control. Closing a tab removes it from `AppState.tabs` and correctly re-derives `selectedTabIndex` so the UI doesn't end up pointing at a stale or out-of-range tab.

## Non-Goals

- No "unsaved changes" confirmation on close — this app is read-only (a file viewer), so there's nothing to lose by closing a tab.
- No keyboard shortcut (e.g. Cmd+W) for closing the current tab — mouse/click only, matching the rest of this app's interaction model (no other keyboard shortcuts exist today).
- No configurable tab limit — 2 is a fixed constant, not a setting.
- No auto-dismiss timer on the rejection message — it's simply replaced or cleared the next time `openFile` runs, not a toast that disappears after N seconds.

## Design

### 1. Two-file limit (`AppState.openFile`)

`AppState` gains a new field: `var openError: String? by mutableStateOf(null)`.

`openFile`'s existing "already open → switch to it" branch is unchanged. Before creating a new `TabState`, add a check: if `tabs.size >= 2` (and this is a genuinely new file, not a re-open of an already-open one), set `openError = "You can only have 2 files open at a time."` and return without adding a tab. On every call to `openFile` — whether it succeeds, fails to parse, or gets rejected by the limit — `openError` is reset first (cleared on success/parse-failure, (re)set on rejection), so a stale rejection message never lingers after the user successfully opens or closes a tab and tries again.

### 2. Closing a tab (`AppState.closeTab(index: Int)`, new)

Removes `tabs[index]`. Re-derives `selectedTabIndex`:
- If `index == selectedTabIndex` (closing the currently-viewed tab): select another remaining tab — `index.coerceAtMost(tabs.size - 1)` after removal (so if the closed tab wasn't last, the tab that slides into its old position is selected; if it was last, the new last tab is selected). If `tabs` is now empty, `selectedTabIndex` is set to `0` (harmless — `Main.kt` already guards all tab rendering behind `if (appState.tabs.isNotEmpty())`).
- If `index < selectedTabIndex` (closing a tab before the selected one): decrement `selectedTabIndex` by 1, so it keeps pointing at the same `TabState` object, which shifted down one position.
- If `index > selectedTabIndex`: no change.

### 3. UI (`Main.kt`)

The existing per-file `TabRow`'s `Tab { text = { Text(tab.file.name) } }` becomes `Tab { text = { Row { Text(tab.file.name); <close control> } } }` — a small clickable close affordance (e.g. an "✕" `Text` or `IconButton`) placed after the filename inside the tab's content, calling `appState.closeTab(index)` on click. Because it's a distinct clickable element nested inside the `Tab`'s content (not the `Tab`'s own `onClick`), clicking it consumes the click before it reaches the `Tab`'s own tab-selection handler — standard Compose click-consumption behavior, no extra plumbing needed.

Below (or beside) the existing "Open File" `Button`, a `Text(appState.openError)` renders only `if (appState.openError != null)`, showing the rejection message inline.

## Testing

This is UI-adjacent state logic (`AppState`/`TabState` are plain Compose state holders, not pure functions operating on immutable data like `MediaSummaryBuilder`), so — matching this project's established pattern for `AppState`-level changes — verification is manual: open 2 files, confirm a 3rd is rejected with the message shown, confirm the message clears after closing a tab and successfully opening a new one, confirm closing the selected tab (first, middle, last position) and a non-selected tab both leave `selectedTabIndex` pointing at a sensible remaining tab, and confirm closing the last remaining tab returns to the empty "Open File only" state.
