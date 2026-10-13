package com.multiviewer.ui.menu

import androidx.compose.ui.input.key.Key

/**
 * Whole interaction model of the custom (Windows/Linux) menu bar, modelled on VS Code's custom title
 * bar menus: which menu is open, which header has keyboard focus (Alt), which item is highlighted, and
 * whether mnemonics are underlined. Every transition is pure; [handleMenuKey] maps keys onto them.
 */
data class MenuBarNavigationState(
    val openMenuIndex: Int? = null,
    val focusedIndex: Int? = null,
    val highlightedItem: Int? = null,
    val mnemonicsVisible: Boolean = false,
    private val switchedByHover: Boolean = false,
    private val altArmed: Boolean = false,
) {
    /** A menu is open or the bar has keyboard focus: keys belong to the menu bar. */
    val isActive: Boolean get() = openMenuIndex != null || focusedIndex != null

    /** Header click: open it, or close it if it is the open one (unless hover just opened it). */
    fun activate(index: Int, menuCount: Int): MenuBarNavigationState {
        if (index !in 0 until menuCount) return this
        if (openMenuIndex != index) return open(index, highlight = null)
        return if (switchedByHover) copy(switchedByHover = false) else close()
    }

    /** Pointer over a header: switches menus only while one is already open (no click needed). */
    fun hover(index: Int, menuCount: Int): MenuBarNavigationState =
        if (openMenuIndex != null && index in 0 until menuCount && openMenuIndex != index) {
            open(index, highlight = null).copy(switchedByHover = true)
        } else {
            this
        }

    fun hoverItem(item: Int, menus: List<AppMenu>): MenuBarNavigationState {
        val menu = openMenuIndex?.let(menus::getOrNull) ?: return this
        return if (menu.entries.getOrNull(item)?.isSelectable() == true) copy(highlightedItem = item) else this
    }

    /** ←/→: with a menu open, open the neighbour (first item highlighted); with bar focus, move it. */
    fun moveHeader(delta: Int, menus: List<AppMenu>): MenuBarNavigationState {
        if (menus.isEmpty()) return close()
        openMenuIndex?.let { return open((it + delta).mod(menus.size), menus.firstSelectable((it + delta).mod(menus.size))) }
        focusedIndex?.let { return copy(focusedIndex = (it + delta).mod(menus.size)) }
        return this
    }

    /** ↑/↓ over selectable items (wrapping); ↓ on a focused header opens it. */
    fun moveItem(delta: Int, menus: List<AppMenu>): MenuBarNavigationState {
        val index = openMenuIndex
        if (index == null) return if (delta > 0) openFocused(menus) else this
        val entries = menus.getOrNull(index)?.entries ?: return this
        val selectable = entries.indices.filter { entries[it].isSelectable() }
        if (selectable.isEmpty()) return this
        val current = highlightedItem?.let(selectable::indexOf)?.takeIf { it >= 0 }
        val next = when {
            current != null -> selectable[(current + delta).mod(selectable.size)]
            delta > 0 -> selectable.first()
            else -> selectable.last()
        }
        return copy(highlightedItem = next)
    }

    fun highlightEdge(first: Boolean, menus: List<AppMenu>): MenuBarNavigationState {
        val entries = openMenuIndex?.let(menus::getOrNull)?.entries ?: return this
        val selectable = entries.indices.filter { entries[it].isSelectable() }
        return if (selectable.isEmpty()) this else copy(highlightedItem = if (first) selectable.first() else selectable.last())
    }

    fun openFocused(menus: List<AppMenu>): MenuBarNavigationState {
        val index = focusedIndex ?: return this
        return open(index, menus.firstSelectable(index))
    }

    fun openByMnemonic(letter: Char, menus: List<AppMenu>): MenuBarNavigationState? {
        val index = menus.indexOfFirst { it.mnemonic?.equals(letter, ignoreCase = true) == true }
        if (index < 0) return null
        return open(index, menus.firstSelectable(index)).copy(mnemonicsVisible = true)
    }

    /** The highlighted entry's action (checkbox: toggle), or null. */
    fun highlightedAction(menus: List<AppMenu>): (() -> Unit)? {
        val entry = openMenuIndex?.let(menus::getOrNull)?.entries?.getOrNull(highlightedItem ?: return null) ?: return null
        return when (entry) {
            is MenuEntry.Item -> entry.onClick.takeIf { entry.enabled }
            is MenuEntry.Checkbox -> ({ entry.onCheckedChange(!entry.checked) })
            MenuEntry.Separator -> null
        }
    }

    internal fun altDown(): MenuBarNavigationState = copy(altArmed = true)
    internal fun otherKeyDown(): MenuBarNavigationState = if (altArmed) copy(altArmed = false) else this

    /** Alt released with nothing pressed in between: toggle keyboard focus on the bar. */
    internal fun altUp(menuCount: Int): MenuBarNavigationState = when {
        !altArmed -> this
        isActive -> close()
        menuCount > 0 -> copy(focusedIndex = 0, mnemonicsVisible = true, altArmed = false)
        else -> copy(altArmed = false)
    }

    fun close(): MenuBarNavigationState = MenuBarNavigationState()

    private fun open(index: Int, highlight: Int?) = copy(
        openMenuIndex = index,
        focusedIndex = index,
        highlightedItem = highlight,
        switchedByHover = false,
        altArmed = false,
    )
}

fun MenuEntry.isSelectable(): Boolean = when (this) {
    is MenuEntry.Item -> enabled
    is MenuEntry.Checkbox -> true
    MenuEntry.Separator -> false
}

private fun List<AppMenu>.firstSelectable(menuIndex: Int): Int? =
    getOrNull(menuIndex)?.entries?.indexOfFirst { it.isSelectable() }?.takeIf { it >= 0 }

/** Result of offering one key event to the menu bar. */
data class MenuKeyResult(val state: MenuBarNavigationState, val consumed: Boolean, val action: (() -> Unit)? = null)

private val LETTER_KEYS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)

private fun Key.letter(): Char? = LETTER_KEYS.indexOf(this).takeIf { it >= 0 }?.let { 'A' + it }

/**
 * Maps one key event onto the menu bar (meant for the window's onPreviewKeyEvent, so focused content
 * can't swallow arrows while a menu is active). Returns `consumed = false` for anything the menu bar
 * doesn't own; Ctrl shortcuts close an active menu and pass through.
 */
fun handleMenuKey(
    state: MenuBarNavigationState,
    key: Key,
    keyDown: Boolean,
    alt: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    menus: List<AppMenu>,
): MenuKeyResult {
    val isAlt = key == Key.AltLeft || key == Key.AltRight
    if (isAlt) {
        return if (keyDown) {
            MenuKeyResult(state.altDown(), consumed = false)
        } else {
            val next = state.altUp(menus.size)
            MenuKeyResult(next, consumed = next != state)
        }
    }
    if (!keyDown) return MenuKeyResult(state, consumed = state.isActive)

    val s = state.otherKeyDown()
    if (alt && !ctrl) {
        key.letter()?.let { letter -> s.openByMnemonic(letter, menus)?.let { return MenuKeyResult(it, consumed = true) } }
        return MenuKeyResult(s, consumed = false)
    }
    if (!s.isActive) return MenuKeyResult(s, consumed = false)
    if (ctrl) return MenuKeyResult(s.close(), consumed = false)

    return when (key) {
        Key.DirectionLeft -> MenuKeyResult(s.moveHeader(-1, menus), true)
        Key.DirectionRight -> MenuKeyResult(s.moveHeader(1, menus), true)
        Key.DirectionUp -> MenuKeyResult(s.moveItem(-1, menus), true)
        Key.DirectionDown -> MenuKeyResult(s.moveItem(1, menus), true)
        Key.MoveHome -> MenuKeyResult(s.highlightEdge(first = true, menus), true)
        Key.MoveEnd -> MenuKeyResult(s.highlightEdge(first = false, menus), true)
        Key.Escape -> MenuKeyResult(s.close(), true)
        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            if (s.openMenuIndex == null) {
                MenuKeyResult(s.openFocused(menus), true)
            } else {
                val action = s.highlightedAction(menus)
                MenuKeyResult(if (action != null) s.close() else s, true, action)
            }
        }
        else -> {
            val letter = if (!shift) key.letter() else null
            val byMnemonic = if (s.openMenuIndex == null && letter != null) s.openByMnemonic(letter, menus) else null
            MenuKeyResult(byMnemonic ?: s, consumed = true) // a menu swallows typing, like VS Code
        }
    }
}

/** A header label with the index of the character to underline when mnemonics are visible. */
data class MnemonicLabel(val text: String, val underlineIndex: Int?)

/** "File" underlines its F; a label without the letter (Korean) gets VS Code's "파일(F)" suffix. */
fun mnemonicLabel(label: String, mnemonic: Char?): MnemonicLabel {
    if (mnemonic == null) return MnemonicLabel(label, null)
    val inLabel = label.indexOfFirst { it.equals(mnemonic, ignoreCase = true) }
    if (inLabel >= 0) return MnemonicLabel(label, inLabel)
    val upper = mnemonic.uppercaseChar()
    return MnemonicLabel("$label($upper)", label.length + 1)
}
