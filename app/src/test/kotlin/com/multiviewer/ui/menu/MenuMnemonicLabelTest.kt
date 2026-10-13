package com.multiviewer.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals

class MenuMnemonicLabelTest {
    @Test
    fun `english label underlines its own letter`() {
        assertEquals(MnemonicLabel("File", 0), mnemonicLabel("File", 'F'))
        assertEquals(MnemonicLabel("Motion Photo", 0), mnemonicLabel("Motion Photo", 'M'))
        assertEquals(MnemonicLabel("Gain Map", 0), mnemonicLabel("Gain Map", 'g'))
    }

    @Test
    fun `korean label gets the VS Code style suffix`() {
        assertEquals(MnemonicLabel("파일(F)", 3), mnemonicLabel("파일", 'F'))
    }

    @Test
    fun `no mnemonic leaves the label alone`() {
        assertEquals(MnemonicLabel("Tools", null), mnemonicLabel("Tools", null))
    }
}
