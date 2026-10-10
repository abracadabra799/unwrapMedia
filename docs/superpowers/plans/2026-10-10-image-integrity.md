# Image Integrity Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Image Integrity…" check (structure verification with exact offsets + FFmpeg/Skia decode verification) for every supported image format, as a GUI window and in `unwrapMedia check`.

**Architecture:** UI-independent per-format checkers live in `com.multiviewer.parser.integrity` and return `IntegrityCheckItem`s (status + evidence + offset). JPEG and HEIF checkers read the existing `parseFile` `BoxNode` tree; PNG/GIF/BMP/WebP/TIFF re-walk raw bytes through `ByteReader` (they need raw CRCs, little-endian sizes and IFD offsets the tree only exposes as display strings). Decode verification (`com.multiviewer.ui.ImageDecodeCheck`) runs FFmpeg with `-f framecrc` (frame count + decoded size) and Skia as a second, stricter decoder. A Compose window and the CLI render the two verdicts side by side.

**Tech Stack:** Kotlin/JVM, Compose Desktop (Material3), skiko `org.jetbrains.skia.Codec`, FFmpeg via `ProcessBuilder`, kotlin.test + JUnit5.

## Global Constraints

- Work ONLY in the worktree `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/image-integrity` on branch `feature/image-integrity`. Before every commit run `git -C /Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/image-integrity branch --show-current` and confirm it prints `feature/image-integrity`. Never `cd` into or commit in `/Users/dong.kim/AndroidStudioProjects/multiViewer` (the main checkout holds someone else's uncommitted Video Integrity work — do not touch it).
- Run Gradle from the worktree root: `./gradlew :app:test --tests '<pattern>' -q`.
- Do NOT create or modify any of: `VideoIntegrity.kt`, `VideoIntegrityWindow.kt`, `BitstreamCorruption*`. Do not reuse names already used by the Video Integrity work in package `com.multiviewer.ui`: `IntegrityStatus`, `IntegrityDiagnostic`, `IntegrityPacket`, `integrityProcess`, `VideoIntegrityReport`, `inspectVideoIntegrity`, `decodeIntegrityStatus`.
- UI text: always import `androidx.compose.material3.Text` (Material2 `Text` renders invisible on this app's theme).
- Check item `id`s are stable strings exactly as written in this plan (tests and JSON depend on them).
- Decode CLEAN requires exit code 0 AND no error output AND ≥ 1 decoded frame.
- Per-process decode timeout: 2 minutes. Log cap: 500 lines (record truncation).
- Commit messages end with: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

---

## File Structure

```
app/src/main/kotlin/com/multiviewer/parser/integrity/
  ImageIntegrityModels.kt   CheckStatus, IntegrityCheckItem, ImageStructureReport, FormatCheckResult
  IntegrityBytes.kt         LE/BE byte helpers, streaming CRC32
  ImageIntegrityChecker.kt  format detection + dispatch + parser-warning items
  JpegIntegrity.kt  PngIntegrity.kt  GifIntegrity.kt  BmpIntegrity.kt
  WebpIntegrity.kt  HeifIntegrity.kt  TiffIntegrity.kt
app/src/main/kotlin/com/multiviewer/ui/
  ImageDecodeCheck.kt       FFmpeg framecrc + Skia decode, status rules, process runner
  ImageIntegrityWindow.kt   Compose window
app/src/main/kotlin/com/multiviewer/cli/
  ImageIntegrityJson.kt     JSON rendering + analysis-case JSON
  CheckFile.kt, CheckCommand.kt (modify)
app/src/main/kotlin/com/multiviewer/Main.kt, ui/I18n.kt (modify), README.md (modify)
app/src/test/kotlin/com/multiviewer/parser/integrity/
  IntegrityTestFixtures.kt  synthetic file builders shared by all integrity tests
  <Format>IntegrityTest.kt
app/src/test/kotlin/com/multiviewer/ui/ImageDecodeCheckTest.kt
```

---

### Task 1: Models, dispatcher, parser-warning items, JPEG checker

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/ImageIntegrityModels.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/IntegrityBytes.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/ImageIntegrityChecker.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/JpegIntegrity.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/integrity/IntegrityTestFixtures.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/JpegIntegrityTest.kt`

**Interfaces:**
- Consumes: `parseFile(File): BoxNode`, `collectWarnings(BoxNode): List<WarningEntry>` (fields `node`, `warning`), `ByteReader.open/readBytes/readUInt8/readUInt16/readUInt32/readFourCC/length`.
- Produces:
  - `enum class CheckStatus { PASS, INFO, WARN, FAIL, SKIP }`
  - `data class IntegrityCheckItem(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null)`
  - `data class ImageStructureReport(format: String, items: List<IntegrityCheckItem>, declaredWidth: Int? = null, declaredHeight: Int? = null)` with `val overall: CheckStatus`
  - `data class FormatCheckResult(items: List<IntegrityCheckItem>, declaredWidth: Int? = null, declaredHeight: Int? = null)`
  - `object ImageIntegrityChecker { fun check(file: File, root: BoxNode): ImageStructureReport; fun check(root: BoxNode, reader: ByteReader): ImageStructureReport }`
  - `fun detectImageFormat(reader: ByteReader): String` → one of `JPEG PNG GIF BMP TIFF WEBP HEIF UNKNOWN`
  - helpers in `IntegrityBytes.kt`: `ByteArray.u8/u16le/u24le/u32le/u16be/u32be(i: Int)`, `crc32Of(reader, offset, length): Long`, `hex32(Long): String`
  - test fixtures: `checkBytes(bytes, ext): ImageStructureReport`, `ImageStructureReport.item(id)`, `be16/be32/le16/le24/le32`, `jpegBytes(...)`

- [ ] **Step 1: Write the models and byte helpers** (no behaviour to test yet)

`ImageIntegrityModels.kt`:
```kotlin
package com.multiviewer.parser.integrity

enum class CheckStatus { PASS, INFO, WARN, FAIL, SKIP }

data class IntegrityCheckItem(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val offset: Long? = null,
    val length: Long? = null,
)

data class ImageStructureReport(
    val format: String,
    val items: List<IntegrityCheckItem>,
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
) {
    /** Worst non-SKIP status; INFO counts as PASS (recognized, legitimate extra data). */
    val overall: CheckStatus
        get() = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
}

/** A per-format checker's result: its items plus the image size its headers declare (null when unknown). */
data class FormatCheckResult(
    val items: List<IntegrityCheckItem>,
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
)
```

`IntegrityBytes.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.util.zip.CRC32

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
internal fun ByteArray.u32le(i: Int): Long = u16le(i).toLong() or (u16le(i + 2).toLong() shl 16)
internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()

/** Streams [length] bytes starting at [offset] through CRC32 in 64 KiB blocks (never loads the whole file). */
internal fun crc32Of(reader: ByteReader, offset: Long, length: Long): Long {
    val crc = CRC32()
    var pos = offset
    val end = offset + length
    while (pos < end) {
        val n = minOf(65536L, end - pos).toInt()
        crc.update(reader.readBytes(pos, n))
        pos += n
    }
    return crc.value
}

internal fun hex32(v: Long): String = "0x" + v.toString(16).padStart(8, '0')
```

- [ ] **Step 2: Write test fixtures and the failing JPEG tests**

`IntegrityTestFixtures.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.parseFile
import java.io.ByteArrayOutputStream
import java.io.File

internal fun checkBytes(bytes: ByteArray, ext: String): ImageStructureReport {
    val f = File.createTempFile("integrity-", ".$ext")
    f.deleteOnExit()
    f.writeBytes(bytes)
    return ImageIntegrityChecker.check(f, parseFile(f))
}

internal fun ImageStructureReport.item(id: String): IntegrityCheckItem =
    items.firstOrNull { it.id == id } ?: error("No item '$id' in ${items.map { it.id }}")

internal fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
internal fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
internal fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
internal fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

/** Minimal baseline JPEG (3x2, 1 component). Not pixel-decodable; structurally complete. */
internal fun jpegBytes(
    withEoi: Boolean = true,
    withDht: Boolean = true,
    sofMarker: Int = 0xC0,
    trailing: ByteArray = ByteArray(0),
): ByteArray {
    val out = ByteArrayOutputStream()
    fun seg(marker: Int, payload: ByteArray) {
        val l = payload.size + 2
        out.write(0xFF); out.write(marker); out.write(l shr 8); out.write(l and 0xFF); out.write(payload)
    }
    out.write(0xFF); out.write(0xD8)
    seg(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    seg(sofMarker, byteArrayOf(8, 0, 2, 0, 3, 1, 1, 0x11, 0))
    if (withDht) seg(0xC4, byteArrayOf(0x00, 1) + ByteArray(15) + byteArrayOf(0))
    seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
    out.write(byteArrayOf(0x12, 0x34, 0x56))
    if (withEoi) { out.write(0xFF); out.write(0xD9) }
    out.write(trailing)
    return out.toByteArray()
}
```

`JpegIntegrityTest.kt`:
```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class JpegIntegrityTest {
    @Test
    fun `valid jpeg passes every check and declares its size`() {
        val r = checkBytes(jpegBytes(), "jpg")
        assertEquals("JPEG", r.format)
        listOf("jpeg.soi", "jpeg.frame", "jpeg.dqt", "jpeg.dht", "jpeg.eoi", "jpeg.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
        assertEquals(3, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `missing EOI fails`() {
        val r = checkBytes(jpegBytes(withEoi = false), "jpg")
        assertEquals(CheckStatus.FAIL, r.item("jpeg.eoi").status)
        assertEquals(CheckStatus.FAIL, r.overall)
    }

    @Test
    fun `unknown bytes after EOI warn at the first unknown offset`() {
        val base = jpegBytes()
        val r = checkBytes(jpegBytes(trailing = byteArrayOf(0x00, 0x11, 0x22)), "jpg")
        val item = r.item("jpeg.trailing")
        assertEquals(CheckStatus.WARN, item.status)
        assertEquals(base.size.toLong(), item.offset)
    }

    @Test
    fun `secondary jpeg after EOI is recognized as info`() {
        val r = checkBytes(jpegBytes(trailing = jpegBytes()), "jpg")
        assertEquals(CheckStatus.INFO, r.item("jpeg.trailing").status)
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
    }

    @Test
    fun `huffman jpeg without DHT warns, arithmetic jpeg skips`() {
        assertEquals(CheckStatus.WARN, checkBytes(jpegBytes(withDht = false), "jpg").item("jpeg.dht").status)
        assertEquals(CheckStatus.SKIP, checkBytes(jpegBytes(withDht = false, sofMarker = 0xC9), "jpg").item("jpeg.dht").status)
    }

    @Test
    fun `unrecognized format yields a single skip item`() {
        val r = checkBytes("not an image at all".toByteArray(), "jpg")
        assertEquals("UNKNOWN", r.format)
        assertEquals(CheckStatus.SKIP, r.item("format").status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: compilation FAIL — `ImageIntegrityChecker` unresolved.

- [ ] **Step 4: Implement the dispatcher and JPEG checker**

`ImageIntegrityChecker.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.collectWarnings
import java.io.File

object ImageIntegrityChecker {
    fun check(file: File, root: BoxNode): ImageStructureReport =
        ByteReader.open(file).use { check(root, it) }

    fun check(root: BoxNode, reader: ByteReader): ImageStructureReport {
        val format = detectImageFormat(reader)
        val result = try {
            when (format) {
                "JPEG" -> JpegIntegrity.check(root, reader)
                else -> FormatCheckResult(
                    listOf(IntegrityCheckItem("format", "Format", CheckStatus.SKIP, "Unrecognized image format; structure checks are not available")),
                )
            }
        } catch (e: Exception) {
            FormatCheckResult(
                listOf(
                    IntegrityCheckItem(
                        "structure.error", "Structure interpretation", CheckStatus.FAIL,
                        "The structure could not be interpreted: ${e.message ?: e.toString()}",
                    ),
                ),
            )
        }
        return ImageStructureReport(format, result.items + parserWarningItems(root), result.declaredWidth, result.declaredHeight)
    }

    internal fun parserWarningItems(root: BoxNode): List<IntegrityCheckItem> {
        val warnings = collectWarnings(root)
        if (warnings.isEmpty()) {
            return listOf(IntegrityCheckItem("parser.warnings", "Parser warnings", CheckStatus.PASS, "No parser warnings"))
        }
        return warnings.map { w ->
            IntegrityCheckItem("parser.warning", "Parser warning (${w.node.type})", CheckStatus.WARN, w.warning, w.node.offset, w.node.size)
        }
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Magic-byte detection, same precedence as parseFile. */
fun detectImageFormat(reader: ByteReader): String {
    val len = reader.length
    if (len < 4) return "UNKNOWN"
    val head = reader.readBytes(0, minOf(len, 12L).toInt())
    fun ascii(from: Int, n: Int) = if (head.size >= from + n) String(head, from, n, Charsets.US_ASCII) else ""
    return when {
        head.u8(0) == 0xFF && head.u8(1) == 0xD8 -> "JPEG"
        head.size >= 8 && head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "PNG"
        ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a" -> "GIF"
        ascii(0, 2) == "BM" -> "BMP"
        (head.u8(0) == 0x49 && head.u8(1) == 0x49 && head.u8(2) == 0x2A && head.u8(3) == 0) ||
            (head.u8(0) == 0x4D && head.u8(1) == 0x4D && head.u8(2) == 0 && head.u8(3) == 0x2A) -> "TIFF"
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "WEBP"
        ascii(4, 4) == "ftyp" -> "HEIF"
        else -> "UNKNOWN"
    }
}
```

`JpegIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.INFO
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN

/** Uses the JpegWalker tree: top-level nodes are markers ("SOI", "SOF0", "DQT", "SOS", "EOI", ...);
 *  after EOI the walker emits secondary images (SOI...), "sefd", "EmbeddedVideoData" or "?" (unknown). */
object JpegIntegrity {
    // Huffman-coded frames; SOF9-15 are arithmetic-coded and need no DHT.
    private val HUFFMAN_SOF = setOf("SOF0", "SOF1", "SOF2", "SOF3", "SOF5", "SOF6", "SOF7")

    fun check(root: BoxNode, reader: ByteReader): FormatCheckResult {
        val nodes = root.children
        val fileLength = reader.length
        val items = mutableListOf<IntegrityCheckItem>()

        items += if (nodes.firstOrNull()?.type == "SOI") {
            IntegrityCheckItem("jpeg.soi", "Start of image (SOI)", PASS, "SOI at offset 0", 0, 2)
        } else {
            IntegrityCheckItem("jpeg.soi", "Start of image (SOI)", FAIL, "The file does not start with an SOI marker", 0, 2)
        }

        val eoiIndex = nodes.indexOfFirst { it.type == "EOI" }
        val primary = if (eoiIndex >= 0) nodes.subList(0, eoiIndex) else nodes
        val sof = primary.firstOrNull { it.type.startsWith("SOF") }
        val sos = primary.firstOrNull { it.type == "SOS" }
        items += when {
            sof == null -> IntegrityCheckItem("jpeg.frame", "Frame and scan headers", FAIL, "No SOF (frame header) segment in the primary image")
            sos == null -> IntegrityCheckItem("jpeg.frame", "Frame and scan headers", FAIL, "No SOS (scan) segment in the primary image")
            sos.offset < sof.offset -> IntegrityCheckItem(
                "jpeg.frame", "Frame and scan headers", FAIL,
                "SOS at offset ${sos.offset} precedes ${sof.type} at offset ${sof.offset}", sos.offset, sos.size,
            )
            else -> IntegrityCheckItem(
                "jpeg.frame", "Frame and scan headers", PASS,
                "${sof.type} at offset ${sof.offset}, first SOS at offset ${sos.offset}", sof.offset, sof.size,
            )
        }

        if (sos != null) {
            val beforeScan = primary.filter { it.offset < sos.offset }
            items += if (beforeScan.any { it.type == "DQT" }) {
                IntegrityCheckItem("jpeg.dqt", "Quantization tables (DQT)", PASS, "DQT present before the first scan")
            } else {
                IntegrityCheckItem("jpeg.dqt", "Quantization tables (DQT)", FAIL, "No DQT segment before the first SOS", sos.offset, sos.size)
            }
            items += when {
                sof == null || sof.type !in HUFFMAN_SOF ->
                    IntegrityCheckItem("jpeg.dht", "Huffman tables (DHT)", SKIP, "Arithmetic coding: DHT not required")
                beforeScan.any { it.type == "DHT" } ->
                    IntegrityCheckItem("jpeg.dht", "Huffman tables (DHT)", PASS, "DHT present before the first scan")
                else -> IntegrityCheckItem(
                    "jpeg.dht", "Huffman tables (DHT)", WARN,
                    "No DHT before the first SOS; decoders must fall back to the standard (MJPEG) tables", sos.offset, sos.size,
                )
            }
        }

        if (eoiIndex < 0) {
            val last = nodes.lastOrNull()
            items += IntegrityCheckItem(
                "jpeg.eoi", "End of image (EOI)", FAIL,
                "No EOI marker: the data ends at offset $fileLength without terminating the image (truncated)",
                last?.offset, last?.size,
            )
        } else {
            val eoi = nodes[eoiIndex]
            items += IntegrityCheckItem("jpeg.eoi", "End of image (EOI)", PASS, "EOI at offset ${eoi.offset}", eoi.offset, eoi.size)
            items += trailingItem(nodes.drop(eoiIndex + 1), eoi.offset + eoi.size, fileLength)
        }

        // SOF layout: FF Cx, length(2), precision(1), height(2), width(2)
        val size = sof?.takeIf { it.size >= 9 }?.let { node ->
            val b = reader.readBytes(node.offset + 5, 4)
            b.u16be(2) to b.u16be(0)
        }
        return FormatCheckResult(items, size?.first, size?.second)
    }

    private fun trailingItem(trailing: List<BoxNode>, eoiEnd: Long, fileLength: Long): IntegrityCheckItem {
        if (trailing.isEmpty()) return IntegrityCheckItem("jpeg.trailing", "Data after EOI", PASS, "No data after EOI")
        val unknown = trailing.firstOrNull { it.type == "?" }
        if (unknown != null) {
            return IntegrityCheckItem(
                "jpeg.trailing", "Data after EOI", WARN,
                "${fileLength - unknown.offset} unrecognized byte(s) after the image at offset ${unknown.offset}",
                unknown.offset, fileLength - unknown.offset,
            )
        }
        val kinds = buildList {
            if (trailing.any { it.type == "SOI" }) add("embedded JPEG image(s) (MPF/secondary)")
            if (trailing.any { it.type == "sefd" }) add("Samsung SEF trailer")
            if (trailing.any { it.type == "EmbeddedVideoData" }) add("embedded motion photo video")
        }.ifEmpty { listOf("additional JPEG segments") }
        return IntegrityCheckItem(
            "jpeg.trailing", "Data after EOI", INFO,
            "Recognized data after EOI: ${kinds.joinToString(", ")}", eoiEnd, fileLength - eoiEnd,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: PASS. If the valid-file test fails because of a `parser.warning` item, print `r.items`, fix the fixture bytes (not the checker) so the synthetic JPEG parses without warnings.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add app/src/main/kotlin/com/multiviewer/parser/integrity app/src/test/kotlin/com/multiviewer/parser/integrity
git commit -m "feat(integrity): image structure model, dispatcher and JPEG checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: PNG checker (chunk CRC, IHDR/IDAT/IEND, trailing data)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/PngIntegrity.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/ImageIntegrityChecker.kt` (add dispatch branch)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/integrity/IntegrityTestFixtures.kt` (append PNG builders)
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/PngIntegrityTest.kt`

**Interfaces:**
- Consumes: Task 1 models/helpers (`crc32Of`, `hex32`, `be32` fixture).
- Produces: `object PngIntegrity { fun check(reader: ByteReader): FormatCheckResult }`; item ids `png.ihdr`, `png.crc`, `png.truncated`, `png.idat`, `png.iend`, `png.trailing`; fixtures `pngChunk(type, data, corruptCrc)`, `pngBytes(...)`.

- [ ] **Step 1: Append fixtures**

```kotlin
internal val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun pngChunk(type: String, data: ByteArray, corruptCrc: Boolean = false): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = java.util.zip.CRC32().apply { update(typeBytes); update(data) }.value
    return be32(data.size.toLong()) + typeBytes + data + be32(if (corruptCrc) crc xor 1L else crc)
}

internal fun pngIdatPayload(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 0x7F)) } // filter byte + one gray pixel
    return out.toByteArray()
}

/** 1x1 8-bit grayscale PNG with a tEXt chunk before IDAT. */
internal fun pngBytes(
    corruptIdatCrc: Boolean = false,
    corruptTextCrc: Boolean = false,
    trailing: ByteArray = ByteArray(0),
): ByteArray {
    val ihdr = be32(1) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
    return PNG_SIG + pngChunk("IHDR", ihdr) +
        pngChunk("tEXt", "k\u0000v".toByteArray(Charsets.ISO_8859_1), corruptTextCrc) +
        pngChunk("IDAT", pngIdatPayload(), corruptIdatCrc) + pngChunk("IEND", ByteArray(0)) + trailing
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class PngIntegrityTest {
    @Test
    fun `valid png passes and declares 1x1`() {
        val r = checkBytes(pngBytes(), "png")
        assertEquals("PNG", r.format)
        listOf("png.ihdr", "png.crc", "png.idat", "png.iend", "png.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
        assertEquals(1, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `critical chunk CRC mismatch fails at the IDAT offset`() {
        val bytes = pngBytes(corruptIdatCrc = true)
        val idatOffset = (PNG_SIG.size + 25 + 15).toLong() // IHDR chunk 25 bytes, tEXt chunk 12+3
        val crc = checkBytes(bytes, "png").item("png.crc")
        assertEquals(CheckStatus.FAIL, crc.status)
        assertEquals(idatOffset, crc.offset)
    }

    @Test
    fun `ancillary chunk CRC mismatch only warns`() {
        val r = checkBytes(pngBytes(corruptTextCrc = true), "png")
        assertEquals(CheckStatus.WARN, r.item("png.crc").status)
        assertEquals(CheckStatus.WARN, r.overall)
    }

    @Test
    fun `truncated png fails layout and IEND`() {
        val full = pngBytes()
        val r = checkBytes(full.copyOf(full.size - 8), "png")
        assertEquals(CheckStatus.FAIL, r.item("png.truncated").status)
        assertEquals(CheckStatus.FAIL, r.item("png.iend").status)
    }

    @Test
    fun `bytes after IEND warn`() {
        val r = checkBytes(pngBytes(trailing = byteArrayOf(1, 2, 3)), "png")
        assertEquals(CheckStatus.WARN, r.item("png.trailing").status)
        assertEquals(3L, r.item("png.trailing").length)
    }

    @Test
    fun `non-consecutive IDAT chunks fail`() {
        val ihdr = be32(1) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
        val idat = pngIdatPayload()
        val bytes = PNG_SIG + pngChunk("IHDR", ihdr) + pngChunk("IDAT", idat.copyOfRange(0, 4)) +
            pngChunk("tEXt", "k\u0000v".toByteArray(Charsets.ISO_8859_1)) +
            pngChunk("IDAT", idat.copyOfRange(4, idat.size)) + pngChunk("IEND", ByteArray(0))
        assertEquals(CheckStatus.FAIL, checkBytes(bytes, "png").item("png.idat").status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.PngIntegrityTest' -q`
Expected: FAIL (`No item 'png.ihdr'` — PNG falls into the SKIP branch).

- [ ] **Step 4: Implement**

`PngIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.WARN

object PngIntegrity {
    private data class Chunk(val type: String, val offset: Long, val dataLength: Long)

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        val chunks = mutableListOf<Chunk>()
        val crcProblems = mutableListOf<IntegrityCheckItem>()
        var truncated: IntegrityCheckItem? = null
        var iend: Chunk? = null
        var verified = 0
        var pos = 8L
        while (pos < len) {
            if (len - pos < 12) {
                truncated = IntegrityCheckItem(
                    "png.truncated", "Chunk layout", FAIL,
                    "${len - pos} trailing byte(s) at offset $pos are too short for a chunk (truncated)", pos, len - pos,
                )
                break
            }
            val dataLength = reader.readUInt32(pos)
            val type = reader.readFourCC(pos + 4)
            if (dataLength > len - pos - 12) {
                truncated = IntegrityCheckItem(
                    "png.truncated", "Chunk layout", FAIL,
                    "Chunk '$type' at offset $pos declares $dataLength data byte(s) but the file ends at $len (truncated)",
                    pos, len - pos,
                )
                chunks += Chunk(type, pos, dataLength)
                break
            }
            val stored = reader.readUInt32(pos + 8 + dataLength)
            val computed = crc32Of(reader, pos + 4, 4 + dataLength)
            verified++
            if (stored != computed) {
                val critical = type.first().isUpperCase()
                crcProblems += IntegrityCheckItem(
                    "png.crc", "Chunk CRC ($type)", if (critical) FAIL else WARN,
                    "CRC mismatch in ${if (critical) "critical" else "ancillary"} chunk '$type': stored ${hex32(stored)}, computed ${hex32(computed)}",
                    pos, 12 + dataLength,
                )
            }
            val chunk = Chunk(type, pos, dataLength)
            chunks += chunk
            pos += 12 + dataLength
            if (type == "IEND") { iend = chunk; break }
        }

        val first = chunks.firstOrNull()
        items += if (first != null && first.type == "IHDR" && first.dataLength == 13L) {
            IntegrityCheckItem("png.ihdr", "Header chunk (IHDR)", PASS, "IHDR is the first chunk", first.offset, 25)
        } else {
            IntegrityCheckItem(
                "png.ihdr", "Header chunk (IHDR)", FAIL,
                if (first == null) "No chunks after the signature" else "First chunk is '${first.type}' (${first.dataLength} bytes); expected IHDR with 13 bytes",
                first?.offset, first?.let { 12 + it.dataLength },
            )
        }
        items += crcProblems.ifEmpty { listOf(IntegrityCheckItem("png.crc", "Chunk CRCs", PASS, "All $verified chunk CRC(s) match")) }
        truncated?.let { items += it }

        val idat = chunks.indices.filter { chunks[it].type == "IDAT" }
        items += when {
            idat.isEmpty() -> IntegrityCheckItem("png.idat", "Image data (IDAT)", FAIL, "No IDAT chunk")
            idat.last() - idat.first() + 1 != idat.size -> {
                val interrupter = chunks.subList(idat.first(), idat.last()).first { it.type != "IDAT" }
                IntegrityCheckItem(
                    "png.idat", "Image data (IDAT)", FAIL,
                    "IDAT chunks are not consecutive: '${interrupter.type}' at offset ${interrupter.offset} interrupts them",
                    interrupter.offset, 12 + interrupter.dataLength,
                )
            }
            else -> IntegrityCheckItem("png.idat", "Image data (IDAT)", PASS, "${idat.size} consecutive IDAT chunk(s)")
        }

        val end = iend
        if (end == null) {
            items += IntegrityCheckItem("png.iend", "End chunk (IEND)", FAIL, "No IEND chunk: the file ends at $len before the image is terminated")
        } else {
            items += IntegrityCheckItem("png.iend", "End chunk (IEND)", PASS, "IEND at offset ${end.offset}", end.offset, 12)
            val iendEnd = end.offset + 12 + end.dataLength
            items += if (iendEnd == len) {
                IntegrityCheckItem("png.trailing", "Data after IEND", PASS, "No data after IEND")
            } else {
                IntegrityCheckItem("png.trailing", "Data after IEND", WARN, "${len - iendEnd} byte(s) after IEND", iendEnd, len - iendEnd)
            }
        }

        val size = if (first?.type == "IHDR" && first.dataLength >= 8) {
            reader.readUInt32(16).toInt() to reader.readUInt32(20).toInt()
        } else null
        return FormatCheckResult(items, size?.first, size?.second)
    }
}
```

In `ImageIntegrityChecker.check`, add the branch below `"JPEG" -> ...`:
```kotlin
                "PNG" -> PngIntegrity.check(reader)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add -A app/src/main/kotlin/com/multiviewer/parser/integrity app/src/test/kotlin/com/multiviewer/parser/integrity
git commit -m "feat(integrity): PNG chunk CRC and layout checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: GIF, BMP and WebP checkers

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/GifIntegrity.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/BmpIntegrity.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/WebpIntegrity.kt`
- Modify: `ImageIntegrityChecker.kt` (3 dispatch branches), `IntegrityTestFixtures.kt` (append builders)
- Test: `GifIntegrityTest.kt`, `BmpIntegrityTest.kt`, `WebpIntegrityTest.kt` in `app/src/test/kotlin/com/multiviewer/parser/integrity/`

**Interfaces:**
- Produces: `GifIntegrity.check(reader)`, `BmpIntegrity.check(reader)`, `WebpIntegrity.check(reader)` → `FormatCheckResult`.
  Item ids — GIF: `gif.lsd`, `gif.frames`, `gif.blocks`, `gif.trailer`, `gif.trailing`. BMP: `bmp.header`, `bmp.size`, `bmp.offset`, `bmp.pixels`. WebP: `webp.riff`, `webp.chunks`, `webp.image`, `webp.canvas`.

- [ ] **Step 1: Append fixtures**

```kotlin
/** 2x1 GIF89a with a 2-colour global table and one frame. */
internal fun gifBytes(withTrailer: Boolean = true, truncateImageData: Boolean = false, trailing: ByteArray = ByteArray(0)): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(2, 0, 1, 0, 0x80.toByte(), 0, 0))
    out.write(byteArrayOf(0, 0, 0, -1, -1, -1))
    out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0))
    out.write(2)
    if (truncateImageData) { out.write(byteArrayOf(5, 1, 2)); return out.toByteArray() }
    out.write(byteArrayOf(2, 0x44, 0x01, 0))
    if (withTrailer) out.write(0x3B)
    out.write(trailing)
    return out.toByteArray()
}

/** Uncompressed 24-bit BMP (BITMAPINFOHEADER). */
internal fun bmpBytes(width: Int = 2, height: Int = 2, pixelBytes: Int? = null, bfSizeOverride: Long? = null): ByteArray {
    val stride = ((width * 24 + 31) / 32) * 4
    val pixels = ByteArray(pixelBytes ?: (stride * height))
    val size = 54L + pixels.size
    return "BM".toByteArray(Charsets.US_ASCII) + le32(bfSizeOverride ?: size) + le32(0) + le32(54) +
        le32(40) + le32(width.toLong()) + le32(height.toLong()) + le16(1) + le16(24) + le32(0) + le32(pixels.size.toLong()) +
        le32(2835) + le32(2835) + le32(0) + le32(0) + pixels
}

/** Lossless WebP with a 1x1 VP8L header (header-valid only), optionally under a VP8X canvas. */
internal fun webpBytes(riffSizeDelta: Long = 0, trailing: ByteArray = ByteArray(0), vp8xCanvas: Pair<Int, Int>? = null): ByteArray {
    fun chunk(type: String, data: ByteArray) =
        type.toByteArray(Charsets.US_ASCII) + le32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    val vp8l = byteArrayOf(0x2F, 0, 0, 0, 0, 0x07, 0x10, 0x00)
    val vp8x = vp8xCanvas?.let { (w, h) -> chunk("VP8X", byteArrayOf(0, 0, 0, 0) + le24(w - 1) + le24(h - 1)) } ?: ByteArray(0)
    val body = "WEBP".toByteArray(Charsets.US_ASCII) + vp8x + chunk("VP8L", vp8l)
    return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size + riffSizeDelta) + body + trailing
}
```

- [ ] **Step 2: Write the failing tests**

`GifIntegrityTest.kt`:
```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class GifIntegrityTest {
    @Test
    fun `valid gif passes`() {
        val r = checkBytes(gifBytes(), "gif")
        listOf("gif.lsd", "gif.frames", "gif.blocks", "gif.trailer", "gif.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(2, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `missing trailer fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(gifBytes(withTrailer = false), "gif").item("gif.trailer").status)
    }

    @Test
    fun `truncated sub-block chain fails blocks and trailer`() {
        val r = checkBytes(gifBytes(truncateImageData = true), "gif")
        assertEquals(CheckStatus.FAIL, r.item("gif.blocks").status)
        assertEquals(CheckStatus.FAIL, r.item("gif.trailer").status)
    }

    @Test
    fun `bytes after trailer warn`() {
        assertEquals(CheckStatus.WARN, checkBytes(gifBytes(trailing = byteArrayOf(9, 9)), "gif").item("gif.trailing").status)
    }
}
```

`BmpIntegrityTest.kt`:
```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class BmpIntegrityTest {
    @Test
    fun `valid bmp passes`() {
        val r = checkBytes(bmpBytes(), "bmp")
        listOf("bmp.size", "bmp.offset", "bmp.pixels").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(2, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `short pixel array fails`() {
        val r = checkBytes(bmpBytes(pixelBytes = 10), "bmp")
        assertEquals(CheckStatus.FAIL, r.item("bmp.pixels").status)
        assertEquals(54L, r.item("bmp.pixels").offset)
    }

    @Test
    fun `bfSize larger than the file fails, smaller warns`() {
        assertEquals(CheckStatus.FAIL, checkBytes(bmpBytes(bfSizeOverride = 1000), "bmp").item("bmp.size").status)
        assertEquals(CheckStatus.WARN, checkBytes(bmpBytes(bfSizeOverride = 60), "bmp").item("bmp.size").status)
    }
}
```

`WebpIntegrityTest.kt`:
```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class WebpIntegrityTest {
    @Test
    fun `valid simple webp passes and declares its bitstream size`() {
        val r = checkBytes(webpBytes(), "webp")
        listOf("webp.riff", "webp.chunks", "webp.image").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(CheckStatus.SKIP, r.item("webp.canvas").status)
        assertEquals(1, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `riff size larger than the file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(webpBytes(riffSizeDelta = 10), "webp").item("webp.riff").status)
    }

    @Test
    fun `trailing bytes warn`() {
        assertEquals(CheckStatus.WARN, checkBytes(webpBytes(trailing = byteArrayOf(1, 2)), "webp").item("webp.riff").status)
    }

    @Test
    fun `truncated chunk fails`() {
        val full = webpBytes()
        val r = checkBytes(full.copyOf(full.size - 3), "webp")
        assertEquals(CheckStatus.FAIL, r.item("webp.chunks").status)
    }

    @Test
    fun `vp8x canvas is compared with the bitstream`() {
        assertEquals(CheckStatus.PASS, checkBytes(webpBytes(vp8xCanvas = 1 to 1), "webp").item("webp.canvas").status)
        assertEquals(CheckStatus.WARN, checkBytes(webpBytes(vp8xCanvas = 4 to 4), "webp").item("webp.canvas").status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: FAIL (`No item 'gif.lsd'` etc.).

- [ ] **Step 4: Implement**

`GifIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.WARN

object GifIntegrity {
    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        if (len < 13) {
            return FormatCheckResult(listOf(IntegrityCheckItem("gif.lsd", "Logical screen descriptor", FAIL,
                "The file is too short ($len bytes) for a GIF header and screen descriptor", 0, len)))
        }
        val lsd = reader.readBytes(6, 7)
        val width = lsd.u16le(0)
        val height = lsd.u16le(2)
        val flags = lsd.u8(4)
        val gct = if (flags and 0x80 != 0) 3L * (1 shl ((flags and 7) + 1)) else 0L
        var pos = 13 + gct
        if (pos > len) {
            items += IntegrityCheckItem("gif.lsd", "Logical screen descriptor", FAIL,
                "The global color table needs $gct byte(s) but the file ends at $len", 13, len - 13)
            return FormatCheckResult(items, width, height)
        }
        items += IntegrityCheckItem("gif.lsd", "Logical screen descriptor", PASS,
            "${width}x$height, global color table $gct byte(s)", 6, 7 + gct)

        var frames = 0
        var trailerAt: Long? = null
        var problem: IntegrityCheckItem? = null
        loop@ while (pos < len) {
            when (val introducer = reader.readUInt8(pos)) {
                0x21 -> {
                    val next = if (pos + 2 <= len) skipSubBlocks(reader, pos + 2, len) else null
                    if (next == null) { problem = chainItem("extension block", pos, len); break@loop }
                    pos = next
                }
                0x2C -> {
                    if (pos + 11 > len) { problem = chainItem("image descriptor", pos, len); break@loop }
                    val imageFlags = reader.readUInt8(pos + 9)
                    val lct = if (imageFlags and 0x80 != 0) 3L * (1 shl ((imageFlags and 7) + 1)) else 0L
                    val dataStart = pos + 10 + lct + 1 // + LZW minimum code size byte
                    val next = if (dataStart <= len) skipSubBlocks(reader, dataStart, len) else null
                    if (next == null) { problem = chainItem("image data of frame ${frames + 1}", pos, len); break@loop }
                    frames++
                    pos = next
                }
                0x3B -> { trailerAt = pos; pos += 1; break@loop }
                else -> {
                    problem = IntegrityCheckItem("gif.blocks", "Block structure", FAIL,
                        "Unexpected byte 0x%02x at offset %d where a block introducer was expected".format(introducer, pos), pos, 1)
                    break@loop
                }
            }
        }

        items += if (frames > 0) {
            IntegrityCheckItem("gif.frames", "Image frames", PASS, "$frames frame(s)")
        } else {
            IntegrityCheckItem("gif.frames", "Image frames", FAIL, "No complete image frame found")
        }
        items += problem ?: IntegrityCheckItem("gif.blocks", "Block structure", PASS, "All blocks and sub-block chains terminate")
        val trailer = trailerAt
        if (trailer == null) {
            items += IntegrityCheckItem("gif.trailer", "Trailer (0x3B)", FAIL,
                "No trailer byte: the file ends at offset $len before the GIF is terminated (truncated)")
        } else {
            items += IntegrityCheckItem("gif.trailer", "Trailer (0x3B)", PASS, "Trailer at offset $trailer", trailer, 1)
            items += if (pos == len) {
                IntegrityCheckItem("gif.trailing", "Data after trailer", PASS, "No data after the trailer")
            } else {
                IntegrityCheckItem("gif.trailing", "Data after trailer", WARN, "${len - pos} byte(s) after the trailer", pos, len - pos)
            }
        }
        return FormatCheckResult(items, width, height)
    }

    /** Offset just past the zero-length terminator, or null if the chain runs past [end]. */
    private fun skipSubBlocks(reader: ByteReader, start: Long, end: Long): Long? {
        var p = start
        while (p < end) {
            val size = reader.readUInt8(p)
            if (size == 0) return p + 1
            p += 1 + size
        }
        return null
    }

    private fun chainItem(what: String, pos: Long, len: Long) = IntegrityCheckItem(
        "gif.blocks", "Block structure", FAIL,
        "The $what starting at offset $pos runs past the end of the file ($len bytes, truncated)", pos, len - pos,
    )
}
```

`BmpIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN
import kotlin.math.abs

object BmpIntegrity {
    private val UNCOMPRESSED = setOf(0L, 3L, 6L) // BI_RGB, BI_BITFIELDS, BI_ALPHABITFIELDS

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        if (len < 26) {
            return FormatCheckResult(listOf(IntegrityCheckItem("bmp.header", "Headers", FAIL,
                "The file is too short ($len bytes) for BMP headers", 0, len)))
        }
        val h = reader.readBytes(0, minOf(len, 54L).toInt())
        val bfSize = h.u32le(2)
        val offBits = h.u32le(10)
        val dibSize = h.u32le(14)
        val core = dibSize == 12L
        val items = mutableListOf<IntegrityCheckItem>()

        items += when {
            bfSize == len -> IntegrityCheckItem("bmp.size", "Declared file size", PASS, "bfSize matches the file size ($len)", 2, 4)
            bfSize == 0L -> IntegrityCheckItem("bmp.size", "Declared file size", WARN, "bfSize is 0 (not filled in by the writer)", 2, 4)
            bfSize < len -> IntegrityCheckItem("bmp.size", "Declared file size", WARN,
                "bfSize $bfSize is smaller than the file ($len): ${len - bfSize} extra byte(s)", 2, 4)
            else -> IntegrityCheckItem("bmp.size", "Declared file size", FAIL,
                "bfSize $bfSize exceeds the file size $len (truncated by ${bfSize - len} byte(s))", 2, 4)
        }
        val headersEnd = 14 + dibSize
        items += if (offBits in headersEnd until len) {
            IntegrityCheckItem("bmp.offset", "Pixel data offset", PASS, "Pixel data starts at offset $offBits", 10, 4)
        } else {
            IntegrityCheckItem("bmp.offset", "Pixel data offset", FAIL, "bfOffBits $offBits is outside the valid range [$headersEnd, $len)", 10, 4)
        }
        if (!core && h.size < 34) {
            items += IntegrityCheckItem("bmp.header", "Headers", FAIL, "The DIB header is truncated", 14, len - 14)
            return FormatCheckResult(items)
        }
        val width = if (core) h.u16le(18) else h.u32le(18).toInt()
        val height = abs(if (core) h.u16le(20).toShort().toInt() else h.u32le(22).toInt())
        val bpp = if (core) h.u16le(24) else h.u16le(28)
        val compression = if (core) 0L else h.u32le(30)
        items += if (compression in UNCOMPRESSED) {
            val stride = ((width.toLong() * bpp + 31) / 32) * 4
            val needed = stride * height
            val available = (len - offBits).coerceAtLeast(0)
            if (available >= needed) {
                IntegrityCheckItem("bmp.pixels", "Pixel array size", PASS, "$needed byte(s) needed, $available available", offBits, needed)
            } else {
                IntegrityCheckItem("bmp.pixels", "Pixel array size", FAIL,
                    "The pixel array needs $needed byte(s) (${width}x$height, $bpp bpp) but only $available remain (truncated)", offBits, available)
            }
        } else {
            IntegrityCheckItem("bmp.pixels", "Pixel array size", SKIP, "Compressed pixel data (compression=$compression): size not computable")
        }
        return FormatCheckResult(items, width, height)
    }
}
```

`WebpIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN

object WebpIntegrity {
    private data class Chunk(val type: String, val offset: Long, val size: Long)

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        val declaredEnd = reader.readBytes(4, 4).u32le(0) + 8
        items += when {
            declaredEnd == len -> IntegrityCheckItem("webp.riff", "RIFF size", PASS, "RIFF size matches the file size ($len)", 4, 4)
            declaredEnd < len -> IntegrityCheckItem("webp.riff", "RIFF size", WARN,
                "RIFF declares $declaredEnd byte(s); ${len - declaredEnd} extra byte(s) follow", declaredEnd, len - declaredEnd)
            else -> IntegrityCheckItem("webp.riff", "RIFF size", FAIL,
                "RIFF declares $declaredEnd byte(s) but the file has $len (truncated by ${declaredEnd - len})", 4, 4)
        }

        val limit = minOf(len, declaredEnd)
        val chunks = mutableListOf<Chunk>()
        var problem: IntegrityCheckItem? = null
        var pos = 12L
        while (pos + 8 <= limit) {
            val type = reader.readFourCC(pos)
            val size = reader.readBytes(pos + 4, 4).u32le(0)
            if (pos + 8 + size > limit) {
                problem = IntegrityCheckItem("webp.chunks", "Chunk layout", FAIL,
                    "Chunk '$type' at offset $pos declares $size byte(s) but only ${limit - pos - 8} remain (truncated)", pos, limit - pos)
                break
            }
            chunks += Chunk(type, pos, size)
            pos += 8 + size + (size and 1L)
        }
        if (problem == null && pos < limit) {
            problem = IntegrityCheckItem("webp.chunks", "Chunk layout", WARN,
                "${limit - pos} stray byte(s) at offset $pos are too short for a chunk header", pos, limit - pos)
        }
        items += problem ?: IntegrityCheckItem("webp.chunks", "Chunk layout", PASS, "${chunks.size} chunk(s), all within the RIFF payload")

        val images = chunks.filter { it.type == "VP8 " || it.type == "VP8L" }
        val frameCount = chunks.count { it.type == "ANMF" }
        items += when {
            frameCount > 0 -> IntegrityCheckItem("webp.image", "Image data", PASS, "Animated: $frameCount frame(s)")
            images.size == 1 -> IntegrityCheckItem("webp.image", "Image data", PASS,
                "${images[0].type.trim()} bitstream at offset ${images[0].offset}", images[0].offset, 8 + images[0].size)
            images.isEmpty() -> IntegrityCheckItem("webp.image", "Image data", FAIL, "No VP8/VP8L image data chunk")
            else -> IntegrityCheckItem("webp.image", "Image data", WARN,
                "${images.size} image data chunks; a still image has one", images[1].offset, 8 + images[1].size)
        }

        val bitstream = images.firstOrNull()?.let { bitstreamDimensions(reader, it.offset + 8, it.type, it.size) }
        val vp8x = chunks.firstOrNull()?.takeIf { it.type == "VP8X" && it.size >= 10 }
        val canvas = vp8x?.let { reader.readBytes(it.offset + 12, 6).let { b -> (b.u24le(0) + 1) to (b.u24le(3) + 1) } }
        items += when {
            vp8x == null -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "Simple format (no VP8X canvas)")
            frameCount > 0 -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "Animated: frames may be smaller than the canvas")
            canvas == null || bitstream == null -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "The bitstream size is not readable")
            canvas == bitstream -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", PASS,
                "Canvas ${canvas.first}x${canvas.second} matches the bitstream", vp8x.offset, 18)
            else -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", WARN,
                "Canvas ${canvas.first}x${canvas.second} differs from bitstream ${bitstream.first}x${bitstream.second}", vp8x.offset, 18)
        }
        val declared = canvas ?: bitstream
        return FormatCheckResult(items, declared?.first, declared?.second)
    }

    internal fun bitstreamDimensions(reader: ByteReader, dataOffset: Long, type: String, size: Long): Pair<Int, Int>? {
        val need = if (type == "VP8 ") 10 else 5
        if (size < need || dataOffset + need > reader.length) return null
        val b = reader.readBytes(dataOffset, need)
        return when (type) {
            "VP8 " -> if (b.u8(3) == 0x9D && b.u8(4) == 0x01 && b.u8(5) == 0x2A) (b.u16le(6) and 0x3FFF) to (b.u16le(8) and 0x3FFF) else null
            "VP8L" -> if (b.u8(0) != 0x2F) null else {
                val bits = b.u32le(1)
                ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
            }
            else -> null
        }
    }
}
```

In `ImageIntegrityChecker.check` add:
```kotlin
                "GIF" -> GifIntegrity.check(reader)
                "BMP" -> BmpIntegrity.check(reader)
                "WEBP" -> WebpIntegrity.check(reader)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add -A app/src/main/kotlin/com/multiviewer/parser/integrity app/src/test/kotlin/com/multiviewer/parser/integrity
git commit -m "feat(integrity): GIF, BMP and WebP structure checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: HEIF/AVIF checker (meta items, iloc extents, grids)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/HeifIntegrity.kt`
- Modify: `ImageIntegrityChecker.kt` (dispatch), `IntegrityTestFixtures.kt` (append builders)
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/HeifIntegrityTest.kt`

**Interfaces:**
- Consumes (existing, `internal` in the same module): `findFirst(node, predicate)` (`parser/MediaSummaryBuilder.kt`), `findItemProperty(meta, itemId, type)` and `extractItemBytes(reader, iloc, itemId, idatBase)` (`parser/HeifHevcThumbnail.kt`), `decodeGridItemPayload(bytes): GridLayout?` (`parser/HeicTileGrid.kt`).
  Tree facts: `meta` children include `hdlr` (field `handler_type`), `pitm` (field `primary_item_ID`), `iinf` → children `infe` (fields `item_ID`, `item_type`), `iloc` → children `item_<id>` (field `construction_method`) → children `extent` with fields `offset` (absolute — MetaBoxDecoder already resolves construction_method 1 against idat) or `idat_relative_offset` (only when no idat could be resolved) plus `length`; `iref` → children typed by reference (`dimg`) with fields `from_item_ID`, `to_item_ID[i]`.
- Produces: `HeifIntegrity.check(root, reader)`; ids `heif.ftyp`, `heif.meta`, `heif.hdlr`, `heif.primary`, `heif.iloc`, `heif.grid`.

- [ ] **Step 1: Append fixtures**

```kotlin
internal fun box(type: String, payload: ByteArray) = be32(8L + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload
internal fun fullBox(type: String, version: Int, payload: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)
private fun infe(id: Int, type: String) = fullBox("infe", 2, be16(id) + be16(0) + type.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
private fun heifHdlr() = fullBox("hdlr", 0, be32(0) + "pict".toByteArray(Charsets.US_ASCII) + ByteArray(12) + byteArrayOf(0))
private val HEIF_FTYP = box("ftyp", "heic".toByteArray(Charsets.US_ASCII) + be32(0) + "mif1heic".toByteArray(Charsets.US_ASCII))

/** Single coded item 1 ('hvc1', 64x48 via ispe) stored in mdat. extentShift moves its iloc offset. */
internal fun heifBytes(extentShift: Long = 0, primaryId: Int = 1): ByteArray {
    val mdatPayload = ByteArray(16) { it.toByte() }
    val iprp = box("iprp", box("ipco", fullBox("ispe", 0, be32(64) + be32(48))) +
        fullBox("ipma", 0, be32(1) + be16(1) + byteArrayOf(1, 0x81.toByte())))
    fun iloc(offset: Long) = fullBox("iloc", 0, byteArrayOf(0x44, 0x00) + be16(1) +
        be16(1) + be16(0) + be16(1) + be32(offset) + be32(mdatPayload.size.toLong()))
    fun meta(offset: Long) = fullBox("meta", 0, heifHdlr() + fullBox("pitm", 0, be16(primaryId)) +
        fullBox("iinf", 0, be16(1) + infe(1, "hvc1")) + iloc(offset) + iprp)
    val mdatPayloadOffset = HEIF_FTYP.size + meta(0).size + 8L
    return HEIF_FTYP + meta(mdatPayloadOffset + extentShift) + box("mdat", mdatPayload)
}

/** Grid item 1 (1 row x 2 columns, output 128x64, descriptor in idat) + tile items 2 and 3 in mdat.
 *  dimg references only the first [tileRefs] tiles. */
internal fun heifGridBytes(tileRefs: Int = 2): ByteArray {
    val gridDescriptor = byteArrayOf(0, 0, 0, 1) + be16(128) + be16(64)
    val mdatPayload = ByteArray(16) { it.toByte() }
    fun iloc(mdatOff: Long) = fullBox("iloc", 1, byteArrayOf(0x44, 0x00) + be16(3) +
        be16(1) + be16(1) + be16(0) + be16(1) + be32(0) + be32(gridDescriptor.size.toLong()) +
        be16(2) + be16(0) + be16(0) + be16(1) + be32(mdatOff) + be32(8) +
        be16(3) + be16(0) + be16(0) + be16(1) + be32(mdatOff + 8) + be32(8))
    val iref = fullBox("iref", 0, box("dimg", be16(1) + be16(tileRefs) + (2 until 2 + tileRefs).fold(ByteArray(0)) { a, id -> a + be16(id) }))
    fun meta(mdatOff: Long) = fullBox("meta", 0, heifHdlr() + fullBox("pitm", 0, be16(1)) +
        fullBox("iinf", 0, be16(3) + infe(1, "grid") + infe(2, "hvc1") + infe(3, "hvc1")) +
        iloc(mdatOff) + iref + box("idat", gridDescriptor))
    val mdatPayloadOffset = HEIF_FTYP.size + meta(0).size + 8L
    return HEIF_FTYP + meta(mdatPayloadOffset) + box("mdat", mdatPayload)
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class HeifIntegrityTest {
    @Test
    fun `valid single-item heif passes and declares the ispe size`() {
        val r = checkBytes(heifBytes(), "heic")
        assertEquals("HEIF", r.format)
        listOf("heif.ftyp", "heif.meta", "heif.primary", "heif.iloc").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(64, r.declaredWidth)
        assertEquals(48, r.declaredHeight)
    }

    @Test
    fun `extent past end of file fails`() {
        val r = checkBytes(heifBytes(extentShift = 100), "heic")
        assertEquals(CheckStatus.FAIL, r.item("heif.iloc").status)
    }

    @Test
    fun `truncated file fails the iloc check`() {
        val full = heifBytes()
        assertEquals(CheckStatus.FAIL, checkBytes(full.copyOf(full.size - 8), "heic").item("heif.iloc").status)
    }

    @Test
    fun `undeclared primary item fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(heifBytes(primaryId = 7), "heic").item("heif.primary").status)
    }

    @Test
    fun `complete grid passes and declares its output size`() {
        val r = checkBytes(heifGridBytes(tileRefs = 2), "heic")
        assertEquals(CheckStatus.PASS, r.item("heif.grid").status, r.items.toString())
        assertEquals(128, r.declaredWidth)
        assertEquals(64, r.declaredHeight)
    }

    @Test
    fun `grid with missing tile references fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(heifGridBytes(tileRefs = 1), "heic").item("heif.grid").status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.HeifIntegrityTest' -q`
Expected: FAIL (`No item 'heif.ftyp'`).

- [ ] **Step 4: Implement**

`HeifIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.decodeGridItemPayload
import com.multiviewer.parser.extractItemBytes
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findItemProperty
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS

object HeifIntegrity {
    private const val MAX_EXTENT_ITEMS = 20

    fun check(root: BoxNode, reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()

        val ftyp = root.children.firstOrNull()
        items += if (ftyp != null && ftyp.type == "ftyp" && ftyp.size >= 12) {
            IntegrityCheckItem("heif.ftyp", "File type (ftyp)", PASS, "Major brand '${reader.readFourCC(ftyp.offset + 8)}'", ftyp.offset, ftyp.size)
        } else {
            IntegrityCheckItem("heif.ftyp", "File type (ftyp)", FAIL, "The first box is '${ftyp?.type}', expected ftyp", ftyp?.offset, ftyp?.size)
        }

        val meta = root.children.firstOrNull { it.type == "meta" }
        if (meta == null) {
            items += IntegrityCheckItem("heif.meta", "Item metadata (meta)", FAIL, "No top-level meta box: the file declares no image items")
            return FormatCheckResult(items)
        }
        fun child(type: String) = findFirst(meta) { it.type == type }
        val missing = listOf("hdlr", "pitm", "iinf", "iloc").filter { child(it) == null }
        items += if (missing.isEmpty()) {
            IntegrityCheckItem("heif.meta", "Item metadata (meta)", PASS, "hdlr, pitm, iinf and iloc present", meta.offset, meta.size)
        } else {
            IntegrityCheckItem("heif.meta", "Item metadata (meta)", FAIL, "meta is missing: ${missing.joinToString()}", meta.offset, meta.size)
        }
        child("hdlr")?.let { hdlr ->
            val handler = hdlr.field("handler_type")
            if (handler != null && handler != "pict") {
                items += IntegrityCheckItem("heif.hdlr", "Handler (hdlr)", FAIL, "Handler type is '$handler', expected 'pict'", hdlr.offset, hdlr.size)
            }
        }

        val itemTypes: Map<Long, String> = child("iinf")?.children.orEmpty()
            .filter { it.type == "infe" }
            .mapNotNull { n -> n.field("item_ID")?.toLongOrNull()?.let { it to (n.field("item_type") ?: "") } }
            .toMap()
        val primaryId = child("pitm")?.field("primary_item_ID")?.toLongOrNull()
        items += when {
            primaryId == null -> IntegrityCheckItem("heif.primary", "Primary item", FAIL, "pitm is missing or unreadable")
            primaryId !in itemTypes -> IntegrityCheckItem("heif.primary", "Primary item", FAIL, "Primary item $primaryId is not declared in iinf")
            else -> IntegrityCheckItem("heif.primary", "Primary item", PASS, "Primary item $primaryId (${itemTypes[primaryId]})")
        }

        val iloc = child("iloc")
        val idat = child("idat")
        if (iloc != null) items += extentItems(iloc, idat, len)

        val iref = child("iref")
        val idatBase = idat?.let { it.offset + it.headerSize } ?: 0L
        var declared: Pair<Int, Int>? = null
        for (gridId in itemTypes.filterValues { it == "grid" }.keys) {
            val tiles = iref?.children
                ?.firstOrNull { it.type == "dimg" && it.field("from_item_ID")?.toLongOrNull() == gridId }
                ?.fields?.filter { it.name.startsWith("to_item_ID") }?.mapNotNull { it.value.toLongOrNull() }
                .orEmpty()
            val layout = iloc?.let { runCatching { extractItemBytes(reader, it, gridId, idatBase) }.getOrNull() }
                ?.let { decodeGridItemPayload(it) }
            val undeclared = tiles.filter { it !in itemTypes }
            items += when {
                layout == null -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL, "The grid descriptor could not be read")
                tiles.size != layout.rows * layout.columns -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL,
                    "Grid ${layout.rows}x${layout.columns} needs ${layout.rows * layout.columns} tile(s) but dimg references ${tiles.size}")
                undeclared.isNotEmpty() -> IntegrityCheckItem("heif.grid", "Grid item $gridId", FAIL,
                    "Tile item(s) ${undeclared.joinToString()} referenced by dimg are not declared in iinf")
                else -> IntegrityCheckItem("heif.grid", "Grid item $gridId", PASS,
                    "${layout.rows}x${layout.columns} tiles, output ${layout.outputWidth}x${layout.outputHeight}")
            }
            if (gridId == primaryId && layout != null) declared = layout.outputWidth to layout.outputHeight
        }
        if (declared == null && primaryId != null) {
            findItemProperty(meta, primaryId, "ispe")?.let { ispe ->
                val w = ispe.field("image_width")?.toIntOrNull()
                val h = ispe.field("image_height")?.toIntOrNull()
                if (w != null && h != null) declared = w to h
            }
        }
        return FormatCheckResult(items, declared?.first, declared?.second)
    }

    private fun extentItems(iloc: BoxNode, idat: BoxNode?, len: Long): List<IntegrityCheckItem> {
        val bad = mutableListOf<IntegrityCheckItem>()
        var count = 0
        for (item in iloc.children) {
            val itemId = item.type.removePrefix("item_")
            val method = item.field("construction_method")?.toIntOrNull() ?: 0
            for (extent in item.children.filter { it.type == "extent" }) {
                count++
                val length = extent.field("length")?.toLongOrNull() ?: continue
                val absolute = extent.field("offset")?.toLongOrNull()
                when {
                    method == 0 && absolute != null -> {
                        val end = if (length == 0L) len else absolute + length
                        if (absolute > len || end > len) {
                            bad += IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                                "Item $itemId extent [$absolute, $end) exceeds the file size $len (${end - len} byte(s) missing)", extent.offset, extent.size)
                        }
                    }
                    method == 1 -> {
                        // "offset" is already absolute when MetaBoxDecoder resolved it; otherwise resolve the idat-relative value here.
                        val start = absolute ?: extent.field("idat_relative_offset")?.toLongOrNull()
                            ?.let { rel -> idat?.let { it.offset + it.headerSize + rel } }
                        val idatEnd = idat?.let { it.offset + it.size }
                        if (start == null || idatEnd == null || start + length > idatEnd) {
                            bad += IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                                "Item $itemId idat extent of $length byte(s) does not fit inside the idat box", extent.offset, extent.size)
                        }
                    }
                }
            }
        }
        return when {
            bad.isEmpty() -> listOf(IntegrityCheckItem("heif.iloc", "Item locations (iloc)", PASS, "$count extent(s) within the file", iloc.offset, iloc.size))
            bad.size > MAX_EXTENT_ITEMS -> bad.take(MAX_EXTENT_ITEMS) + IntegrityCheckItem("heif.iloc", "Item locations (iloc)", FAIL,
                "…and ${bad.size - MAX_EXTENT_ITEMS} more extent(s) outside the file")
            else -> bad
        }
    }

    private fun BoxNode.field(name: String): String? = fields.firstOrNull { it.name == name }?.value
}
```

In `ImageIntegrityChecker.check` add:
```kotlin
                "HEIF" -> HeifIntegrity.check(root, reader)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: PASS. If a fixture fails to parse (e.g. the existing decoders expect a slightly different layout), print `parseFile(f)` children and fix the fixture bytes, not the checker semantics.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add -A app/src/main/kotlin/com/multiviewer/parser/integrity app/src/test/kotlin/com/multiviewer/parser/integrity
git commit -m "feat(integrity): HEIF item, iloc extent and grid checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: TIFF / RAW checker (IFD chain, loops, strip/tile/preview ranges)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/TiffIntegrity.kt`
- Modify: `ImageIntegrityChecker.kt` (dispatch), `IntegrityTestFixtures.kt` (append builder)
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/TiffIntegrityTest.kt`

**Interfaces:**
- Produces:
  - `TiffIntegrity.check(reader): FormatCheckResult` — ids `tiff.header`, `tiff.ifd`, `tiff.loop`, `tiff.data`
  - `TiffIntegrity.largestJpegPreview(reader): Pair<Long, Long>?` — (offset, length) of the largest embedded non-lossless JPEG; used by Task 6 for RAW decode
  - `TiffIntegrity.jpegSofMarker(reader, start, length): Int?`

- [ ] **Step 1: Append fixture**

```kotlin
/** Little-endian TIFF, IFD0 at 8: ImageWidth=4, ImageLength=2, Compression, StripOffsets, StripByteCounts
 *  [+ JPEGInterchangeFormat/Length]. 8 strip bytes follow the IFD, then the optional JPEG preview. */
internal fun tiffBytes(stripCountOverride: Long? = null, nextIfd: Long = 0, jpegPreview: ByteArray? = null): ByteArray {
    fun entry(tag: Int, type: Int, count: Long, value: Long) = le16(tag) + le16(type) + le32(count) + le32(value)
    val n = if (jpegPreview != null) 7 else 5
    val dataStart = 8L + 2 + 12 * n + 4
    val strip = ByteArray(8) { 0x55 }
    val previewOffset = dataStart + strip.size
    var entries = entry(0x100, 3, 1, 4) + entry(0x101, 3, 1, 2) +
        entry(0x103, 3, 1, if (jpegPreview != null) 6 else 1) +
        entry(0x111, 4, 1, dataStart) + entry(0x117, 4, 1, stripCountOverride ?: strip.size.toLong())
    if (jpegPreview != null) entries += entry(0x201, 4, 1, previewOffset) + entry(0x202, 4, 1, jpegPreview.size.toLong())
    return byteArrayOf(0x49, 0x49, 0x2A, 0) + le32(8) + le16(n) + entries + le32(nextIfd) + strip + (jpegPreview ?: ByteArray(0))
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiffIntegrityTest {
    private fun reader(bytes: ByteArray): ByteReader {
        val f = File.createTempFile("tiff-", ".tif"); f.deleteOnExit(); f.writeBytes(bytes)
        return ByteReader.open(f)
    }

    @Test
    fun `valid tiff passes and declares IFD0 size`() {
        val r = checkBytes(tiffBytes(), "tif")
        assertEquals("TIFF", r.format)
        listOf("tiff.ifd", "tiff.data").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(4, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `strip past end of file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(stripCountOverride = 1000), "tif").item("tiff.data").status)
    }

    @Test
    fun `IFD chain pointing back to itself is a loop`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(nextIfd = 8), "tif").item("tiff.loop").status)
    }

    @Test
    fun `next IFD outside the file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(nextIfd = 99_999), "tif").item("tiff.ifd").status)
    }

    @Test
    fun `largest baseline jpeg preview is found, lossless is ignored`() {
        val preview = jpegBytes()
        val bytes = tiffBytes(jpegPreview = preview)
        reader(bytes).use { assertEquals((bytes.size - preview.size).toLong() to preview.size.toLong(), TiffIntegrity.largestJpegPreview(it)) }
        reader(tiffBytes(jpegPreview = jpegBytes(sofMarker = 0xC3))).use { assertNull(TiffIntegrity.largestJpegPreview(it)) }
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.TiffIntegrityTest' -q`
Expected: compilation FAIL (`TiffIntegrity` unresolved).

- [ ] **Step 4: Implement**

`TiffIntegrity.kt`:
```kotlin
package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS

/** TIFF and the TIFF-based RAW formats (CR2, NEF, ARW, DNG). Walks IFDs from raw bytes. */
object TiffIntegrity {
    private const val MAX_IFDS = 512
    private const val MAX_ENTRIES = 4096
    private const val MAX_VALUES = 1_000_000L
    private val TYPE_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 13 to 4, 16 to 8)
    private val INTEGER_TYPES = setOf(1, 3, 4, 13)

    private class Walk(
        val items: List<IntegrityCheckItem>,
        val width: Int?,
        val height: Int?,
        val jpegCandidates: List<Pair<Long, Long>>,
    )

    fun check(reader: ByteReader): FormatCheckResult = walk(reader).let { FormatCheckResult(it.items, it.width, it.height) }

    /** (offset, length) of the largest embedded JPEG that is not lossless (SOF3) — a RAW file's preview. */
    fun largestJpegPreview(reader: ByteReader): Pair<Long, Long>? =
        walk(reader).jpegCandidates.distinct()
            .filter { (off, n) ->
                n >= 4 && off + n <= reader.length &&
                    reader.readUInt8(off) == 0xFF && reader.readUInt8(off + 1) == 0xD8 &&
                    jpegSofMarker(reader, off, n).let { it != null && it != 0xC3 }
            }
            .maxByOrNull { it.second }

    internal fun jpegSofMarker(reader: ByteReader, start: Long, length: Long): Int? {
        var p = start + 2
        val end = start + length
        while (p + 4 <= end) {
            if (reader.readUInt8(p) != 0xFF) return null
            val m = reader.readUInt8(p + 1)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return m
            if (m == 0xDA || m == 0xD9) return null
            p += 2 + reader.readUInt16(p + 2)
        }
        return null
    }

    private fun walk(reader: ByteReader): Walk {
        val len = reader.length
        if (len < 8) {
            return Walk(listOf(IntegrityCheckItem("tiff.header", "TIFF header", FAIL, "The file is too short ($len bytes) for a TIFF header", 0, len)), null, null, emptyList())
        }
        val le = reader.readUInt8(0) == 0x49
        fun u16(off: Long): Int = reader.readBytes(off, 2).let { if (le) it.u16le(0) else it.u16be(0) }
        fun u32(off: Long): Long = reader.readBytes(off, 4).let { if (le) it.u32le(0) else it.u32be(0) }

        val problems = mutableListOf<IntegrityCheckItem>()
        val visited = HashSet<Long>()
        val candidates = mutableListOf<Pair<Long, Long>>()
        var rangeCount = 0
        var width: Int? = null
        var height: Int? = null

        fun readValues(entry: Long): List<Long>? {
            val type = u16(entry + 2)
            val count = u32(entry + 4)
            val size = TYPE_SIZES[type] ?: return null
            if (type !in INTEGER_TYPES || count > MAX_VALUES) return null
            val total = size * count
            val dataOff = if (total <= 4) entry + 8 else u32(entry + 8)
            if (dataOff + total > len) return null
            return (0 until count.toInt()).map { i ->
                when (size) {
                    1 -> reader.readUInt8(dataOff + i).toLong()
                    2 -> u16(dataOff + 2L * i).toLong()
                    else -> u32(dataOff + 4L * i)
                }
            }
        }

        fun checkRanges(name: String, offsets: List<Long>?, counts: List<Long>?, kind: String, entry: Long?) {
            if (offsets == null && counts == null) return
            if (offsets == null || counts == null || offsets.size != counts.size) {
                problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                    "$name: ${kind}Offsets has ${offsets?.size ?: 0} value(s) but ${kind}ByteCounts has ${counts?.size ?: 0}", entry, 12)
                return
            }
            rangeCount += offsets.size
            val bad = offsets.indices.filter { offsets[it] + counts[it] > len }
            if (bad.isNotEmpty()) {
                val i = bad.first()
                problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                    "$name: ${bad.size} of ${offsets.size} $kind(s) exceed the file size $len; first is #$i at [${offsets[i]}, ${offsets[i] + counts[i]})",
                    offsets[i].coerceAtMost(len), (len - offsets[i]).coerceAtLeast(0))
            }
        }

        fun walkChain(start: Long, label: String, topLevel: Boolean) {
            var off = start
            var index = 0
            while (off != 0L) {
                val name = if (topLevel) "IFD$index" else if (index == 0) label else "$label+$index"
                if (visited.size >= MAX_IFDS) return
                if (off < 8 || off + 2 > len) {
                    problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL, "$name offset $off lies outside the file ($len bytes)")
                    return
                }
                if (!visited.add(off)) {
                    problems += IntegrityCheckItem("tiff.loop", "IFD loop", FAIL, "$name at offset $off was already visited (IFD loop)", off, 2)
                    return
                }
                val count = u16(off)
                val entriesEnd = off + 2 + 12L * count
                if (count > MAX_ENTRIES || entriesEnd + 4 > len) {
                    problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL,
                        "$name at offset $off declares $count entries that run past the end of the file", off, len - off)
                    return
                }
                val entries = (0 until count).associate { i -> val e = off + 2 + 12L * i; u16(e) to e }
                for ((tag, e) in entries) {
                    val size = TYPE_SIZES[u16(e + 2)] ?: continue
                    val total = size.toLong() * u32(e + 4)
                    if (total > 4) {
                        val d = u32(e + 8)
                        if (d + total > len) {
                            problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL,
                                "$name tag 0x%04x: value data [%d, %d) lies outside the file".format(tag, d, d + total), e, 12)
                        }
                    }
                }
                fun values(tag: Int) = entries[tag]?.let { readValues(it) }
                if (topLevel && index == 0) {
                    width = values(0x100)?.firstOrNull()?.toInt()
                    height = values(0x101)?.firstOrNull()?.toInt()
                }
                val stripOffsets = values(0x111)
                val stripCounts = values(0x117)
                checkRanges(name, stripOffsets, stripCounts, "strip", entries[0x111] ?: entries[0x117])
                checkRanges(name, values(0x144), values(0x145), "tile", entries[0x144] ?: entries[0x145])
                val jpegOff = values(0x201)?.firstOrNull()
                val jpegLen = values(0x202)?.firstOrNull()
                if (jpegOff != null && jpegLen != null) {
                    rangeCount++
                    if (jpegOff + jpegLen > len) {
                        problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                            "$name: JPEG preview [$jpegOff, ${jpegOff + jpegLen}) exceeds the file size $len", entries[0x201], 12)
                    } else {
                        candidates += jpegOff to jpegLen
                    }
                }
                val compression = values(0x103)?.firstOrNull()
                if ((compression == 6L || compression == 7L) && stripOffsets?.size == 1 && stripCounts?.size == 1 &&
                    stripOffsets[0] + stripCounts[0] <= len
                ) {
                    candidates += stripOffsets[0] to stripCounts[0]
                }
                values(0x14A)?.forEachIndexed { i, sub -> walkChain(sub, "$name/SubIFD$i", topLevel = false) }
                values(0x8769)?.firstOrNull()?.let { walkChain(it, "$name/ExifIFD", topLevel = false) }
                off = u32(entriesEnd)
                index++
            }
        }

        walkChain(u32(4), "IFD", topLevel = true)

        val structural = problems.filter { it.id == "tiff.ifd" || it.id == "tiff.loop" }
        val data = problems.filter { it.id == "tiff.data" }
        val items = structural.ifEmpty {
            listOf(IntegrityCheckItem("tiff.ifd", "IFD structure", PASS, "${visited.size} IFD(s), all within the file, no loops"))
        } + data.ifEmpty {
            listOf(IntegrityCheckItem("tiff.data", "Image data ranges", PASS, "$rangeCount strip/tile/preview range(s) within the file"))
        }
        return Walk(items, width, height, candidates)
    }
}
```

In `ImageIntegrityChecker.check` add:
```kotlin
                "TIFF" -> TiffIntegrity.check(reader)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.*' -q`
Expected: PASS. Note: the loop test also runs the existing `decodeTiff` parser on a self-referencing IFD chain via `checkBytes`. If that test hangs, the bug is in `parser/ExifDecoder.kt`'s IFD-chain walk (missing visited-set guard) — add a visited-offset guard there with its own regression test in `ExifDecoderTest.kt`, and report it as a separate finding.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add -A app/src/main/kotlin/com/multiviewer/parser app/src/test/kotlin/com/multiviewer/parser
git commit -m "feat(integrity): TIFF/RAW IFD, loop and data-range checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Decode verification (FFmpeg framecrc + Skia)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ImageDecodeCheck.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ImageDecodeCheckTest.kt`

**Interfaces:**
- Consumes: `ImageStructureReport` (format, declaredWidth/Height), `TiffIntegrity.largestJpegPreview`, `FfmpegLocator.ffmpegPath()/configureEnvironment(ProcessBuilder)`, `ProcessManager.register/terminate/unregister` (`com.multiviewer.util`).
- Produces:
  - `enum class ImageDecodeStatus { NOT_RUN, CLEAN, ISSUES, FAILED }`
  - `data class SkiaDecodeResult(attempted: Boolean, ok: Boolean, detail: String)`
  - `data class ImageDecodeReport(ffmpegStatus, decodedFrames: Long, decodedWidth: Int?, decodedHeight: Int?, logs: List<String>, logsTruncated: Boolean, ffmpegVersion: String?, source: String, skia: SkiaDecodeResult, declaredWidth: Int?, declaredHeight: Int?)` with `val status: ImageDecodeStatus` and `val resolutionStatus: CheckStatus`
  - `suspend fun inspectImageDecode(file: File, structure: ImageStructureReport, ffmpeg: String = FfmpegLocator.ffmpegPath(), timeoutMs: Long = 120_000): ImageDecodeReport`
  - internal: `imageDecodeStatus(exit, frames, logs)`, `combineDecodeStatus(ffmpeg, skia)`, `resolutionCheck(dw, dh, w, h)`, `classifyFramecrcLine(line)`, `skiaDecode(bytes)`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImageDecodeCheckTest {
    private fun realJpeg(): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 64)
        surface.canvas.clear(0xFF3366CC.toInt())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
    }

    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `decode status needs exit 0, no log and at least one frame`() {
        assertEquals(ImageDecodeStatus.CLEAN, imageDecodeStatus(0, 1, emptyList()))
        assertEquals(ImageDecodeStatus.ISSUES, imageDecodeStatus(0, 1, listOf("overread 8")))
        assertEquals(ImageDecodeStatus.FAILED, imageDecodeStatus(1, 1, emptyList()))
        assertEquals(ImageDecodeStatus.FAILED, imageDecodeStatus(0, 0, emptyList()))
    }

    @Test
    fun `skia failure downgrades a clean ffmpeg result`() {
        val bad = SkiaDecodeResult(attempted = true, ok = false, detail = "Incomplete input")
        val skipped = SkiaDecodeResult(attempted = false, ok = false, detail = "n/a")
        assertEquals(ImageDecodeStatus.ISSUES, combineDecodeStatus(ImageDecodeStatus.CLEAN, bad))
        assertEquals(ImageDecodeStatus.CLEAN, combineDecodeStatus(ImageDecodeStatus.CLEAN, skipped))
        assertEquals(ImageDecodeStatus.FAILED, combineDecodeStatus(ImageDecodeStatus.FAILED, bad))
    }

    @Test
    fun `resolution check accepts a rotation swap`() {
        assertEquals(CheckStatus.PASS, resolutionCheck(4000, 2252, 2252, 4000))
        assertEquals(CheckStatus.WARN, resolutionCheck(64, 64, 32, 64))
        assertEquals(CheckStatus.SKIP, resolutionCheck(null, 64, 64, 64))
    }

    @Test
    fun `framecrc lines are classified`() {
        assertEquals(FramecrcLine.Dimensions(2252, 4000), classifyFramecrcLine("#dimensions 0: 2252x4000"))
        assertEquals(FramecrcLine.Header, classifyFramecrcLine("#tb 0: 1/1"))
        assertEquals(FramecrcLine.Frame, classifyFramecrcLine("0,          0,          0,        1, 13512000, 0xbffcdbb8"))
        assertEquals(FramecrcLine.Log("[mjpeg @ 0x1] overread 8"), classifyFramecrcLine("[mjpeg @ 0x1] overread 8"))
    }

    @Test
    fun `skia rejects a truncated jpeg`() {
        val jpeg = realJpeg()
        assertTrue(skiaDecode(jpeg).ok)
        assertFalse(skiaDecode(jpeg.copyOf(jpeg.size / 2)).ok)
    }

    @Test
    fun `ffmpeg integration - full jpeg is clean, truncated is not`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val jpeg = realJpeg()
        fun run(bytes: ByteArray): ImageDecodeReport {
            val f = File.createTempFile("decode-", ".jpg"); f.deleteOnExit(); f.writeBytes(bytes)
            val structure = ImageIntegrityChecker.check(f, parseFile(f))
            return runBlocking { inspectImageDecode(f, structure, ffmpeg = "ffmpeg") }
        }
        val full = run(jpeg)
        assertEquals(ImageDecodeStatus.CLEAN, full.status, full.logs.toString())
        assertEquals(64, full.decodedWidth)
        assertEquals(CheckStatus.PASS, full.resolutionStatus)
        assertNotEquals(ImageDecodeStatus.CLEAN, run(jpeg.copyOf(jpeg.size / 2)).status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ImageDecodeCheckTest' -q`
Expected: compilation FAIL (`imageDecodeStatus` unresolved).

- [ ] **Step 3: Implement**

`ImageDecodeCheck.kt`:
```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.TiffIntegrity
import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ImageDecodeStatus { NOT_RUN, CLEAN, ISSUES, FAILED }

data class SkiaDecodeResult(val attempted: Boolean, val ok: Boolean, val detail: String)

data class ImageDecodeReport(
    val ffmpegStatus: ImageDecodeStatus,
    val decodedFrames: Long,
    val decodedWidth: Int?,
    val decodedHeight: Int?,
    val logs: List<String>,
    val logsTruncated: Boolean,
    val ffmpegVersion: String?,
    val source: String,
    val skia: SkiaDecodeResult,
    val declaredWidth: Int?,
    val declaredHeight: Int?,
) {
    val status: ImageDecodeStatus get() = combineDecodeStatus(ffmpegStatus, skia)
    val resolutionStatus: CheckStatus get() = resolutionCheck(declaredWidth, declaredHeight, decodedWidth, decodedHeight)
}

private val RAW_IMAGE_EXTENSIONS = setOf("cr2", "nef", "arw", "dng")
private val SKIA_FORMATS = setOf("JPEG", "PNG", "GIF", "WEBP", "BMP")
private val ANIMATED_FORMATS = setOf("GIF", "WEBP")
private const val MAX_LOG_LINES = 500
private const val MAX_SKIA_PIXELS = 100_000_000L

/** CLEAN requires exit 0 AND no error output AND at least one decoded frame. */
internal fun imageDecodeStatus(exit: Int, frames: Long, logs: List<String>): ImageDecodeStatus = when {
    exit != 0 || frames == 0L -> ImageDecodeStatus.FAILED
    logs.isNotEmpty() -> ImageDecodeStatus.ISSUES
    else -> ImageDecodeStatus.CLEAN
}

internal fun combineDecodeStatus(ffmpeg: ImageDecodeStatus, skia: SkiaDecodeResult): ImageDecodeStatus = when {
    ffmpeg == ImageDecodeStatus.FAILED -> ImageDecodeStatus.FAILED
    skia.attempted && !skia.ok -> ImageDecodeStatus.ISSUES
    else -> ffmpeg
}

/** A width/height swap matches: FFmpeg applies EXIF / irot rotation to the decoded frame. */
internal fun resolutionCheck(declaredW: Int?, declaredH: Int?, decodedW: Int?, decodedH: Int?): CheckStatus = when {
    declaredW == null || declaredH == null || decodedW == null || decodedH == null -> CheckStatus.SKIP
    (declaredW == decodedW && declaredH == decodedH) || (declaredW == decodedH && declaredH == decodedW) -> CheckStatus.PASS
    else -> CheckStatus.WARN
}

internal sealed class FramecrcLine {
    data class Dimensions(val width: Int, val height: Int) : FramecrcLine()
    object Header : FramecrcLine()
    object Frame : FramecrcLine()
    data class Log(val text: String) : FramecrcLine()
}

private val DIMENSIONS = Regex("""^#dimensions \d+: (\d+)x(\d+)""")
private val FRAME_LINE = Regex("""^\d+,\s""")

internal fun classifyFramecrcLine(line: String): FramecrcLine {
    DIMENSIONS.find(line)?.let { return FramecrcLine.Dimensions(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    return when {
        line.startsWith("#") -> FramecrcLine.Header
        FRAME_LINE.containsMatchIn(line) -> FramecrcLine.Frame
        else -> FramecrcLine.Log(line)
    }
}

/** Skia (libjpeg-turbo/libpng/libwebp/wuffs) is stricter than FFmpeg about truncation: it throws "Incomplete input". */
internal fun skiaDecode(bytes: ByteArray): SkiaDecodeResult = try {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val width = codec.width
    val height = codec.height
    if (width.toLong() * height > MAX_SKIA_PIXELS) {
        SkiaDecodeResult(false, false, "Skipped: ${width}x$height exceeds the Skia decode limit")
    } else {
        val bitmap = Bitmap()
        bitmap.allocPixels(codec.imageInfo)
        codec.readPixels(bitmap)
        bitmap.close()
        codec.close()
        SkiaDecodeResult(true, true, "Decoded ${width}x$height")
    }
} catch (e: Exception) {
    SkiaDecodeResult(true, false, e.message ?: e.toString())
}

/** Streams merged stdout/stderr line by line; cancellation or timeout kills the child process. */
internal suspend fun runImageDecodeProcess(
    command: List<String>,
    stdin: ByteArray?,
    timeoutMs: Long,
    onLine: (String) -> Unit,
): Int = withTimeout(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val process = try {
            ProcessManager.register(
                ProcessBuilder(command).redirectErrorStream(true).also(FfmpegLocator::configureEnvironment).start(),
            )
        } catch (e: Exception) {
            cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { ProcessManager.terminate(process) }
        if (stdin != null) {
            Thread({ runCatching { process.outputStream.use { it.write(stdin) } } }, "image-decode-stdin")
                .apply { isDaemon = true; start() }
        } else {
            runCatching { process.outputStream.close() }
        }
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!cont.isActive) break
                        onLine(line.take(8192))
                    }
                }
                val exit = process.waitFor()
                if (cont.isActive) cont.resume(exit)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                ProcessManager.terminate(process)
                ProcessManager.unregister(process)
            }
        }, "image-decode-output").apply { isDaemon = true; start() }
    }
}

private suspend fun ffmpegVersion(ffmpeg: String): String? = try {
    var first: String? = null
    runImageDecodeProcess(listOf(ffmpeg, "-hide_banner", "-version"), null, 10_000) { if (first == null) first = it }
    first?.removePrefix("ffmpeg version ")?.substringBefore(' ')
} catch (e: TimeoutCancellationException) {
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

suspend fun inspectImageDecode(
    file: File,
    structure: ImageStructureReport,
    ffmpeg: String = FfmpegLocator.ffmpegPath(),
    timeoutMs: Long = 120_000,
): ImageDecodeReport = withContext(Dispatchers.IO) {
    val isRaw = structure.format == "TIFF" && file.extension.lowercase(Locale.US) in RAW_IMAGE_EXTENSIONS
    val preview: Pair<Long, ByteArray>? = if (isRaw) {
        ByteReader.open(file).use { r -> TiffIntegrity.largestJpegPreview(r)?.let { (off, n) -> off to r.readBytes(off, n.toInt()) } }
    } else null
    val version = ffmpegVersion(ffmpeg)

    if (isRaw && preview == null) {
        return@withContext ImageDecodeReport(
            ImageDecodeStatus.FAILED, 0, null, null,
            listOf("No decodable embedded JPEG preview found; RAW sensor data cannot be decoded"), false, version,
            "RAW: no embedded preview", SkiaDecodeResult(false, false, "No embedded preview"), null, null,
        )
    }
    val source = if (preview != null) {
        "Embedded JPEG preview at offset ${preview.first} (${preview.second.size} bytes); RAW sensor data not verified"
    } else "File"

    val logs = mutableListOf<String>()
    var logsTruncated = false
    fun log(line: String) {
        if (logs.size < MAX_LOG_LINES) logs += line.replace(file.absolutePath, file.name) else logsTruncated = true
    }
    var frames = 0L
    var width: Int? = null
    var height: Int? = null
    val command = buildList {
        add(ffmpeg)
        if (preview == null) add("-nostdin")
        addAll(listOf("-hide_banner", "-v", "error", "-i", if (preview != null) "pipe:0" else file.absolutePath))
        if (structure.format !in ANIMATED_FORMATS) addAll(listOf("-frames:v", "1"))
        addAll(listOf("-an", "-sn", "-dn", "-f", "framecrc", "-"))
    }
    val ffmpegStatus = try {
        val exit = runImageDecodeProcess(command, preview?.second, timeoutMs) { line ->
            when (val c = classifyFramecrcLine(line)) {
                is FramecrcLine.Dimensions -> if (width == null) { width = c.width; height = c.height }
                FramecrcLine.Frame -> frames++
                FramecrcLine.Header -> Unit
                is FramecrcLine.Log -> if (line.isNotBlank()) log(line)
            }
        }
        if (exit != 0) log("FFmpeg exited with code $exit")
        if (frames == 0L) log("No frames decoded")
        imageDecodeStatus(exit, frames, logs)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log("Decoding timed out (${timeoutMs / 1000} s)")
        ImageDecodeStatus.FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("FFmpeg could not run: ${e.message ?: e.toString()}")
        ImageDecodeStatus.FAILED
    }

    val skia = when {
        preview != null -> skiaDecode(preview.second)
        structure.format in SKIA_FORMATS -> skiaDecode(file.readBytes())
        else -> SkiaDecodeResult(false, false, "Skia does not decode ${structure.format}")
    }
    ImageDecodeReport(
        ffmpegStatus, frames, width, height, logs.toList(), logsTruncated, version, source, skia,
        if (isRaw) null else structure.declaredWidth,
        if (isRaw) null else structure.declaredHeight,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ImageDecodeCheckTest' -q`
Expected: PASS (the integration test is skipped only if `ffmpeg` is not on PATH — on this machine it is at `/opt/homebrew/bin/ffmpeg`, so it must run).

- [ ] **Step 5: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add app/src/main/kotlin/com/multiviewer/ui/ImageDecodeCheck.kt app/src/test/kotlin/com/multiviewer/ui/ImageDecodeCheckTest.kt
git commit -m "feat(integrity): FFmpeg framecrc + Skia image decode verification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: JSON rendering and CLI (`check <image> [--decode]`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/ImageIntegrityJson.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/cli/CheckFile.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/cli/CheckCommand.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/ImageIntegrityJsonTest.kt`

**Interfaces:**
- Consumes: `JsonValue` (`JObject`, `JArray`, `JString`, `JNumber` only — there is no boolean type on this branch), `render()`, `IMAGE_EXTENSIONS` (`com.multiviewer.ui`), `I18n.APP_VERSION`.
- Produces:
  - `fun imageIntegrityJson(structure: ImageStructureReport, decode: ImageDecodeReport?): JsonValue`
  - `fun buildImageIntegrityCaseJson(file: File, structure: ImageStructureReport, decode: ImageDecodeReport?): String`
  - `checkFile(file: File, decode: Boolean = false)`; `buildCheckJson(file, warnings, imageIntegrity: JsonValue? = null)`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.integrity.jpegBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ImageIntegrityJsonTest {
    private fun tempJpeg(bytes: ByteArray): File =
        File.createTempFile("cli-integrity-", ".jpg").apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun `check json for an image includes the structure report and NOT_RUN decode`() {
        val result = checkFile(tempJpeg(jpegBytes(withEoi = false))) as CheckResult.Success
        assertTrue(result.json.contains("\"imageIntegrity\""), result.json)
        assertTrue(result.json.contains("\"id\": \"jpeg.eoi\""), result.json)
        assertTrue(result.json.contains("\"overall\": \"FAIL\""), result.json)
        assertTrue(result.json.contains("\"status\": \"NOT_RUN\""), result.json)
    }

    @Test
    fun `analysis case json carries file identity and integrity`() {
        val file = tempJpeg(jpegBytes())
        val parsed = com.multiviewer.parser.parseFile(file)
        val structure = com.multiviewer.parser.integrity.ImageIntegrityChecker.check(file, parsed)
        val case = buildImageIntegrityCaseJson(file, structure, null)
        assertTrue(case.contains("\"sha256\""), case)
        assertTrue(case.contains("\"imageIntegrity\""), case)
        assertTrue(!case.contains(file.parent), "case must not contain the absolute path")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.cli.ImageIntegrityJsonTest' -q`
Expected: compilation FAIL (`buildImageIntegrityCaseJson` unresolved).

- [ ] **Step 3: Implement JSON rendering**

`ImageIntegrityJson.kt`:
```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.ui.I18n
import com.multiviewer.ui.ImageDecodeReport
import java.io.File
import java.security.MessageDigest

private fun size(w: Int?, h: Int?): JsonValue = JsonValue.JString(if (w != null && h != null) "${w}x$h" else "unknown")

fun imageIntegrityJson(structure: ImageStructureReport, decode: ImageDecodeReport?): JsonValue = JsonValue.JObject(
    listOf(
        "structure" to JsonValue.JObject(
            listOf(
                "format" to JsonValue.JString(structure.format),
                "overall" to JsonValue.JString(structure.overall.name),
                "declaredSize" to size(structure.declaredWidth, structure.declaredHeight),
                "items" to JsonValue.JArray(structure.items.map { item ->
                    JsonValue.JObject(buildList {
                        add("id" to JsonValue.JString(item.id))
                        add("title" to JsonValue.JString(item.title))
                        add("status" to JsonValue.JString(item.status.name))
                        add("detail" to JsonValue.JString(item.detail))
                        item.offset?.let { add("offset" to JsonValue.JNumber(it)) }
                        item.length?.let { add("length" to JsonValue.JNumber(it)) }
                    })
                }),
            ),
        ),
        "decode" to if (decode == null) {
            JsonValue.JObject(listOf("status" to JsonValue.JString("NOT_RUN")))
        } else {
            JsonValue.JObject(
                listOf(
                    "status" to JsonValue.JString(decode.status.name),
                    "source" to JsonValue.JString(decode.source),
                    "resolution" to JsonValue.JString(decode.resolutionStatus.name),
                    "ffmpeg" to JsonValue.JObject(
                        listOf(
                            "status" to JsonValue.JString(decode.ffmpegStatus.name),
                            "version" to JsonValue.JString(decode.ffmpegVersion ?: "unknown"),
                            "decodedFrames" to JsonValue.JNumber(decode.decodedFrames),
                            "decodedSize" to size(decode.decodedWidth, decode.decodedHeight),
                            "logsTruncated" to JsonValue.JString(decode.logsTruncated.toString()),
                            "logs" to JsonValue.JArray(decode.logs.map { JsonValue.JString(it) }),
                        ),
                    ),
                    "skia" to JsonValue.JObject(
                        listOf(
                            "status" to JsonValue.JString(
                                when {
                                    !decode.skia.attempted -> "SKIP"
                                    decode.skia.ok -> "CLEAN"
                                    else -> "ISSUES"
                                },
                            ),
                            "detail" to JsonValue.JString(decode.skia.detail),
                        ),
                    ),
                ),
            )
        },
    ),
)

/** Reproducible case file: identity (name, size, SHA-256 — never the absolute path) + integrity results. */
fun buildImageIntegrityCaseJson(file: File, structure: ImageStructureReport, decode: ImageDecodeReport?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return JsonValue.JObject(
        listOf(
            "schemaVersion" to JsonValue.JNumber(1),
            "tool" to JsonValue.JObject(listOf("name" to JsonValue.JString("unwrapMedia"), "version" to JsonValue.JString(I18n.APP_VERSION))),
            "file" to JsonValue.JObject(
                listOf(
                    "name" to JsonValue.JString(file.name),
                    "sizeBytes" to JsonValue.JNumber(file.length()),
                    "sha256" to JsonValue.JString(digest.digest().joinToString("") { "%02x".format(it) }),
                ),
            ),
            "imageIntegrity" to imageIntegrityJson(structure, decode),
        ),
    ).render()
}
```

- [ ] **Step 4: Wire into `CheckFile.kt`**

Replace the current `checkFile` and `buildCheckJson` (keep `CheckResult` and everything else unchanged) with:
```kotlin
fun checkFile(file: File, decode: Boolean = false): CheckResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> try {
        val warnings = collectWarnings(result.root)
        val imageIntegrity = if (result.file.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS) {
            val structure = ImageIntegrityChecker.check(result.file, result.root)
            val decodeReport = if (decode) runBlocking { inspectImageDecode(result.file, structure) } else null
            imageIntegrityJson(structure, decodeReport)
        } else {
            require(!decode) { "--decode requires an image file" }
            null
        }
        val json = buildCheckJson(result.file, warnings, imageIntegrity)
        val prompt = AiDiagnosticPromptBuilder.buildPrompt(result.file, result.root, warnings)
        CheckResult.Success(json = json, prompt = prompt, warningCount = warnings.size)
    } catch (e: Exception) {
        CheckResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
    is CliParseResult.Failure -> CheckResult.Failure(result.message)
}

fun buildCheckJson(file: File, warnings: List<WarningEntry>, imageIntegrity: JsonValue? = null): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ) + if (imageIntegrity != null) listOf("imageIntegrity" to imageIntegrity) else emptyList(),
    )
    return wrapper.render()
}
```
Add imports: `com.multiviewer.parser.integrity.ImageIntegrityChecker`, `com.multiviewer.ui.IMAGE_EXTENSIONS`, `com.multiviewer.ui.inspectImageDecode`, `kotlinx.coroutines.runBlocking`, `java.util.Locale`. Keep the existing body of `buildCheckJson`'s warnings rendering exactly as it is (only the trailing `+ if (...)` is new).

- [ ] **Step 5: Wire `--decode` into `CheckCommand.kt`**

Add `var decode = false` next to the other flags; add the branch `"--decode" -> decode = true` in the `when (arg)`; call `checkFile(File(filePath), decode = decode)`; update the usage line to `Usage: unwrapMedia check <file> [--prompt] [--clipboard] [--decode]`; add this help line after `--json`:
```
          --decode                 Images: also decode with FFmpeg and Skia (structure checks always run)
```

- [ ] **Step 6: Run the CLI tests**

Run: `./gradlew :app:test --tests 'com.multiviewer.cli.*' -q`
Expected: PASS. If an existing `CheckCommandTest`/`CheckFileTest` compares the full JSON of an image file byte-for-byte, update that expectation to include the new `imageIntegrity` block (the addition is intended); do not change the `buildCheckJson` tests that pass no `imageIntegrity`.

- [ ] **Step 7: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add app/src/main/kotlin/com/multiviewer/cli app/src/test/kotlin/com/multiviewer/cli
git commit -m "feat(cli): image integrity in check JSON, --decode flag, case JSON builder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Image Integrity window, menu, I18n, README

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ImageIntegrityWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` (one function next to `menuMotionPhotoIntegrityCheck`)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (state var, Analysis menu item, window render)
- Modify: `README.md` (CLI block + one paragraph)

**Interfaces:**
- Consumes: `TabState` (`file`, `root`, `type`, `isLoading`, `parameterSetHighlightRange: LongRange?`), `AppLanguage`, `AppColors`, Tasks 1–7 APIs.
- Produces: `@Composable fun ImageIntegrityWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit)`; `I18n.menuImageIntegrity(lang)`.

- [ ] **Step 1: I18n** — add below `menuMotionPhotoIntegrityCheck`:
```kotlin
    fun menuImageIntegrity(lang: AppLanguage) = if (lang == AppLanguage.KO) "이미지 무결성 검사…" else "Image Integrity…"
```

- [ ] **Step 2: Window**

`ImageIntegrityWindow.kt`:
```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.buildImageIntegrityCaseJson
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
private fun checkStatusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> AppColors.NeonGreen
    CheckStatus.INFO -> AppColors.NeonBlue
    CheckStatus.WARN -> AppColors.NeonYellow
    CheckStatus.FAIL -> AppColors.NeonRed
    CheckStatus.SKIP -> AppColors.TextMuted
}

@Composable
private fun decodeStatusColor(status: ImageDecodeStatus): Color = when (status) {
    ImageDecodeStatus.NOT_RUN -> AppColors.TextMuted
    ImageDecodeStatus.CLEAN -> AppColors.NeonGreen
    ImageDecodeStatus.ISSUES -> AppColors.NeonYellow
    ImageDecodeStatus.FAILED -> AppColors.NeonRed
}

private fun checkStatusLabel(status: CheckStatus, ko: Boolean): String = when (status) {
    CheckStatus.PASS -> if (ko) "정상" else "Pass"
    CheckStatus.INFO -> if (ko) "참고" else "Info"
    CheckStatus.WARN -> if (ko) "경고" else "Warning"
    CheckStatus.FAIL -> if (ko) "실패" else "Fail"
    CheckStatus.SKIP -> if (ko) "해당 없음" else "Skipped"
}

private fun decodeStatusLabel(status: ImageDecodeStatus, ko: Boolean): String = when (status) {
    ImageDecodeStatus.NOT_RUN -> if (ko) "미검사" else "Not run"
    ImageDecodeStatus.CLEAN -> if (ko) "완료 · 오류 없음" else "Completed · no errors"
    ImageDecodeStatus.ISSUES -> if (ko) "완료 · 오류 발견" else "Completed · errors found"
    ImageDecodeStatus.FAILED -> if (ko) "실패 · 정상 여부 확인 불가" else "Failed · integrity unconfirmed"
}

@Composable
fun ImageIntegrityWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()
    var structure by remember(tab.file) { mutableStateOf<ImageStructureReport?>(null) }
    var structureError by remember(tab.file) { mutableStateOf<String?>(null) }
    var decode by remember(tab.file) { mutableStateOf<ImageDecodeReport?>(null) }
    var job by remember(tab.file) { mutableStateOf<Job?>(null) }
    var running by remember(tab.file) { mutableStateOf(false) }
    var message by remember(tab.file) { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    DisposableEffect(tab.file) { onDispose { job?.cancel() } }

    LaunchedEffect(tab.file) {
        val root = tab.root
        if (root == null) {
            structureError = label("파일 구조가 아직 로드되지 않았습니다", "The file structure is not loaded yet")
            return@LaunchedEffect
        }
        try {
            structure = withContext(Dispatchers.IO) { ImageIntegrityChecker.check(tab.file, root) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            structureError = e.message ?: e.toString()
        }
    }

    Window(
        onCloseRequest = { job?.cancel(); onCloseRequest() },
        state = rememberWindowState(width = 1000.dp, height = 740.dp),
        title = label("이미지 무결성 검사", "Image Integrity") + " — ${tab.file.name}",
    ) {
        Column(
            Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val s = structure
            Text(tab.file.name, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label("형식: ", "Format: ") + (s?.format ?: "…"), color = AppColors.TextSecondary)
                Text(
                    label("구조: ", "Structure: ") + (s?.overall?.let { checkStatusLabel(it, ko) } ?: "…"),
                    color = s?.overall?.let { checkStatusColor(it) } ?: AppColors.TextMuted,
                )
                val ds = decode?.status ?: ImageDecodeStatus.NOT_RUN
                Text(label("디코딩: ", "Decode: ") + decodeStatusLabel(ds, ko), color = decodeStatusColor(ds))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !running && s != null, onClick = {
                    val snapshot = s ?: return@Button
                    running = true
                    decode = null
                    message = label("디코딩 중…", "Decoding…")
                    job = scope.launch {
                        try {
                            decode = inspectImageDecode(tab.file, snapshot)
                            message = ""
                            selectedTab = 1
                        } catch (e: CancellationException) {
                            message = label("중단 · 검사 완료되지 않음", "Cancelled · inspection incomplete")
                            throw e
                        } catch (e: Exception) {
                            message = label("검사 실패: ", "Inspection failed: ") + (e.message ?: e.toString())
                        } finally {
                            running = false
                        }
                    }
                }) { Text(label("디코딩 검사 시작", "Start decode check")) }
                OutlinedButton(enabled = running, onClick = { job?.cancel() }) { Text(label("취소", "Cancel")) }
                OutlinedButton(enabled = s != null && !running, onClick = {
                    val snapshot = s ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, label("분석 케이스 저장", "Save analysis case"), FileDialog.SAVE)
                    dialog.file = "${tab.file.nameWithoutExtension}-image-integrity.json"
                    dialog.isVisible = true
                    val name = dialog.file ?: return@OutlinedButton
                    val target = File(dialog.directory, name)
                    val decodeSnapshot = decode
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) {
                                if (!target.createNewFile()) error(label("이미 존재하는 파일입니다", "File already exists"))
                                target.writeText(buildImageIntegrityCaseJson(tab.file, snapshot, decodeSnapshot), Charsets.UTF_8)
                            }
                            label("저장됨: ", "Saved: ") + target.name
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            label("저장 실패: ", "Save failed: ") + (e.message ?: e.toString())
                        }
                    }
                }) { Text(label("분석 케이스 저장", "Save analysis case")) }
                Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(label("구조 검사", "Structure")) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(label("디코딩 검사", "Decode")) })
            }
            when (selectedTab) {
                0 -> StructurePanel(s, structureError, ko) { item ->
                    val offset = item.offset ?: return@StructurePanel
                    tab.parameterSetHighlightRange = offset until offset + maxOf(1L, item.length ?: 1L)
                }
                else -> DecodePanel(decode, ko)
            }
        }
    }
}

@Composable
private fun StructurePanel(report: ImageStructureReport?, error: String?, ko: Boolean, onSelect: (IntegrityCheckItem) -> Unit) {
    when {
        error != null -> Text(error, color = AppColors.NeonRed)
        report == null -> Text(if (ko) "구조 검사 중…" else "Checking structure…", color = AppColors.TextSecondary)
        else -> Column {
            Text(
                if (ko) "오프셋이 있는 항목을 클릭하면 Hex 뷰에서 해당 범위를 강조합니다." else "Click a row with an offset to highlight it in the Hex view.",
                color = AppColors.TextMuted, fontSize = 12.sp,
            )
            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(report.items) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.offset != null) { onSelect(item) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(checkStatusLabel(item.status, ko), color = checkStatusColor(item.status), modifier = Modifier.width(72.dp), fontSize = 13.sp)
                        Text(item.title, color = AppColors.TextPrimary, modifier = Modifier.width(220.dp), fontSize = 13.sp)
                        Text(
                            item.offset?.let { "0x%X".format(it) + (item.length?.let { l -> " +$l" } ?: "") } ?: "—",
                            color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.width(150.dp), fontSize = 12.sp,
                        )
                        Text(item.detail, color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodePanel(report: ImageDecodeReport?, ko: Boolean) {
    if (report == null) {
        Text(
            if (ko) "'디코딩 검사 시작'을 눌러 FFmpeg/Skia 디코딩 검사를 실행하세요." else "Press 'Start decode check' to run the FFmpeg/Skia decode check.",
            color = AppColors.TextSecondary,
        )
        return
    }
    fun size(w: Int?, h: Int?) = if (w != null && h != null) "${w}x$h" else "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (ko) "대상: " else "Source: ") + report.source, color = AppColors.TextPrimary, fontSize = 13.sp)
        Text(
            "FFmpeg ${report.ffmpegVersion ?: "?"}: ${decodeStatusLabel(report.ffmpegStatus, ko)} · " +
                (if (ko) "프레임 " else "frames ") + report.decodedFrames + " · " + size(report.decodedWidth, report.decodedHeight),
            color = decodeStatusColor(report.ffmpegStatus), fontSize = 13.sp,
        )
        val skiaColor = when {
            !report.skia.attempted -> AppColors.TextMuted
            report.skia.ok -> AppColors.NeonGreen
            else -> AppColors.NeonYellow
        }
        Text("Skia: ${report.skia.detail}", color = skiaColor, fontSize = 13.sp)
        Text(
            (if (ko) "해상도: 선언 " else "Resolution: declared ") + size(report.declaredWidth, report.declaredHeight) +
                (if (ko) " / 디코딩 " else " / decoded ") + size(report.decodedWidth, report.decodedHeight) +
                " — " + checkStatusLabel(report.resolutionStatus, ko),
            color = checkStatusColor(report.resolutionStatus), fontSize = 13.sp,
        )
        Text(
            (if (ko) "오류 로그" else "Error log") + " (${report.logs.size}${if (report.logsTruncated) "+" else ""})",
            color = AppColors.TextSecondary, fontSize = 12.sp,
        )
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(AppColors.Panel).padding(8.dp)) {
                items(report.logs) { line -> Text(line, color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}
```

- [ ] **Step 3: Main.kt wiring**

1. Next to `var motionPhotoIntegrityWindowOpen by remember { mutableStateOf(false) }` add:
```kotlin
        var imageIntegrityTab by remember { mutableStateOf<TabState?>(null) }
```
2. In the Analysis menu block (the one that defines `val hasActiveFile = currentTab != null && !currentTab.isLoading && currentTab.root != null`), directly after the SEF Integrity Check `item(...)`, add:
```kotlin
                item(
                    I18n.menuImageIntegrity(language),
                    enabled = hasActiveFile && currentTab?.type == MediaType.IMAGE,
                    onClick = { imageIntegrityTab = currentTab },
                )
```
3. Directly before `if (motionPhotoIntegrityWindowOpen) {` (window rendering section) add:
```kotlin
            imageIntegrityTab?.let { targetTab ->
                ImageIntegrityWindow(targetTab, language, onCloseRequest = { imageIntegrityTab = null })
            }
```
Add imports if Main.kt does not import `com.multiviewer.ui.*` wholesale (check the existing import style first).

- [ ] **Step 4: README** — in `README.md`, in the CLI example block add:
```
unwrapMedia check <image>            # 이미지: 구조 무결성 검사 결과(imageIntegrity) 포함
unwrapMedia check <image> --decode   # 이미지: FFmpeg + Skia 디코딩 검사까지 실행
```
and after the code block add the paragraph:
```
이미지는 **분석 → 이미지 무결성 검사…**에서 포맷별 구조 검사(JPEG SOI/EOI·세그먼트 순서, PNG 청크 CRC·IEND, HEIF iloc 범위·그리드 타일, WebP RIFF 크기, GIF 트레일러, BMP 픽셀 배열 크기, TIFF/RAW IFD 순환·스트립 범위)와 FFmpeg·Skia 디코딩 검사를 실행합니다. 구조 검사 결과는 오프셋을 가지며 클릭하면 Hex 뷰에서 강조됩니다. 두 결과는 합치지 않고 나란히 표시합니다. FFmpeg는 잘린 HEIC를 오류 없이 디코딩하는 경우가 있어 HEIF 잘림은 구조 검사(iloc 범위)로 판정합니다. RAW 파일은 센서 데이터 대신 내장 JPEG 프리뷰만 디코딩합니다.
```

- [ ] **Step 5: Build and run the full test suite**

Run: `./gradlew :app:compileKotlin -q && ./gradlew :app:test -q`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feature/image-integrity
git add app/src/main/kotlin/com/multiviewer/ui/ImageIntegrityWindow.kt app/src/main/kotlin/com/multiviewer/ui/I18n.kt app/src/main/kotlin/com/multiviewer/Main.kt README.md
git commit -m "feat(ui): Image Integrity window and Analysis menu entry

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Manual verification on real files (controller, not a subagent)

- [ ] Build the CLI distribution: `./gradlew :app:installDist -q` (or the project's existing CLI run path) and run `check <file> --decode` on, at minimum: a real camera JPEG, the same JPEG truncated to half, a real PNG with one IDAT byte flipped, `~/Downloads/heic_mp1.heic`, that HEIC truncated to 1/3, a GIF, a WebP, a BMP (`sips -s format bmp`), a TIFF (`sips -s format tiff`), and a DNG/CR2 if one exists on disk.
- [ ] Expected: intact files → structure PASS (or INFO only) and decode CLEAN; truncated JPEG → `jpeg.eoi` FAIL and decode not CLEAN; corrupted PNG → `png.crc` FAIL; truncated HEIC → `heif.iloc` FAIL even though FFmpeg alone reports nothing.
- [ ] Launch the GUI, open a corrupted file, open **Analysis → Image Integrity…**, click a FAIL row and confirm the Hex view highlights the range; run the decode check, cancel it once mid-run, save a case JSON and confirm it does not overwrite an existing file.
- [ ] Record any false positives on real files and fix them before finishing.

## Merge notes (after the Video Integrity work lands on main)

- `CheckFile.kt` / `CheckCommand.kt`: both branches add `--decode`. Merge as: video file → Video Integrity, image file → image decode; `--case` (Video branch) should embed `imageIntegrity` for images.
- Replace `runImageDecodeProcess` with a shared runner extracted from Video Integrity's `integrityProcess` (identical streaming/cancellation semantics).
- If `JsonValue.JBoolean` exists after merge, switch `logsTruncated` to it.
