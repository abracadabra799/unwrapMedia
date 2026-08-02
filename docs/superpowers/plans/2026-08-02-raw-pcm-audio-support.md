# Raw PCM Audio Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the app open headerless raw PCM audio files (`.pcm`, and `.raw` after an image/audio chooser), collecting sample rate/channels/format/byte order/offset via a new dialog, then reuse the existing audio player/waveform/spectrogram pipeline exactly as-is for every other format.

**Architecture:** A new `RawAudioDecoder.kt` (enums + pure functions, mirroring `RawPixelDecoder.kt`'s established pattern) computes ffmpeg format codes and durations. A new `RawAudioOpenDialog.kt` (mirroring `RawPixelOpenDialog.kt`) collects parameters. `AppState`/`Main.kt` gain the same `pendingXFile`/`confirmXFile` wiring already used for raw pixel images, plus a small chooser dialog to disambiguate `.raw`. `FfmpegAudioPlayer`/`AudioWaveformPeaks`/`generateSpectrogramImage` each gain an optional `rawAudioParams` parameter that, when present, prepends ffmpeg input-side format flags before `-i` and skips the (header-dependent) `ffprobe` call -- every existing call site's default behavior is completely unchanged.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, ffmpeg (subprocess, already bundled/required), `kotlin.test`.

## Global Constraints

- `.pcm` always routes directly to the new raw-audio dialog. `.raw` shows a chooser ("이미지" / "오디오") before routing to either the existing raw-pixel dialog or the new raw-audio dialog -- `.rgb`/`.rgba`/`.yuv` are unaffected, still route straight to the raw-pixel dialog as today.
- Reuse `MediaType.AUDIO` and the existing `AudioInspectorUI` entirely -- no new `MediaType`, no new inspector UI.
- Every existing call site of `FfmpegAudioPlayer`, `computeWaveformPeaks`, `generateSpectrogramImage`, and every other format's file-open path must keep working completely unchanged (new parameters are optional with defaults that preserve current behavior).
- Sample formats: 8-bit unsigned, 16/24/32-bit signed, 32-bit float -- byte order (little/big-endian) only meaningful for the non-8-bit formats, matching `RawPixelFormat.needsByteOrder`'s existing pattern.
- Offset-to-skip field is included (bytes to skip before real audio data starts), defaulting to `0`.
- Spec reference: `docs/superpowers/specs/2026-08-02-raw-pcm-audio-support-design.md`.

---

## Task 1: `RawAudioDecoder.kt` -- enums, params, pure functions

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/RawAudioDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/RawAudioDecoderTest.kt`

**Interfaces:**
- Produces (package `com.multiviewer.ui`, consumed by Tasks 2-4):
  ```kotlin
  enum class RawAudioFormat(val label: String, val bytesPerSample: Int, val needsByteOrder: Boolean)
  enum class RawAudioByteOrder(val label: String)
  data class RawAudioParams(val sampleRate: Int, val channels: Int, val format: RawAudioFormat, val byteOrder: RawAudioByteOrder, val offsetBytes: Long)
  fun RawAudioParams.ffmpegFormatCode(): String
  fun computeRawAudioDuration(fileSize: Long, offsetBytes: Long, sampleRate: Int, channels: Int, bytesPerSample: Int): Double
  fun rawAudioSourceFile(original: File, offsetBytes: Long): File
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/RawAudioDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawAudioDecoderTest {
    @Test
    fun `computes ffmpeg format codes for every format and byte order combination`() {
        assertEquals("u8", RawAudioParams(44100, 1, RawAudioFormat.U8, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("u8", RawAudioParams(44100, 1, RawAudioFormat.U8, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s16le", RawAudioParams(44100, 1, RawAudioFormat.S16, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s16be", RawAudioParams(44100, 1, RawAudioFormat.S16, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s24le", RawAudioParams(44100, 1, RawAudioFormat.S24, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s24be", RawAudioParams(44100, 1, RawAudioFormat.S24, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s32le", RawAudioParams(44100, 1, RawAudioFormat.S32, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s32be", RawAudioParams(44100, 1, RawAudioFormat.S32, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("f32le", RawAudioParams(44100, 1, RawAudioFormat.F32, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("f32be", RawAudioParams(44100, 1, RawAudioFormat.F32, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
    }

    @Test
    fun `computes duration from file size, sample rate, and channels`() {
        // 44100 Hz, 1 channel, 2 bytes/sample -> 88200 bytes/second. A 3-second stream is 264600 bytes.
        val duration = computeRawAudioDuration(fileSize = 264600, offsetBytes = 0, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(3.0, duration)
    }

    @Test
    fun `subtracts the offset before computing duration`() {
        // A 1-second (88200-byte) header in front of the same 3-second (264600-byte) payload as
        // above -- skipping the header via offsetBytes should still measure exactly 3 seconds.
        val duration = computeRawAudioDuration(fileSize = 264600 + 88200, offsetBytes = 88200, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(3.0, duration)
    }

    @Test
    fun `duration is zero when the offset consumes the whole file`() {
        val duration = computeRawAudioDuration(fileSize = 1000, offsetBytes = 5000, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(0.0, duration)
    }

    @Test
    fun `zero offset returns the original file unchanged`() {
        val file = File.createTempFile("raw-audio-decoder-test-", ".pcm")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val result = rawAudioSourceFile(file, 0L)
        assertEquals(file.absolutePath, result.absolutePath)
        file.delete()
    }

    @Test
    fun `positive offset produces a new file containing only the bytes after the offset`() {
        val file = File.createTempFile("raw-audio-decoder-test-", ".pcm")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4))
        val result = rawAudioSourceFile(file, 2L)
        assertTrue(result.absolutePath != file.absolutePath)
        assertEquals(listOf<Byte>(1, 2, 3, 4), result.readBytes().toList())
        file.delete()
        result.delete()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.RawAudioDecoderTest"`
Expected: compile failure -- `RawAudioFormat`/`RawAudioByteOrder`/`RawAudioParams`/`ffmpegFormatCode`/`computeRawAudioDuration`/`rawAudioSourceFile` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/RawAudioDecoder.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File

enum class RawAudioFormat(val label: String, val bytesPerSample: Int, val needsByteOrder: Boolean) {
    U8("8-bit unsigned", 1, false),
    S16("16-bit signed", 2, true),
    S24("24-bit signed", 3, true),
    S32("32-bit signed", 4, true),
    F32("32-bit float", 4, true),
}

enum class RawAudioByteOrder(val label: String) {
    LITTLE_ENDIAN("Little-endian"),
    BIG_ENDIAN("Big-endian"),
}

data class RawAudioParams(
    val sampleRate: Int,
    val channels: Int,
    val format: RawAudioFormat,
    val byteOrder: RawAudioByteOrder,
    val offsetBytes: Long,
)

// ffmpeg's raw-PCM format codes (passed as the argument to -f, before -i, when the input has no
// self-describing header) -- e.g. "s16le" = 16-bit signed little-endian. U8 has no byte-order
// axis (a single byte can't have endianness), so byteOrder is ignored for that case.
fun RawAudioParams.ffmpegFormatCode(): String {
    val suffix = if (byteOrder == RawAudioByteOrder.LITTLE_ENDIAN) "le" else "be"
    return when (format) {
        RawAudioFormat.U8 -> "u8"
        RawAudioFormat.S16 -> "s16$suffix"
        RawAudioFormat.S24 -> "s24$suffix"
        RawAudioFormat.S32 -> "s32$suffix"
        RawAudioFormat.F32 -> "f32$suffix"
    }
}

// Total playable duration once the leading offsetBytes are skipped -- used both for the open
// dialog's live preview and to build an AudioFileInfo without ffprobe (raw PCM has no header for
// ffprobe to read).
fun computeRawAudioDuration(fileSize: Long, offsetBytes: Long, sampleRate: Int, channels: Int, bytesPerSample: Int): Double {
    val playableBytes = (fileSize - offsetBytes).coerceAtLeast(0L)
    val frameSizeBytes = channels * bytesPerSample
    if (frameSizeBytes <= 0 || sampleRate <= 0) return 0.0
    val totalFrames = playableBytes / frameSizeBytes
    return totalFrames.toDouble() / sampleRate.toDouble()
}

// Raw PCM has no header, so an offset > 0 means real audio data doesn't start at byte 0 -- ffmpeg
// needs a file where it does. Mirrors RawPixelDecoder.decodeYuvFamily's temp-file pattern: when
// there's nothing to skip, hand back the original file untouched (no copy needed).
fun rawAudioSourceFile(original: File, offsetBytes: Long): File {
    if (offsetBytes <= 0L) return original
    val temp = File.createTempFile("raw-audio-offset-", ".pcm")
    temp.deleteOnExit()
    original.inputStream().use { input ->
        input.skip(offsetBytes)
        temp.outputStream().use { output -> input.copyTo(output) }
    }
    return temp
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.RawAudioDecoderTest"`
Expected: all 6 tests PASS.

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/RawAudioDecoder.kt app/src/test/kotlin/com/multiviewer/ui/RawAudioDecoderTest.kt
git commit -m "feat: add RawAudioDecoder for headerless PCM format/duration calculation"
```

---

## Task 2: `RawAudioOpenDialog.kt`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/RawAudioOpenDialog.kt`

**Interfaces:**
- Consumes (from Task 1): `RawAudioFormat`, `RawAudioByteOrder`, `RawAudioParams`, `computeRawAudioDuration`.
- Produces (consumed by Task 3): `@Composable fun RawAudioOpenDialog(file: File, onConfirm: (params: RawAudioParams) -> Unit, onCancel: () -> Unit)`.

- [ ] **Step 1: Create the dialog**

Create `app/src/main/kotlin/com/multiviewer/ui/RawAudioOpenDialog.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

private val SAMPLE_RATE_PRESETS = listOf(8000, 16000, 22050, 44100, 48000, 96000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RawAudioOpenDialog(
    file: File,
    onConfirm: (params: RawAudioParams) -> Unit,
    onCancel: () -> Unit,
) {
    var sampleRateText by remember { mutableStateOf("44100") }
    var channelsText by remember { mutableStateOf("2") }
    var format by remember { mutableStateOf(RawAudioFormat.S16) }
    var byteOrder by remember { mutableStateOf(RawAudioByteOrder.LITTLE_ENDIAN) }
    var offsetText by remember { mutableStateOf("0") }

    val sampleRate = sampleRateText.toIntOrNull()
    val channels = channelsText.toIntOrNull()
    val offsetBytes = offsetText.toLongOrNull()
    val fileSize = remember(file) { file.length() }

    val offsetTooLarge = offsetBytes != null && offsetBytes >= fileSize
    val expectedDuration = if (sampleRate != null && sampleRate > 0 && channels != null && channels > 0 &&
        offsetBytes != null && offsetBytes >= 0 && !offsetTooLarge
    ) {
        computeRawAudioDuration(fileSize, offsetBytes, sampleRate, channels, format.bytesPerSample)
    } else {
        null
    }
    // Non-blocking: a trailing partial sample (the file doesn't end on an exact frame boundary)
    // is simply dropped during decode, same spirit as RawPixelOpenDialog's file-size mismatch
    // warning -- worth flagging to the user, not worth refusing to open over.
    val unevenFrameSize = channels != null && channels > 0 && offsetBytes != null && offsetBytes in 0 until fileSize &&
        (fileSize - offsetBytes) % (channels * format.bytesPerSample) != 0L

    val canOpen = sampleRate != null && sampleRate > 0 && channels != null && channels > 0 &&
        offsetBytes != null && offsetBytes >= 0 && !offsetTooLarge

    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(AppColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                .padding(20.dp),
        ) {
            Text("Raw PCM 오디오 열기", style = AppTypography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(file.name, style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
            Spacer(Modifier.height(16.dp))

            Text("샘플 포맷", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RawAudioFormat.entries.forEach { candidate ->
                    val selected = format == candidate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                            .clickable { format = candidate }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (selected) "●" else "○",
                            fontSize = 10.sp,
                            color = if (selected) AppColors.NeonBlue else AppColors.TextSecondary,
                        )
                        Text(
                            candidate.label,
                            fontSize = 10.sp,
                            color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (format.needsByteOrder) {
                Text("Byte order", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
                Spacer(Modifier.height(4.dp))
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RawAudioByteOrder.entries.forEach { candidate ->
                        val selected = byteOrder == candidate
                        Box(
                            modifier = Modifier
                                .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                                .clickable { byteOrder = candidate }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                candidate.label,
                                fontSize = 10.sp,
                                color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text("샘플레이트 (Hz)", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SAMPLE_RATE_PRESETS.forEach { preset ->
                    val selected = sampleRateText == preset.toString()
                    Box(
                        modifier = Modifier
                            .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                            .clickable { sampleRateText = preset.toString() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(preset.toString(), fontSize = 10.sp, color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sampleRateText,
                    onValueChange = { sampleRateText = it.filter(Char::isDigit) },
                    label = { Text("샘플레이트") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = channelsText,
                    onValueChange = { channelsText = it.filter(Char::isDigit) },
                    label = { Text("채널 수") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it.filter(Char::isDigit) },
                label = { Text("건너뛸 오프셋 (bytes)") },
                singleLine = true,
                modifier = Modifier.width(200.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (offsetTooLarge) {
                Text(
                    "오프셋이 파일 크기보다 크거나 같습니다 -- 재생 가능한 데이터가 없습니다.",
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonRed),
                )
                Spacer(Modifier.height(8.dp))
            } else if (expectedDuration != null) {
                Text(
                    "예상 재생시간: ${"%.2f".format(expectedDuration)}초 ($fileSize bytes)",
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen),
                )
                if (unevenFrameSize) {
                    Text(
                        "⚠ 파일 크기가 프레임 경계에 정확히 맞지 않습니다 -- 마지막 불완전한 샘플은 무시됩니다.",
                        style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonYellow),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onConfirm(RawAudioParams(sampleRate!!, channels!!, format, byteOrder, offsetBytes!!))
                    },
                    enabled = canOpen,
                ) { Text("열기") }
            }
        }
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL (this file isn't called from anywhere yet -- Task 3 wires it in -- but it must compile standalone).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/RawAudioOpenDialog.kt
git commit -m "feat: add RawAudioOpenDialog for collecting raw PCM parameters"
```

---

## Task 3: `AppState`/`Main.kt` wiring -- `.pcm`/`.raw` routing, chooser, tab creation

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes (from Tasks 1-2): `RawAudioParams`, `computeRawAudioDuration`, `RawAudioOpenDialog`.
- Produces (consumed by Task 4): `TabState.rawAudioParams: RawAudioParams?`.

- [ ] **Step 1: Add the new extension list, pending-file fields, and `TabState.rawAudioParams`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

```kotlin
private val RAW_PIXEL_EXTENSIONS = listOf("raw", "rgb", "rgba", "yuv")
```

Replace with:

```kotlin
private val RAW_PIXEL_EXTENSIONS = listOf("raw", "rgb", "rgba", "yuv")
private val RAW_AUDIO_EXTENSIONS = listOf("pcm")
```

Find:

```kotlin
    // Headerless raw pixel dumps carry no width/height/format of their own -- openFile() routes
    // them here instead of the normal parse flow, and RawPixelOpenDialog (shown while this is
    // non-null) collects the parameters needed to actually decode the file.
    var pendingRawPixelFile: File? by mutableStateOf(null)
```

Replace with:

```kotlin
    // Headerless raw pixel dumps carry no width/height/format of their own -- openFile() routes
    // them here instead of the normal parse flow, and RawPixelOpenDialog (shown while this is
    // non-null) collects the parameters needed to actually decode the file.
    var pendingRawPixelFile: File? by mutableStateOf(null)
    // Same idea for headerless raw PCM audio -- RawAudioOpenDialog collects sample rate/channels/
    // format/byte order/offset while this is non-null.
    var pendingRawAudioFile: File? by mutableStateOf(null)
    // .raw is ambiguous between the raw-pixel and raw-audio features (both use it in the wild) --
    // openFile() routes a .raw file here first so Main.kt can show a two-button chooser, which
    // then sets pendingRawPixelFile or pendingRawAudioFile based on the user's answer.
    var pendingRawFileChoice: File? by mutableStateOf(null)
```

Find:

```kotlin
    fun cancelRawPixelFile() {
        pendingRawPixelFile = null
    }
```

Replace with:

```kotlin
    fun cancelRawPixelFile() {
        pendingRawPixelFile = null
    }

    fun chooseRawFileAsPixel() {
        val file = pendingRawFileChoice ?: return
        pendingRawFileChoice = null
        pendingRawPixelFile = file
    }

    fun chooseRawFileAsAudio() {
        val file = pendingRawFileChoice ?: return
        pendingRawFileChoice = null
        pendingRawAudioFile = file
    }

    fun cancelRawFileChoice() {
        pendingRawFileChoice = null
    }

    fun cancelRawAudioFile() {
        pendingRawAudioFile = null
    }

    fun confirmRawAudioFile(params: RawAudioParams) {
        val file = pendingRawAudioFile ?: return
        pendingRawAudioFile = null
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        if (tabs.size >= MAX_OPEN_FILES) {
            statusMessage = "You can only have $MAX_OPEN_FILES files open at a time."
            return
        }
        statusMessage = null
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        val duration = computeRawAudioDuration(
            file.length(), params.offsetBytes, params.sampleRate, params.channels, params.format.bytesPerSample,
        )
        tab.type = MediaType.AUDIO
        tab.rawAudioParams = params
        tab.root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = file.length(),
            children = listOf(
                BoxNode(
                    type = "RawAudioData", offset = 0, headerSize = 0, size = file.length(),
                    fields = listOf(
                        BoxField("Sample Rate", "${params.sampleRate} Hz", 0, file.length()),
                        BoxField("Channels", params.channels.toString(), 0, file.length()),
                        BoxField("Format", params.format.label, 0, file.length()),
                        BoxField("Byte Order", params.byteOrder.label, 0, file.length()),
                        BoxField("Offset", "${params.offsetBytes} bytes", 0, file.length()),
                        BoxField("Duration", "%.2f seconds".format(duration), 0, file.length()),
                    ),
                    summary = "${params.sampleRate} Hz, ${params.channels}ch, ${params.format.label}",
                ),
            ),
        )
        tab.isLoading = false
    }
```

- [ ] **Step 2: Route `.raw`/`.pcm` in `openFile`**

In the same file, find:

```kotlin
        val extension = file.extension.lowercase()
        if (extension in RAW_PIXEL_EXTENSIONS) {
            statusMessage = null
            pendingRawPixelFile = file
            return
        }
        // Reject anything else outright rather than falling through to parseFile(): its magic-byte
        // dispatch (ParseFile.kt) has no "unrecognized" case for the box-parser branch, so an
        // arbitrary non-media file would previously be handed to the MP4/MOV box walker and parsed
        // as if it might be one -- undefined behavior on genuinely arbitrary bytes, not something
        // this app can promise won't hang or crash. See README's "Supported Specs & Limits".
        if (extension !in IMAGE_EXTENSIONS && extension !in VIDEO_EXTENSIONS && extension !in AUDIO_EXTENSIONS) {
            val supported = (IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + RAW_PIXEL_EXTENSIONS).joinToString(", ")
            openFileError = "지원하지 않는 파일 형식입니다 (.$extension).\n지원 형식: $supported"
            return
        }
```

Replace with:

```kotlin
        val extension = file.extension.lowercase()
        // .raw is ambiguous with raw-audio (both use this extension in the wild) -- checked before
        // RAW_PIXEL_EXTENSIONS below so it goes through the chooser instead of assuming pixel data.
        if (extension == "raw") {
            statusMessage = null
            pendingRawFileChoice = file
            return
        }
        if (extension in RAW_PIXEL_EXTENSIONS) {
            statusMessage = null
            pendingRawPixelFile = file
            return
        }
        if (extension in RAW_AUDIO_EXTENSIONS) {
            statusMessage = null
            pendingRawAudioFile = file
            return
        }
        // Reject anything else outright rather than falling through to parseFile(): its magic-byte
        // dispatch (ParseFile.kt) has no "unrecognized" case for the box-parser branch, so an
        // arbitrary non-media file would previously be handed to the MP4/MOV box walker and parsed
        // as if it might be one -- undefined behavior on genuinely arbitrary bytes, not something
        // this app can promise won't hang or crash. See README's "Supported Specs & Limits".
        if (extension !in IMAGE_EXTENSIONS && extension !in VIDEO_EXTENSIONS && extension !in AUDIO_EXTENSIONS) {
            val supported = (IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + RAW_PIXEL_EXTENSIONS + RAW_AUDIO_EXTENSIONS).joinToString(", ")
            openFileError = "지원하지 않는 파일 형식입니다 (.$extension).\n지원 형식: $supported"
            return
        }
```

- [ ] **Step 3: Add `TabState.rawAudioParams`**

In the same file, find:

```kotlin
    var largeResolutionWarning: String? by mutableStateOf(null)
}
```

Replace with:

```kotlin
    var largeResolutionWarning: String? by mutableStateOf(null)

    // Headerless raw PCM audio parameters (see RawAudioOpenDialog) -- null unless this tab was
    // opened via the raw-audio path. FfmpegAudioPlayer uses this to supply ffmpeg the input-side
    // format hints raw PCM can't self-describe.
    var rawAudioParams: RawAudioParams? by mutableStateOf(null)
}
```

- [ ] **Step 4: Wire the chooser and `RawAudioOpenDialog` into `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
            appState.pendingRawPixelFile?.let { pendingFile ->
                RawPixelOpenDialog(
                    file = pendingFile,
                    onConfirm = { width, height, format, byteOrder, fps -> appState.confirmRawPixelFile(width, height, format, byteOrder, fps) },
                    onCancel = { appState.cancelRawPixelFile() },
                )
            }
```

Replace with:

```kotlin
            appState.pendingRawFileChoice?.let { pendingFile ->
                Dialog(onDismissRequest = { appState.cancelRawFileChoice() }) {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                    ) {
                        Text("파일 종류 선택", style = AppTypography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(pendingFile.name, style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            ".raw 파일은 이미지 또는 오디오일 수 있습니다. 어느 쪽인가요?",
                            style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary),
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { appState.cancelRawFileChoice() }) { Text("취소") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { appState.chooseRawFileAsAudio() }) { Text("오디오") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { appState.chooseRawFileAsPixel() }) { Text("이미지") }
                        }
                    }
                }
            }
            appState.pendingRawPixelFile?.let { pendingFile ->
                RawPixelOpenDialog(
                    file = pendingFile,
                    onConfirm = { width, height, format, byteOrder, fps -> appState.confirmRawPixelFile(width, height, format, byteOrder, fps) },
                    onCancel = { appState.cancelRawPixelFile() },
                )
            }
            appState.pendingRawAudioFile?.let { pendingFile ->
                RawAudioOpenDialog(
                    file = pendingFile,
                    onConfirm = { params -> appState.confirmRawAudioFile(params) },
                    onCancel = { appState.cancelRawAudioFile() },
                )
            }
```

- [ ] **Step 5: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. `Dialog`/`Column`/`Row`/`Button`/`TextButton`/`Text`/`Spacer`/`background`/`border`/`RoundedCornerShape` are already used elsewhere in `Main.kt`'s existing `openFileError` dialog, and `com.multiviewer.ui.*`/`androidx.compose.material3.*` are already wildcard-imported there -- no new imports should be needed.

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: wire raw PCM audio open flow into AppState and the .raw chooser dialog"
```

---

## Task 4: Thread `rawAudioParams` through playback, waveform, and spectrogram

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt`

**Interfaces:**
- Consumes (from Tasks 1, 3): `RawAudioParams`, `RawAudioParams.ffmpegFormatCode()`, `computeRawAudioDuration`, `rawAudioSourceFile`, `TabState.rawAudioParams`.

- [ ] **Step 1: Add `rawAudioParams` to `FfmpegAudioPlayer`'s signature and probe/waveform setup**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
@Composable
fun FfmpegAudioPlayer(file: File, modifier: Modifier = Modifier) {
```

Replace with:

```kotlin
@Composable
fun FfmpegAudioPlayer(file: File, rawAudioParams: RawAudioParams? = null, modifier: Modifier = Modifier) {
```

Find:

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info) }
        }
    }
```

Replace with:

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) {
            if (rawAudioParams != null) {
                AudioFileInfo(
                    sampleRate = rawAudioParams.sampleRate,
                    channels = rawAudioParams.channels,
                    duration = computeRawAudioDuration(
                        file.length(), rawAudioParams.offsetBytes, rawAudioParams.sampleRate,
                        rawAudioParams.channels, rawAudioParams.format.bytesPerSample,
                    ),
                )
            } else {
                probeAudioFormat(file)
            }
        }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info, rawAudioParams = rawAudioParams) }
        }
    }
```

- [ ] **Step 2: Prepend raw-format input flags to the playback `ProcessBuilder`**

In the same file, find:

```kotlin
    DisposableEffect(file, restartTrigger) {
        playedSeconds = 0.0
        val seekSeconds = startFromSeconds
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val sampleRate = info.sampleRate
        val channels = info.channels
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
                    "-i", file.absolutePath, "-map", "0:a:0",
                    "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
                    "-acodec", "pcm_s16le", "-",
                ),
            ).also { FfmpegLocator.configureEnvironment(it) }.start()
        } catch (e: Exception) {
            null
        }
```

Replace with:

```kotlin
    DisposableEffect(file, restartTrigger) {
        playedSeconds = 0.0
        val seekSeconds = startFromSeconds
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val sampleRate = info.sampleRate
        val channels = info.channels
        val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + rawInputArgs + listOf(
                    "-i", inputFile.absolutePath, "-map", "0:a:0",
                    "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
                    "-acodec", "pcm_s16le", "-",
                ),
            ).also { FfmpegLocator.configureEnvironment(it) }.start()
        } catch (e: Exception) {
            null
        }
```

- [ ] **Step 3: Thread `rawAudioParams` into the spectrogram `LaunchedEffect`**

In the same file, find:

```kotlin
    LaunchedEffect(file, spectrogramBoxSize) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, boxSize.width, boxSize.height) }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }
```

Replace with:

```kotlin
    LaunchedEffect(file, spectrogramBoxSize) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) {
            generateSpectrogramImage(file, boxSize.width, boxSize.height, rawAudioParams = rawAudioParams)
        }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }
```

- [ ] **Step 4: Add `rawAudioParams` to `renderAudioVisualization`/`generateSpectrogramImage`**

In the same file, find:

```kotlin
private fun renderAudioVisualization(file: File, filter: String): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
            "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        tempPng.delete()
    }
}

fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}")
```

Replace with:

```kotlin
private fun renderAudioVisualization(file: File, filter: String, rawAudioParams: RawAudioParams? = null): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    return try {
        val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        val process = ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath(), "-y") + rawInputArgs + listOf(
                "-i", inputFile.absolutePath,
                "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
            ),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        tempPng.delete()
    }
}

fun generateSpectrogramImage(file: File, width: Int, height: Int, rawAudioParams: RawAudioParams? = null): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}", rawAudioParams)
```

- [ ] **Step 5: Add `rawAudioParams` to `computeWaveformPeaks`**

In `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`, find:

```kotlin
fun computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT): WaveformPeaks? {
    val channels = info.channels
    if (channels <= 0 || bucketCount <= 0) return null

    val frameSizeBytes = channels * 2
    val estimatedTotalFrames = (info.duration * info.sampleRate).toLong().coerceAtLeast(1L)
    val framesPerBucket = (estimatedTotalFrames / bucketCount).coerceAtLeast(1L)

    val minPerChannel = Array(channels) { FloatArray(bucketCount) { Float.MAX_VALUE } }
    val maxPerChannel = Array(channels) { FloatArray(bucketCount) { -Float.MAX_VALUE } }

    val process = try {
        ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-i", file.absolutePath, "-map", "0:a:0",
            "-f", "s16le", "-ar", info.sampleRate.toString(), "-ac", channels.toString(),
            "-acodec", "pcm_s16le", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
    } catch (e: Exception) {
        return null
    }
```

Replace with:

```kotlin
fun computeWaveformPeaks(
    file: File,
    info: AudioFileInfo,
    bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT,
    rawAudioParams: RawAudioParams? = null,
): WaveformPeaks? {
    val channels = info.channels
    if (channels <= 0 || bucketCount <= 0) return null

    val frameSizeBytes = channels * 2
    val estimatedTotalFrames = (info.duration * info.sampleRate).toLong().coerceAtLeast(1L)
    val framesPerBucket = (estimatedTotalFrames / bucketCount).coerceAtLeast(1L)

    val minPerChannel = Array(channels) { FloatArray(bucketCount) { Float.MAX_VALUE } }
    val maxPerChannel = Array(channels) { FloatArray(bucketCount) { -Float.MAX_VALUE } }

    val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
    val rawInputArgs = if (rawAudioParams != null) {
        listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
    } else {
        emptyList()
    }
    val process = try {
        ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath()) + rawInputArgs + listOf(
                "-i", inputFile.absolutePath, "-map", "0:a:0",
                "-f", "s16le", "-ar", info.sampleRate.toString(), "-ac", channels.toString(),
                "-acodec", "pcm_s16le", "-",
            ),
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
    } catch (e: Exception) {
        return null
    }
```

- [ ] **Step 6: Wire `tab.rawAudioParams` through in `AudioInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt`, find:

```kotlin
                FfmpegAudioPlayer(tab.file, modifier = Modifier.weight(verticalSplit).fillMaxWidth())
```

Replace with:

```kotlin
                FfmpegAudioPlayer(tab.file, rawAudioParams = tab.rawAudioParams, modifier = Modifier.weight(verticalSplit).fillMaxWidth())
```

- [ ] **Step 7: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt
git commit -m "feat: thread raw PCM format hints through playback, waveform, and spectrogram"
```

---

## Task 5: Controller-performed manual verification

This task has no subagent dispatch -- run it directly in the controlling session, matching this project's established precedent for real runtime/manual verification.

- [ ] **Step 1: Generate a real raw PCM test file**

```bash
ffmpeg -f lavfi -i "sine=frequency=440:duration=3" -f s16le -ar 44100 -ac 1 /tmp/test-tone.pcm
```

This produces a 3-second, 44100 Hz, mono, 16-bit signed little-endian sine wave with no header -- exactly the kind of file this feature targets.

- [ ] **Step 2: Run the app and open the test file**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

Open `/tmp/test-tone.pcm`.

- [ ] **Step 3: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- Opening the `.pcm` file goes straight to `RawAudioOpenDialog` (no chooser, since `.pcm` is unambiguous).
- Entering sample rate `44100`, channels `1`, format `16-bit signed`, byte order `Little-endian`, offset `0` shows a live "예상 재생시간: 3.00초" preview matching the known 3-second source.
- Confirming opens a tab using the existing audio inspector layout (waveform + spectrogram + playback controls) -- same visual layout as any other supported audio file.
- Playback produces audible sound (a clean 440Hz tone) and the waveform/spectrogram both render (not blank/error states).
- The structure tree panel shows a `RawAudioData` node with the entered parameters as fields.
- Open a `.raw` file (any raw pixel dump used earlier in this session works, or generate one) -- confirm the "이미지 / 오디오" chooser appears, and picking "이미지" reaches the existing `RawPixelOpenDialog` unchanged.
- Open any regular supported audio file (e.g. an existing `.wav`/`.mp3`) in another tab -- confirm playback/waveform/spectrogram are completely unaffected (no regression for the non-raw path).

- [ ] **Step 4: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1-4 commit ranges and the outcome of this manual verification (pass, or any issues found and how they were resolved).
