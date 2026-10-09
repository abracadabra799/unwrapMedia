package com.multiviewer.ui.menu

/** State transitions shared by the custom menu bar's pointer and keyboard navigation. */
data class MenuBarNavigationState(
    val openMenuIndex: Int? = null,
    private val switchedByHover: Boolean = false,
) {
    fun activate(index: Int, menuCount: Int): MenuBarNavigationState {
        if (index !in 0 until menuCount) return this
        if (openMenuIndex != index) return copy(openMenuIndex = index, switchedByHover = false)
        return if (switchedByHover) copy(switchedByHover = false) else close()
    }

    fun hover(index: Int, menuCount: Int): MenuBarNavigationState =
        if (openMenuIndex != null && index in 0 until menuCount && openMenuIndex != index) {
            copy(openMenuIndex = index, switchedByHover = true)
        } else this

    fun move(delta: Int, menuCount: Int): MenuBarNavigationState {
        if (menuCount <= 0 || delta == 0) return close()
        val current = openMenuIndex ?: if (delta > 0) -1 else 0
        return copy(openMenuIndex = (current + delta).mod(menuCount), switchedByHover = false)
    }

    fun close(): MenuBarNavigationState = copy(openMenuIndex = null)
}
