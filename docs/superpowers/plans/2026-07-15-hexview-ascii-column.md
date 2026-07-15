# HexView ASCII Column Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ASCII representation column next to the hex bytes in `HexView`, matching the classic hex-editor layout (010 Editor style: offset | hex | ASCII), with the same tree-selection highlight applied to both hex and ASCII.

**Architecture:** Single-file change to `HexView.kt`. The hex-byte loop is changed to always iterate over all 16 byte slots (padding missing ones with spaces on the file's final, partial row) so the ASCII column lines up on every row; a second loop over the same already-read row buffer renders each byte's ASCII character (or `.` for non-printable), reusing the exact same highlight condition as the hex loop.

**Tech Stack:** Kotlin, Compose Multiplatform (Desktop) — same as the rest of the UI layer.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-15-hexview-ascii-column-design.md`
- Only file touched: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`
- Printable range: `0x20`–`0x7E` (inclusive) renders as the literal character; everything else renders as `.`
- No new tests — this project's UI layer has no automated tests by design (parser is unit-tested; UI is verified by manual/process-level checks). Verify by compiling and running.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Add ASCII column to HexView, with aligned padding on the final row

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`

**Interfaces:**
- Consumes: nothing new — same `HexView(file: File, highlightRange: LongRange?, listState: LazyListState)` signature, same `RandomAccessFile`-based row reading already in place.
- Produces: nothing new for other tasks — this is a self-contained visual change.

- [ ] **Step 1: Replace the row-rendering `Text(...)` block**

Replace the entire body of the `items(rowCount) { rowIndex -> ... }` lambda's `Text(buildAnnotatedString { ... })` call with:

```kotlin
            Text(buildAnnotatedString {
                append("%08X  ".format(rowStart))
                for (i in 0 until BYTES_PER_ROW) {
                    if (i < buf.size) {
                        val byteOffset = rowStart + i
                        val isHighlighted = highlightRange?.contains(byteOffset) == true
                        val hex = "%02X ".format(buf[i])
                        if (isHighlighted) {
                            withStyle(SpanStyle(background = Color.Yellow)) { append(hex) }
                        } else {
                            append(hex)
                        }
                    } else {
                        append("   ")
                    }
                }
                append(" ")
                for (i in buf.indices) {
                    val byteOffset = rowStart + i
                    val isHighlighted = highlightRange?.contains(byteOffset) == true
                    val byteValue = buf[i].toInt() and 0xFF
                    val char = if (byteValue in 0x20..0x7E) byteValue.toChar() else '.'
                    if (isHighlighted) {
                        withStyle(SpanStyle(background = Color.Yellow)) { append(char) }
                    } else {
                        append(char)
                    }
                }
            })
```

The full file after this change should read:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import java.io.File
import java.io.RandomAccessFile

private const val BYTES_PER_ROW = 16

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val rowCount = ((raf.length() + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()

    LazyColumn(state = listState) {
        items(rowCount) { rowIndex ->
            val rowStart = rowIndex.toLong() * BYTES_PER_ROW
            val rowLength = minOf(BYTES_PER_ROW.toLong(), raf.length() - rowStart).toInt()
            val buf = ByteArray(rowLength)
            raf.seek(rowStart)
            raf.readFully(buf)

            Text(buildAnnotatedString {
                append("%08X  ".format(rowStart))
                for (i in 0 until BYTES_PER_ROW) {
                    if (i < buf.size) {
                        val byteOffset = rowStart + i
                        val isHighlighted = highlightRange?.contains(byteOffset) == true
                        val hex = "%02X ".format(buf[i])
                        if (isHighlighted) {
                            withStyle(SpanStyle(background = Color.Yellow)) { append(hex) }
                        } else {
                            append(hex)
                        }
                    } else {
                        append("   ")
                    }
                }
                append(" ")
                for (i in buf.indices) {
                    val byteOffset = rowStart + i
                    val isHighlighted = highlightRange?.contains(byteOffset) == true
                    val byteValue = buf[i].toInt() and 0xFF
                    val char = if (byteValue in 0x20..0x7E) byteValue.toChar() else '.'
                    if (isHighlighted) {
                        withStyle(SpanStyle(background = Color.Yellow)) { append(char) }
                    } else {
                        append(char)
                    }
                }
            })
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite (confirm no regression)**

Run:
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures (this change touches no parser code and no test files).

- [ ] **Step 4: Manual verification**

Run:
```bash
./gradlew run
```
Open a real `.mov`/`.heic` file (button or drag-and-drop) and confirm:
- An ASCII column appears to the right of the hex bytes on every row.
- The ASCII column starts at the same horizontal position on every row, including the file's last (possibly partial) row.
- Printable bytes (e.g. the `ftyp` box's brand fourcc, `hdlr` box's handler name string) show as readable characters in the ASCII column.
- Non-printable bytes show as `.`.
- Clicking a tree node highlights the corresponding bytes in yellow in BOTH the hex and ASCII columns, not just the hex side.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HexView.kt
git commit -m "feat(ui): add ASCII column to hex view, aligned across all rows"
```
