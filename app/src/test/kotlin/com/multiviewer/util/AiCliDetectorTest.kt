package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AiCliDetectorTest {

    @Test
    fun windowsLookupPrefersRunnableExtensionOverBareScript(@TempDir dir: Path) {
        val bare = File(dir.toFile(), "claude").apply { writeText("#!/bin/sh") }
        val cmd = File(dir.toFile(), "claude.cmd").apply { writeText("@echo off") }
        val output = "${bare.absolutePath}\r\n${cmd.absolutePath}\r\n"

        assertEquals(cmd.absolutePath, AiCliDetector.pickFromLookupOutput(output, windows = true))
    }

    @Test
    fun windowsLookupReturnsNullWhenOnlyBareScriptExists(@TempDir dir: Path) {
        val bare = File(dir.toFile(), "claude").apply { writeText("#!/bin/sh") }

        assertNull(AiCliDetector.pickFromLookupOutput("${bare.absolutePath}\n", windows = true))
    }

    @Test
    fun nonWindowsLookupTakesFirstExistingLine(@TempDir dir: Path) {
        val bin = File(dir.toFile(), "claude").apply { writeText("#!/bin/sh") }

        assertEquals(bin.absolutePath, AiCliDetector.pickFromLookupOutput("${bin.absolutePath}\n", windows = false))
    }

    @Test
    fun lookupIgnoresNonExistentAndBlankLines() {
        val output = "\n   \nC:\\nope\\claude.cmd\n"
        assertNull(AiCliDetector.pickFromLookupOutput(output, windows = true))
        assertNull(AiCliDetector.pickFromLookupOutput("", windows = false))
    }

    @Test
    fun findBinaryReturnsNullForAToolThatDoesNotExist() {
        // exercises the full PATH scan + where/which fallback end to end
        assertNull(AiCliDetector.findBinary("definitely-not-a-real-cli-zzz-x9"))
    }

    @Test
    fun cliButtonOrderPutsAgyBeforeGemini() {
        // Enterprise accounts can't use agy yet -> gemini is the fallback and both
        // need a launch button; agy is listed first.
        val order = AiCliType.entries.map { it.name }
        assertTrue(order.indexOf("AGY") < order.indexOf("GEMINI"), "order was $order")
        assertEquals(listOf("CLAUDE", "CODEX", "AGY", "GEMINI"), order)
    }
}
