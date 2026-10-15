# AI 대화 — 비디오 선택 프레임 첨부 + 정확한 프레임 이동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 질문을 보낼 때 비디오 탭의 선택 프레임을 원본 해상도로 정확히 디코딩해 첨부하고, 시작 시간이 0이 아닌 영상(TS 등)에서 팝업/플레이어가 선택 프레임으로 정확히 이동하게 한다.

**Architecture:** 새 `ExactFrameDecoder.kt`가 절대 PTS로 프레임 하나를 `-copyts` + 3초 앞당긴 `-ss` + `select=gte(t,PTS)` + `showinfo`로 디코딩하고 출력 PTS를 검증한다. 플레이어는 공개 값(절대 PTS)과 내부 위치(파일 시작 기준 상대 초)를 경계에서만 변환하고, start_time이 0이면 ffmpeg 인자를 지금과 똑같이 둔다. 컨트롤러는 비디오 탭에서 이 디코더의 결과를 하위 프로젝트 1의 확인·첨부 경로에 넣는다.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg/ffprobe(외부 프로세스), JUnit5 + kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-ai-video-frame-design.md`

## Global Constraints

- 외부 프로세스: `readProcessOutputWithTimeout`(`ui/ProcessOutputReader.kt`)로 타임아웃 강제, 취소(인터럽트) 시 종료 (AGENTS.md 규칙 5).
- 거짓 프레임 금지: 디코딩된 PTS가 선택 PTS와 `max(0.001, 프레임길이/2)` 넘게 다르면 첨부하지 않는다 (규칙 7).
- 필터 문자열에 경로를 넣지 않는다 (규칙 4). 숫자는 `String.format(Locale.US, "%.6f", x)` (지수 표기 금지).
- start_time이 0(±0.001)인 파일의 플레이어 ffmpeg 인자는 지금과 바이트 단위로 같아야 한다.
- 새 의존성 금지. 사용자 문구는 KO/EN (`label`).
- Fixture는 실제 ffmpeg로 생성하고 생성 스크립트·출처를 `docs/validation/video-frame-fixtures/`에 둔다 (규칙 1). ffmpeg 테스트는 `assumeTrue(ffmpegAvailable())`.
- 작업 위치: worktree `.worktrees/ai-video-frame`, 브랜치 `feat/ai-video-frame`. 커밋 전 `git -C <worktree> branch --show-current` 확인.
- 커밋 trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## File Structure

| 파일 | 역할 |
|---|---|
| `docs/validation/video-frame-fixtures/generate.sh`, `README.md` (신규) | fixture 생성·출처 |
| `app/src/test/resources/video-frame/{default.mp4,offset.ts,hevc.mp4,vfr.mkv}` (신규) | 실측 fixture |
| `app/src/main/kotlin/com/multiviewer/ui/ExactFrameDecoder.kt` (신규) | 정확한 단일 프레임 디코딩 |
| `app/src/test/kotlin/com/multiviewer/ui/ExactFrameDecoderTest.kt` (신규) | 디코더 테스트 |
| `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt` (수정) | `VideoInfo.startTime`, `playerSeek`, 경계 변환 |
| `app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt` (수정) | 플레이어 seek 테스트 |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt` (수정) | 비디오 프레임 첨부 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt` (수정) | 컨트롤러 테스트 |

---

### Task 0: Worktree (controller)

```bash
cd /Users/dong.kim/AndroidStudioProjects/multiViewer
git worktree add .worktrees/ai-video-frame -b feat/ai-video-frame main
```

---

### Task 1: Fixture + 정확한 프레임 디코더

**Files:** Create `docs/validation/video-frame-fixtures/generate.sh`, `docs/validation/video-frame-fixtures/README.md`, `app/src/test/resources/video-frame/*`, `app/src/main/kotlin/com/multiviewer/ui/ExactFrameDecoder.kt`, `app/src/test/kotlin/com/multiviewer/ui/ExactFrameDecoderTest.kt`

**Interfaces (Produces):**
```kotlin
data class FrameStreamInfo(val startTime: Double, val colorTransfer: String?)
fun parseFrameStreamInfo(lines: List<String>): FrameStreamInfo
fun probeFrameStreamInfo(file: File): FrameStreamInfo
fun exactFrameArgs(ffmpeg: String, file: String, ptsSeconds: Double, startTime: Double): List<String>  // output path appended by caller
fun parseShowinfoPts(stderr: String): Double?
sealed interface ExactFrameResult {
    data class Frame(val image: java.awt.image.BufferedImage, val ptsSeconds: Double, val hdr: Boolean) : ExactFrameResult
    data class Failed(val reason: String) : ExactFrameResult
}
fun decodeExactFrame(file: File, ptsSeconds: Double, info: FrameStreamInfo, frameDurationSeconds: Double, timeoutSeconds: Long = 15): ExactFrameResult
fun frameDurationAround(frame: FrameInfo, frames: List<FrameInfo>?): Double
fun decodeSelectedFrame(file: File, frame: FrameInfo, frames: List<FrameInfo>?): ExactFrameResult
```
(package `com.multiviewer.ui`; `FrameInfo(index, type, sizeBytes, ptsSeconds, byteOffset)`는 `FrameTypeAnalyzer.kt`에 있음.)

- [ ] **Step 1: Fixture 생성 스크립트**

`docs/validation/video-frame-fixtures/generate.sh`:
```bash
#!/bin/sh
# Regenerates app/src/test/resources/video-frame/. Verified layouts: see README.md.
set -e
OUT="$(cd "$(dirname "$0")/../../.." && pwd)/app/src/test/resources/video-frame"
mkdir -p "$OUT"
SRC="testsrc2=size=160x90:rate=30:duration=2"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx264 -bf 2 -g 30 "$OUT/default.mp4"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx264 -bf 2 -g 30 -output_ts_offset 10 "$OUT/offset.ts"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx265 -x265-params log-level=error:bframes=3 "$OUT/hevc.mp4"
ffmpeg -v error -y -f lavfi -i "$SRC" -vf "setpts='PTS+if(gt(N,20),0.2/TB,0)'" -fps_mode vfr -c:v libx264 -bf 2 "$OUT/vfr.mkv"
for f in default.mp4 offset.ts hevc.mp4 vfr.mkv; do
  echo "$f start_time=$(ffprobe -v error -show_entries format=start_time -of csv=p=0 "$OUT/$f")"
done
```
`README.md`: 목적(절대 PTS 정확 탐색 검증), 생성 명령, 각 파일의 특성(start 0 / start 11.4 TS / HEVC B-frame / VFR 간격), 기대 start_time, 그리고 spec의 실측 표 요약(`-ss PTS -i`는 offset.ts에서 불일치·플레이어 0 프레임; copyts+preroll+select는 전부 일치). 기본 설정 fixture(`default.mp4`, ffmpeg 기본 MP4 HEVC 태그 등)를 둔 이유(규칙 2)도 한 줄.

Run: `sh docs/validation/video-frame-fixtures/generate.sh` (worktree 루트). 출력된 `offset.ts start_time`이 11.4 근처(≠0)인지 확인해 README에 실제 값을 적는다. 각 파일 크기를 `ls -l`로 확인(수십 KB 이하).

- [ ] **Step 2: 실패하는 테스트 — ExactFrameDecoderTest.kt**

```kotlin
package com.multiviewer.ui

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExactFrameDecoderTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    private fun fixture(name: String) = File(javaClass.getResource("/video-frame/$name")!!.toURI())

    private fun ffprobeFramePts(file: File): List<Double> {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
            "-of", "csv=p=0", file.absolutePath).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readLines().mapNotNull { it.trim().trimEnd(',').toDoubleOrNull() }
    }

    private fun referenceFrame(file: File, n: Int): IntArray {
        val out = Files.createTempFile("ref", ".png").toFile()
        try {
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", file.absolutePath, "-vf", "select=eq(n\\,$n)",
                "-frames:v", "1", out.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readAllBytes(); assertEquals(0, p.waitFor())
            val img = ImageIO.read(out)
            return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        } finally { out.delete() }
    }

    @Test
    fun `stream info parses start time and transfer, N_A is zero`() {
        assertEquals(FrameStreamInfo(11.4, "bt709"), parseFrameStreamInfo(listOf("color_transfer=bt709", "start_time=11.400000")))
        assertEquals(FrameStreamInfo(0.0, null), parseFrameStreamInfo(listOf("color_transfer=unknown", "start_time=N/A")))
    }

    @Test
    fun `args seek three seconds early with copyts and select the exact pts, no exponent`() {
        val args = exactFrameArgs("ffmpeg", "/v/a b.ts", 12.9, 11.4)
        assertEquals(listOf("ffmpeg", "-v", "info", "-copyts", "-ss", "0.000000", "-i", "/v/a b.ts", "-map", "0:v:0",
            "-vf", "select=gte(t\\,12.899500),showinfo", "-frames:v", "1", "-y"), args)
        assertEquals("0.000100", exactFrameArgs("ffmpeg", "f", 3.0001, 0.0)[5])
        assertEquals("select=gte(t\\,-0.000400),showinfo", exactFrameArgs("ffmpeg", "f", 0.0001, 0.0)[11])
    }

    @Test
    fun `showinfo pts is the first pts_time`() {
        val err = "[Parsed_showinfo_1 @ 0x1] n:   0 pts: 1161000 pts_time:12.9 duration: 3003\n[Parsed_showinfo_1 @ 0x1] n:   1 pts_time:12.933333"
        assertEquals(12.9, parseShowinfoPts(err))
        assertEquals(null, parseShowinfoPts("no frames"))
    }

    @Test
    fun `selected frame decodes to exactly that frame on every fixture`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        for (name in listOf("default.mp4", "offset.ts", "hevc.mp4", "vfr.mkv")) {
            val file = fixture(name)
            val pts = ffprobeFramePts(file)
            val info = probeFrameStreamInfo(file)
            if (name == "offset.ts") assertTrue(info.startTime > 1.0, "offset.ts start ${info.startTime}")
            for (n in listOf(0, 1, 2, 29, 30, 31, pts.size - 1)) {
                val result = decodeExactFrame(file, pts[n], info, frameDurationSeconds = 1.0 / 30)
                assertIs<ExactFrameResult.Frame>(result, "$name #$n")
                assertEquals(pts[n], result.ptsSeconds, 1e-6, "$name #$n")
                val img = result.image
                assertTrue(referenceFrame(file, n).contentEquals(img.getRGB(0, 0, img.width, img.height, null, 0, img.width)), "$name #$n pixels")
            }
        }
    }

    @Test
    fun `a pts between frames is refused, not rounded to a neighbour`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val file = fixture("offset.ts")
        val pts = ffprobeFramePts(file)
        val between = (pts[10] + pts[11]) / 2
        val result = decodeExactFrame(file, between, probeFrameStreamInfo(file), frameDurationSeconds = 0.001)
        assertIs<ExactFrameResult.Failed>(result)
    }

    @Test
    fun `frame duration comes from the neighbouring frame`() {
        val frames = listOf(FrameInfo(0, 'I', 1, 1.0), FrameInfo(1, 'P', 1, 1.04), FrameInfo(2, 'B', 1, 1.1))
        assertEquals(0.04, frameDurationAround(frames[0], frames), 1e-9)
        assertEquals(0.06, frameDurationAround(frames[2], frames), 1e-9)
        assertEquals(1.0 / 30, frameDurationAround(FrameInfo(5, 'I', 1, 2.0), null), 1e-9)
    }
}
```

(테스트의 `exactFrameArgs` 첫 단언: pts 12.9, start 11.4 → `-ss max(0, 12.9−11.4−3)=0.000000`, select 값 `12.9−0.0005=12.899500`. 두 번째: pts 3.0001, start 0 → `-ss 0.000100`. 세 번째: 아주 작은 PTS도 지수 표기 없이 고정 소수 6자리.)

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ExactFrameDecoderTest'`
Expected: 컴파일 실패 — `Unresolved reference: exactFrameArgs` 등

- [ ] **Step 4: `ExactFrameDecoder.kt` 구현**

```kotlin
package com.multiviewer.ui

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

// Exact single-frame decoding by ABSOLUTE stream PTS (ffprobe frame=pts_time, as FrameInfo carries it).
// Measured (docs/validation/video-frame-fixtures): plain `-ss PTS -i` lands on another frame once the
// stream's start_time is not 0 (MPEG-TS), so this keeps timestamps absolute (-copyts), seeks a little
// early (fast, keyframe-aligned) and lets `select` pick the first frame at/after PTS; showinfo reports
// which frame actually came out, and a different one is refused rather than shown as the selected frame.

private const val SEEK_PREROLL_SECONDS = 3.0
private const val SELECT_EPSILON_SECONDS = 0.0005
private val SHOWINFO_PTS = Regex("""pts_time:\s*(-?[0-9]+(?:\.[0-9]+)?)""")
private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")

internal fun fmt6(x: Double): String = String.format(Locale.US, "%.6f", x)

data class FrameStreamInfo(val startTime: Double, val colorTransfer: String?)

fun parseFrameStreamInfo(lines: List<String>): FrameStreamInfo {
    var start = 0.0
    var transfer: String? = null
    for (line in lines) {
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val value = line.substring(eq + 1).trim()
        when (line.substring(0, eq).trim()) {
            "start_time" -> start = value.toDoubleOrNull() ?: 0.0
            "color_transfer" -> transfer = value.takeIf { it.isNotEmpty() && it != "unknown" }
        }
    }
    return FrameStreamInfo(start, transfer)
}

/** One cheap ffprobe; on any failure start_time is taken as 0 (the PTS check in [decodeExactFrame] still guards). */
fun probeFrameStreamInfo(file: File): FrameStreamInfo {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "format=start_time:stream=color_transfer", "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
        parseFrameStreamInfo(lines.orEmpty())
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        FrameStreamInfo(0.0, null)
    }
}

/** ffmpeg arguments up to (not including) the output path. */
fun exactFrameArgs(ffmpeg: String, file: String, ptsSeconds: Double, startTime: Double): List<String> = listOf(
    ffmpeg, "-v", "info", "-copyts",
    "-ss", fmt6(max(0.0, ptsSeconds - startTime - SEEK_PREROLL_SECONDS)),
    "-i", file, "-map", "0:v:0",
    "-vf", "select=gte(t\\,${fmt6(ptsSeconds - SELECT_EPSILON_SECONDS)}),showinfo",
    "-frames:v", "1", "-y",
)

fun parseShowinfoPts(stderr: String): Double? = SHOWINFO_PTS.find(stderr)?.groupValues?.get(1)?.toDoubleOrNull()

sealed interface ExactFrameResult {
    data class Frame(val image: BufferedImage, val ptsSeconds: Double, val hdr: Boolean) : ExactFrameResult
    data class Failed(val reason: String) : ExactFrameResult
}

/** Blocking (ffmpeg); interruption kills the process and propagates. */
fun decodeExactFrame(
    file: File,
    ptsSeconds: Double,
    info: FrameStreamInfo,
    frameDurationSeconds: Double,
    timeoutSeconds: Long = 15,
): ExactFrameResult {
    val out = try {
        File.createTempFile("exact-frame-", ".png")
    } catch (e: Exception) {
        return ExactFrameResult.Failed("temp file: ${e.message}")
    }
    try {
        val process = try {
            ProcessBuilder(exactFrameArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds, info.startTime) + out.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
        } catch (e: Exception) {
            return ExactFrameResult.Failed("ffmpeg did not start: ${e.message}")
        }
        val stderr = readProcessOutputWithTimeout(process, timeoutSeconds) { process.errorStream.bufferedReader().readText() }
            ?: return ExactFrameResult.Failed("ffmpeg timed out after $timeoutSeconds s")
        if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return ExactFrameResult.Failed("ffmpeg failed")
        }
        val decoded = parseShowinfoPts(stderr) ?: return ExactFrameResult.Failed("no frame at or after PTS ${fmt6(ptsSeconds)}")
        val tolerance = max(0.001, frameDurationSeconds / 2)
        if (abs(decoded - ptsSeconds) > tolerance) {
            return ExactFrameResult.Failed("decoded PTS ${fmt6(decoded)} ≠ selected ${fmt6(ptsSeconds)}")
        }
        val image = ImageIO.read(out) ?: return ExactFrameResult.Failed("unreadable frame image")
        return ExactFrameResult.Frame(image, decoded, info.colorTransfer in HDR_TRANSFERS)
    } finally {
        out.delete()
    }
}

/** Duration of [frame]: gap to the next frame, else to the previous one, else 1/30 s. */
fun frameDurationAround(frame: FrameInfo, frames: List<FrameInfo>?): Double {
    val sorted = frames?.sortedBy { it.ptsSeconds }.orEmpty()
    val at = sorted.indexOfFirst { it.index == frame.index }
    val next = sorted.getOrNull(at + 1)?.takeIf { at >= 0 }
    val prev = sorted.getOrNull(at - 1)?.takeIf { at >= 1 }
    return when {
        next != null -> next.ptsSeconds - frame.ptsSeconds
        prev != null -> frame.ptsSeconds - prev.ptsSeconds
        else -> 1.0 / 30
    }
}

/** The AI's view of a selected frame: stream info + exact decode. Blocking. */
fun decodeSelectedFrame(file: File, frame: FrameInfo, frames: List<FrameInfo>?): ExactFrameResult =
    decodeExactFrame(file, frame.ptsSeconds, probeFrameStreamInfo(file), frameDurationAround(frame, frames))
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ExactFrameDecoderTest'`
Expected: PASS (ffmpeg 있으면 픽셀 비교까지). 픽셀 비교가 한 파일이라도 실패하면 구현을 바꾸지 말고 실패한 `$name #$n`과 디코딩/기준 PTS를 보고한다(BLOCKED).

- [ ] **Step 6: 커밋**

```bash
git add docs/validation/video-frame-fixtures app/src/test/resources/video-frame \
  app/src/main/kotlin/com/multiviewer/ui/ExactFrameDecoder.kt app/src/test/kotlin/com/multiviewer/ui/ExactFrameDecoderTest.kt
git commit -m "feat: decode a video frame exactly by absolute PTS

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 플레이어 — 절대 PTS 경계와 정확한 시작 위치

**Files:** Modify `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`, `app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt`

**Interfaces:**
- Consumes (Task 1): `fmt6(Double)` (internal, same package), fixtures `/video-frame/*`.
- Produces:
  - `data class VideoInfo(…, val startTime: Double = 0.0)`
  - `data class PlayerSeek(val inputArgs: List<String>, val selectFilter: String?)`
  - `fun playerSeek(relSeconds: Double, startTime: Double): PlayerSeek`
  - `fun ffmpegPipeArgs(ffmpegPath: String, filePath: String, seekArgs: List<String>, selectFilter: String? = null): List<String>`

계약: 공개 값(`initialSeekSeconds`, `seekRequestSeconds`, `knownFrameTimestamps`, `onFrameTimestamps`, `onFramePresented.ptsSeconds`)은 절대 PTS, 내부(`startFromSeconds`, `playedSeconds`, `frameTimestamps`, 진행 바, 오디오)는 파일 시작 기준 상대 초.

- [ ] **Step 1: 실패하는 테스트 — FfmpegVideoPlayerTest에 추가**

```kotlin
    @Test
    fun `player seek keeps today's args when the stream starts at zero`() {
        assertEquals(PlayerSeek(emptyList(), null), playerSeek(0.0, 0.0))
        assertEquals(PlayerSeek(listOf("-ss", 1.5.toString()), null), playerSeek(1.5, 0.0))
        assertEquals(PlayerSeek(listOf("-ss", 1.5.toString()), null), playerSeek(1.5, 0.0004))
        assertEquals(PlayerSeek(emptyList(), null), playerSeek(0.0, 11.4))
    }

    @Test
    fun `player seek on an offset stream uses copyts, preroll and an absolute select`() {
        assertEquals(PlayerSeek(listOf("-copyts", "-ss", "0.000000"), "select=gte(t\\,12.899500)"), playerSeek(1.5, 11.4))
        assertEquals(PlayerSeek(listOf("-copyts", "-ss", "2.000000"), "select=gte(t\\,16.399500)"), playerSeek(5.0, 11.4))
    }

    @Test
    fun `the select filter goes first in the pipe's -vf chain`() {
        val base = ffmpegPipeArgs("ffmpeg", "/f.ts", listOf("-copyts", "-ss", "0.000000"))
        val withSelect = ffmpegPipeArgs("ffmpeg", "/f.ts", listOf("-copyts", "-ss", "0.000000"), "select=gte(t\\,1.0)")
        val vf = withSelect[withSelect.indexOf("-vf") + 1]
        assertTrue(vf.startsWith("select=gte(t\\,1.0),scale="), vf)
        assertEquals(base[base.indexOf("-vf") + 1], vf.removePrefix("select=gte(t\\,1.0),"))
    }

    @Test
    fun `an offset TS pipe started at a selected frame begins exactly there`() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false), "ffmpeg not on PATH")
        val file = java.io.File(javaClass.getResource("/video-frame/offset.ts")!!.toURI())
        val info = probeFrameStreamInfo(file)
        val pts = probeFrameTimestamps(file)!!
        for (n in listOf(0, 30, 45)) {
            val rel = pts[n] - info.startTime
            val seek = playerSeek(rel, info.startTime)
            val args = ffmpegPipeArgs("ffmpeg", file.absolutePath, seek.inputArgs, seek.selectFilter)
            val p = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = p.inputStream.readAllBytes()
            assertEquals(0, p.waitFor())
            val frameBytes = 160 * 90 * 4
            assertEquals(pts.size - n, bytes.size / frameBytes, "frames after #$n")
            val ref = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:v:0", "-vf",
                "select=eq(n\\,$n),scale='min(1280,iw)':'min(1280,ih)':force_original_aspect_ratio=decrease:flags=fast_bilinear",
                "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "bgra", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val refBytes = ref.inputStream.readAllBytes()
            ref.waitFor()
            assertTrue(refBytes.contentEquals(bytes.copyOfRange(0, frameBytes)), "first frame after seek to #$n")
        }
    }

    @Test
    fun `probeVideo reads the stream start time`() {
        assumeTrue(runCatching { ProcessBuilder("ffprobe", "-version").start().waitFor() == 0 }.getOrDefault(false), "ffprobe not on PATH")
        val ts = java.io.File(javaClass.getResource("/video-frame/offset.ts")!!.toURI())
        val mp4 = java.io.File(javaClass.getResource("/video-frame/default.mp4")!!.toURI())
        assertTrue(probeVideo(ts)!!.startTime > 1.0)
        assertEquals(0.0, probeVideo(mp4)!!.startTime, 0.001)
    }
```
(필요한 import — `org.junit.jupiter.api.Assumptions.assumeTrue`, `kotlin.test.assertTrue` — 가 파일에 없으면 추가.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.FfmpegVideoPlayerTest'`
Expected: 컴파일 실패 — `PlayerSeek`, `playerSeek`, `startTime` 미정의

- [ ] **Step 3: 구현 — VideoInfo / probeVideo**

`data class VideoInfo(...)` 줄을 다음으로 교체:
```kotlin
// startTime: the container's format start_time (non-zero for e.g. MPEG-TS). Frame PTS from ffprobe are absolute,
// the player's own positions are relative to it -- see playerSeek.
data class VideoInfo(val width: Int, val height: Int, val fps: Double, val rotation: Int = 0, val duration: Double = 0.0, val startTime: Double = 0.0)
```
`probeVideo`의 `-show_entries` 값 `"stream=width,height,avg_frame_rate,r_frame_rate,duration:stream_side_data=rotation:format=duration"`을
`"stream=width,height,avg_frame_rate,r_frame_rate,duration:stream_side_data=rotation:format=duration,start_time"`로 바꾸고,
`VideoInfo(width, height, fps, rotation, duration)`을
```kotlin
        // Only the format section is asked for start_time, so this key is the container's.
        val startTime = values["start_time"]?.toDoubleOrNull() ?: 0.0
        VideoInfo(width, height, fps, rotation, duration, startTime)
```
로 바꾼다.

- [ ] **Step 4: 구현 — playerSeek / ffmpegPipeArgs**

`fun ffmpegPipeArgs(` 바로 위에 추가:
```kotlin
/** How the playback pipe starts at [relSeconds] (seconds from the file's start). */
data class PlayerSeek(val inputArgs: List<String>, val selectFilter: String?)

// A start_time of 0 keeps the exact arguments this player always used (measured exact there). Otherwise
// `-ss rel` lands on the wrong frame (MPEG-TS, measured), so keep timestamps absolute, seek 3 s early and
// select the first frame at/after the absolute target -- see docs/validation/video-frame-fixtures.
fun playerSeek(relSeconds: Double, startTime: Double): PlayerSeek = when {
    relSeconds <= 0.0 -> PlayerSeek(emptyList(), null)
    kotlin.math.abs(startTime) < 0.001 -> PlayerSeek(listOf("-ss", relSeconds.toString()), null)
    else -> PlayerSeek(
        listOf("-copyts", "-ss", fmt6(maxOf(0.0, relSeconds - 3.0))),
        "select=gte(t\\,${fmt6(relSeconds + startTime - 0.0005)})",
    )
}
```
`ffmpegPipeArgs` 시그니처를 `fun ffmpegPipeArgs(ffmpegPath: String, filePath: String, seekArgs: List<String>, selectFilter: String? = null): List<String> =` 로 바꾸고, 그 안의 줄
```kotlin
        "-vf", "scale='min(1280,iw)':'min(1280,ih)':force_original_aspect_ratio=decrease:flags=fast_bilinear",
```
을
```kotlin
        "-vf", listOfNotNull(selectFilter, "scale='min(1280,iw)':'min(1280,ih)':force_original_aspect_ratio=decrease:flags=fast_bilinear").joinToString(","),
```
로 바꾼다.

- [ ] **Step 5: 구현 — 경계 변환 (FfmpegVideoPlayer composable)**

(a) 프로브 코루틴 `LaunchedEffect(file) { … }`에서 다음 두 줄을
```kotlin
        probedInfo = info
        probing = false
```
다음으로 교체 — 처음 요청된 위치(절대 PTS)를 플레이어가 그리기 전에 상대 위치로 바꾼다:
```kotlin
        // initialSeekSeconds is an absolute frame PTS; the player's own positions are relative to the start.
        if (info != null && initialStart.seconds > 0.0) {
            startFromSeconds = (initialStart.seconds - info.startTime).coerceAtLeast(0.0)
        }
        probedInfo = info
        probing = false
```

(b) 같은 코루틴의
```kotlin
            val timestamps = suppliedTimestamps ?: runInterruptible(Dispatchers.IO) { probeFrameTimestamps(file) }
            frameTimestamps = timestamps
            onFrameTimestamps(timestamps)
            if ((probedInfo?.duration ?: 0.0) <= 0.0 && !timestamps.isNullOrEmpty()) {
                val lastPts = timestamps.last()
                val inferredDuration = if (timestamps.size > 1) {
                    lastPts + (lastPts - timestamps[timestamps.size - 2]).coerceAtLeast(0.001)
```
를 다음으로 교체(외부로는 절대, 내부 보관은 상대):
```kotlin
            val absolute = suppliedTimestamps ?: runInterruptible(Dispatchers.IO) { probeFrameTimestamps(file) }
            val timestamps = absolute?.map { it - info.startTime }
            frameTimestamps = timestamps
            onFrameTimestamps(absolute)
            if ((probedInfo?.duration ?: 0.0) <= 0.0 && !timestamps.isNullOrEmpty()) {
                val lastPts = timestamps.last()
                val inferredDuration = if (timestamps.size > 1) {
                    lastPts + (lastPts - timestamps[timestamps.size - 2]).coerceAtLeast(0.001)
```

(c) seek 요청 처리 `LaunchedEffect(seekRequestTick)`의
```kotlin
            startFromSeconds = seekRequestSeconds
```
를
```kotlin
            startFromSeconds = (seekRequestSeconds - (probedInfo?.startTime ?: 0.0)).coerceAtLeast(0.0)
```
로 바꾼다.

(d) `DisposableEffect(file, restartTrigger, audioInfo)` 안의
```kotlin
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val process = try {
            ProcessBuilder(
                ffmpegPipeArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, seekArgs),
```
를
```kotlin
        val seek = playerSeek(seekSeconds, info.startTime)
        val process = try {
            ProcessBuilder(
                ffmpegPipeArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, seek.inputArgs, seek.selectFilter),
```
로 바꾼다. (`info`는 이 composable에서 이미 `val info = probedInfo` 뒤 null 검사를 통과한 값이다. 오디오 `VideoAudioTrack(startFromSeconds = seekSeconds)`는 상대 `-ss`라 그대로 맞다.)

(e) 프레임 표시 콜백
```kotlin
                            if (position != null) onFramePresented(position)
```
을
```kotlin
                            if (position != null) onFramePresented(position.copy(ptsSeconds = position.ptsSeconds + info.startTime))
```
로 바꾼다.

`grep -n "seekRequestSeconds\|initialStart.seconds\|onFramePresented(\|onFrameTimestamps(" FfmpegVideoPlayer.kt`로 위 다섯 곳 외에 절대/상대가 섞이는 곳이 없는지 확인하고, 있으면 고치지 말고 보고한다.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.FfmpegVideoPlayerTest'` 그리고 `./gradlew :app:compileKotlin`
Expected: PASS / BUILD SUCCESSFUL (기존 `ffmpegPipeArgs` 테스트도 그대로 통과 — start 0 경로 불변)

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt
git commit -m "fix: seek the video player to exact frames on streams with a start offset

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: AI 전송 — 선택 프레임 첨부

**Files:** Modify `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt`, `app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt`

**Interfaces:**
- Consumes (Task 1): `ExactFrameResult`, `decodeSelectedFrame(file, frame, frames)`; 기존 `isAv2VideoTrack(root)`(`ui/Av2VideoPlayer.kt`), `cropForAi`, `AiVisualContext`, `ImageRegion`, `AiContextSnapshot.copy`.
- Produces: `AiChatController` 생성자 파라미터 `frameDecoder: (File, FrameInfo, List<FrameInfo>?) -> ExactFrameResult = ::decodeSelectedFrame`.

조건: `type == MediaType.VIDEO`, 이미지 미리보기 없음(`tab.aiImageSource == null`), `!isAv2VideoTrack(root)`, `frame != null`. (스펙의 "코덱 설정 박스" 조건은 쓰지 않는다: TS/MKV 트리에는 그런 박스가 없어 바로 이 기능이 필요한 파일을 막고, 이 경로는 박스 오프셋을 읽지 않고 ffmpeg에 파일을 통째로 준다. 규칙 6의 대상인 HEIC 모션포토는 `type == IMAGE`라 첫 조건에서 빠진다.)

- [ ] **Step 1: 실패하는 테스트 — AiChatControllerTest**

클래스 안에 추가:

```kotlin
    private fun videoTab(name: String = "v.mp4"): TabState = tab(name).apply {
        type = MediaType.VIDEO
        selectedFrame = com.multiviewer.ui.FrameInfo(7, 'P', 100, 0.25)
        gopFrames = listOf(com.multiviewer.ui.FrameInfo(6, 'I', 100, 0.2), com.multiviewer.ui.FrameInfo(7, 'P', 100, 0.25))
    }

    private fun frameImage(w: Int = 320, h: Int = 180) = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)

    @Test
    fun `a video tab attaches the selected frame decoded at send time`() = runBlocking {
        val a = videoTab()
        var decoded: com.multiviewer.ui.FrameInfo? = null
        var mainImages = -1
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, f, _ -> decoded = f; com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = false) },
            runner = { _, _, p, _, imgs -> prompt = p; mainImages = imgs.size; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        a.selectedFrame = com.multiviewer.ui.FrameInfo(6, 'I', 100, 0.2) // moved after sending
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(7, decoded?.index)
        assertEquals(1, mainImages)
        assertTrue(prompt.contains("video frame #7"), prompt)
        assertTrue(prompt.contains("320x180"), prompt)
    }

    @Test
    fun `no frame, AV2, a failed decode or a text-only provider attach nothing and say why`() = runBlocking {
        var decodes = 0
        var prompt = ""
        var mainImages = -1
        fun controller(result: com.multiviewer.ui.ExactFrameResult) = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, _, _ -> decodes++; result },
            runner = { _, _, p, _, imgs -> prompt = p; mainImages = imgs.size; AiCliResult.Answer("ok", false) })
        val ok = com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), 0.25, hdr = false)

        val none = videoTab("n.mp4").apply { selectedFrame = null }
        controller(ok).send(none, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { none.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, decodes); assertEquals(0, mainImages)
        assertTrue(prompt.contains("필름스트립/GOP에서 프레임을 선택"), prompt)

        val av2 = videoTab("a.mp4").apply {
            root = BoxNode("root", 0, 0, 100, listOf(BoxNode("av02", 0, 8, 50, listOf(BoxNode("av2C", 8, 8, 20)))))
        }
        controller(ok).send(av2, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { av2.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, decodes)
        assertTrue(prompt.contains("AV2"), prompt)

        val failed = videoTab("f.ts")
        controller(com.multiviewer.ui.ExactFrameResult.Failed("decoded PTS 0.300000 ≠ selected 0.250000")).send(failed, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { failed.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(1, decodes); assertEquals(0, mainImages)
        assertTrue(prompt.contains("정확히 얻지 못해") && prompt.contains("0.300000"), prompt)

        val gemini = videoTab("g.mp4")
        controller(ok).send(gemini, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { gemini.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(1, decodes) // no decode for a provider that cannot take images
        assertTrue(prompt.contains("이미지를 보낼 수 없음"), prompt)
    }

    @Test
    fun `an HDR frame is attached with a tone-mapping note`() = runBlocking {
        val a = videoTab()
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, f, _ -> com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = true) },
            runner = { _, _, p, _, _ -> prompt = p; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertTrue(prompt.contains("톤매핑 없이"), prompt)
    }
```
(`BoxNode` import는 이미 있다. `BoxNode(type, offset, headerSize, size, children)` 생성자 형식은 기존 `tab()` 헬퍼와 같게 맞춘다 — 다르면 `tab()`의 사용 형식을 따른다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiChatControllerTest'`
Expected: 컴파일 실패 — `frameDecoder` 파라미터 없음

- [ ] **Step 3: 구현 — 생성자**

`AiChatController` 생성자에서
```kotlin
    /** Test hook run inside context capture (e.g. to simulate a failure there). */
    private val contextCapture: () -> Any? = {},
```
뒤에 추가:
```kotlin
    /** Decodes a video tab's selected frame for the AI (blocking; run off the UI thread). */
    private val frameDecoder: (File, com.multiviewer.ui.FrameInfo, List<com.multiviewer.ui.FrameInfo>?) -> com.multiviewer.ui.ExactFrameResult =
        com.multiviewer.ui::decodeSelectedFrame,
```

- [ ] **Step 4: 구현 — send의 전송 시점 값**

`send`에서
```kotlin
        val imageSource = tab.aiImageSource
```
바로 아래에 추가:
```kotlin
        // A video tab sends its selected frame (filmstrip/GOP/popup), decoded exactly at native size at send time.
        val isVideo = type == com.multiviewer.ui.MediaType.VIDEO && imageSource == null
        val av2 = isVideo && com.multiviewer.ui.isAv2VideoTrack(root)
        val videoFrame = frame?.takeIf { isVideo && !av2 }
```

- [ ] **Step 5: 구현 — 코루틴에서 프레임 디코딩과 컨텍스트**

다음 블록을
```kotlin
            // A crop goes only to a provider whose image input was verified here, and only of the marked
            // region (else the visible viewport); otherwise the coordinates travel alone, labeled as such.
            val shown = snapshot.visual
```
다음으로 교체:
```kotlin
            // Video: decode the selected frame (once per send). A frame that cannot be shown exactly is not
            // shown at all -- the reason goes into the context instead.
            var frameImage: java.awt.image.BufferedImage? = null
            val frameNotes = mutableListOf<String>()
            if (isVideo) {
                when {
                    av2 -> frameNotes += label("AV2 프레임 이미지는 아직 첨부하지 않습니다.", "AV2 frame images are not attached yet.")
                    videoFrame == null -> frameNotes += label(
                        "프레임 이미지 없음 — 필름스트립/GOP에서 프레임을 선택하면 첨부됩니다.",
                        "No frame image: select a frame in the filmstrip/GOP view to attach it.",
                    )
                    !canPassImages -> frameNotes += label(
                        "이 앱의 ${provider.displayName} 연동은 이미지를 보낼 수 없음",
                        "this app's ${provider.displayName} integration cannot send images",
                    )
                    !snapshot.fileChangedSinceOpen -> when (
                        val r = withContext(ioDispatcher) { kotlinx.coroutines.runInterruptible { frameDecoder(file, videoFrame, frames) } }
                    ) {
                        is com.multiviewer.ui.ExactFrameResult.Frame -> {
                            frameImage = r.image
                            if (r.hdr) frameNotes += label(
                                "HDR 프레임을 톤매핑 없이 8비트로 변환한 이미지 — 밝기·색이 실제와 다를 수 있음",
                                "HDR frame converted to 8-bit without tone mapping: brightness and colour may differ",
                            )
                        }
                        is com.multiviewer.ui.ExactFrameResult.Failed -> frameNotes += label(
                            "선택한 프레임 이미지를 정확히 얻지 못해 첨부하지 않음 (${r.reason})",
                            "Selected frame image not attached: it could not be decoded exactly (${r.reason})",
                        )
                    }
                }
            }
            val videoImage = frameImage
            val withFrame = if (videoImage != null && videoFrame != null) {
                snapshot.copy(
                    visual = AiVisualContext(
                        "video frame #${videoFrame.index} ${videoFrame.type} PTS ${String.format(java.util.Locale.US, "%.6f", videoFrame.ptsSeconds)} s",
                        videoImage.width, videoImage.height, ImageRegion(0, 0, videoImage.width, videoImage.height), null, null,
                    ),
                    warnings = snapshot.warnings + frameNotes,
                )
            } else {
                snapshot.copy(warnings = snapshot.warnings + frameNotes)
            }
            // A crop goes only to a provider whose image input was verified here, and only of the marked
            // region (else the visible viewport); otherwise the coordinates travel alone, labeled as such.
            val shown = withFrame.visual
            val hasPixels = imageSource != null || videoImage != null
```

이어서 같은 함수 안에서:

- `val checkedImage = if (shown != null && region != null && imageSource != null && canPassImages &&` 의 `imageSource != null` 을 `hasPixels` 로 바꾼다.
- `val crop = if (shown != null && region != null && imageSource != null && canPassImages && checkedImage == CapabilityState.VERIFIED) {` 의 `imageSource != null` 을 `hasPixels` 로 바꾸고, 그 안의
```kotlin
                    withContext(Dispatchers.Default) { cropForAi(regionPixels(imageSource, region), ImageRegion(0, 0, region.width, region.height)) }
```
를
```kotlin
                    withContext(Dispatchers.Default) {
                        if (videoImage != null) cropForAi(videoImage, region)
                        else cropForAi(regionPixels(imageSource!!, region), ImageRegion(0, 0, region.width, region.height))
                    }
```
로 바꾼다.
- `val context = renderAiContext(snapshot, attachedImage = …` 의 `snapshot` 을 `withFrame` 으로 바꾼다.

(`snapshot.fileChangedSinceOpen` 검사 등 그 아래 코드는 그대로 `snapshot`을 쓴다. `AiContextSnapshot`의 `warnings`는 `List<String>`이다 — 다르면 맞춘다.)

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` 그리고 `./gradlew :app:compileKotlin`
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt
git commit -m "feat: attach the selected video frame to AI questions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 전체 검증

- [ ] **Step 1:** `./gradlew :app:test` — BUILD SUCCESSFUL (`ThumbnailDiskCacheTest`만 단독 재실행 통과 시 허용).
- [ ] **Step 2: 실사용(옵트인, 실제 CLI):** 앱 없이 확인할 수 있는 범위 — `decodeSelectedFrame`으로 `offset.ts` #30을 디코딩해 `runAiCli(ClaudeCliAdapter(), …, images = listOf(cropForAi(img, 전체)))`에 "이 이미지에 보이는 숫자/패턴을 한 줄로" 질문 → 답이 이미지 내용을 묘사하는지 확인(수동 로그).
- [ ] **Step 3: GUI (사용자):** offset.ts / 일반 MP4를 열고 프레임 분석 → 필름스트립에서 프레임 선택 → 팝업이 같은 프레임(#·PTS)을 보이고 ←/→가 한 프레임씩 이동; AI 패널 질문 → 컨텍스트에 "video frame #N" 첨부. 기본 설정 iPhone/Galaxy MOV(edit list) 실기 확인.
