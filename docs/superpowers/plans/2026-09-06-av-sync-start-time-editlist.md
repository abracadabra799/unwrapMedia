# A/V Sync — Honor `start_time` & Edit Lists — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive the A/V initial skew and stream durations from ffprobe `-show_streams` `start_time` / `duration` (edit-list-aware, what a compliant player sees) instead of raw packet PTS, with per-field fallback to the packet-derived values; surface when an edit list / codec delay is in play.

**Architecture:** New `probeStreams` (a second lightweight ffprobe call) + two pure functions (`parseStreamInfoBlocks`, `resolveSkewAndDurations`). `analyze` swaps its skew/duration computation to use them. One new `AvSyncReport` field, one info line in the window. `computeSyncPoints`, `avSyncErrorModel`, `avSyncVerdict`, `avSyncSegments`, and the Canvas code are untouched — they receive the corrected scalars.

**Tech Stack:** Kotlin, JUnit 5. No new dependencies.

## Global Constraints

- Kotlin 2.0.21; do NOT touch `app/build.gradle.kts`.
- `AvSyncAnalyzer.kt` + `AvSyncAnalysisWindow.kt` + the analyzer test file are the only `main`-tree files that change.
- `SyncPoint.deltaMs` sign convention unchanged: positive = audio leads.
- Do NOT change `computeSyncPoints` (curve logic), `avSyncErrorModel`, `avSyncIsProgressiveDrift`, `avSyncVerdict`, `avSyncSegments`, `AvSyncVisualization.kt`, or the duration-mismatch / drift diagnosis blocks.
- `./gradlew :app:test` green at end of every task.
- Korean strings inline, terse, matching the files.
- Commit messages END with these two lines exactly:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`

---

### Task 1: Stream-info probe + `resolveSkewAndDurations` + wiring

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt`

**Interfaces:**
- Consumes: `StreamPacket` (existing).
- Produces (`internal`, package `com.multiviewer.ui`):
  - `data class StreamInfo(val codecType: String, val startTimeSec: Double?, val durationSec: Double?)`
  - `data class AvSyncTiming(val videoStartSec: Double, val audioStartSec: Double, val initialSkewMs: Double, val videoDurationSec: Double, val audioDurationSec: Double, val editListAdjusted: Boolean)`
  - `fun parseStreamInfoBlocks(ffprobeOutput: String): List<StreamInfo>`
  - `fun resolveSkewAndDurations(videoStream: StreamInfo?, audioStream: StreamInfo?, packetVideoFirstPts: Double, packetAudioFirstPts: Double, packetVideoDurationSec: Double, packetAudioDurationSec: Double): AvSyncTiming`
  - `AvSyncReport` gains `val editListAdjusted: Boolean = false`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt`, inside the class:

```kotlin
    // ---- parseStreamInfoBlocks ----

    private val realShowStreamsOutput = """
        index=0
        codec_type=video
        start_time=0.000000
        duration=15.000000
        index=1
        codec_type=audio
        start_time=0.000000
        duration=15.000000
    """.trimIndent()

    @Test
    fun parseStreamInfo_realTwoStreamOutput() {
        val streams = parseStreamInfoBlocks(realShowStreamsOutput)
        assertEquals(2, streams.size)
        assertEquals("video", streams[0].codecType)
        assertEquals(0.0, streams[0].startTimeSec!!, 1e-9)
        assertEquals(15.0, streams[0].durationSec!!, 1e-9)
        assertEquals("audio", streams[1].codecType)
    }

    @Test
    fun parseStreamInfo_naFieldsBecomeNull() {
        val out = "index=0\ncodec_type=audio\nstart_time=N/A\nduration=N/A\n"
        val s = parseStreamInfoBlocks(out).single()
        assertEquals("audio", s.codecType)
        assertNull(s.startTimeSec)
        assertNull(s.durationSec)
    }

    @Test
    fun parseStreamInfo_blockMissingCodecTypeIsSkipped() {
        val out = "index=0\nstart_time=1.0\nduration=2.0\nindex=1\ncodec_type=video\nstart_time=0.0\nduration=10.0\n"
        val streams = parseStreamInfoBlocks(out)
        assertEquals(1, streams.size)
        assertEquals("video", streams[0].codecType)
    }

    @Test
    fun parseStreamInfo_emptyOrGarbage() {
        assertTrue(parseStreamInfoBlocks("").isEmpty())
        assertTrue(parseStreamInfoBlocks("no equals signs here\njust text").isEmpty())
    }

    @Test
    fun parseStreamInfo_multiAudioKeepsBoth() {
        val out = "index=0\ncodec_type=video\nstart_time=0.0\nduration=10.0\n" +
            "index=1\ncodec_type=audio\nstart_time=0.0\nduration=10.0\n" +
            "index=2\ncodec_type=audio\nstart_time=0.0\nduration=10.0\n"
        assertEquals(2, parseStreamInfoBlocks(out).count { it.codecType == "audio" })
    }

    // ---- resolveSkewAndDurations ----

    @Test
    fun resolveTiming_primingCase_streamStartWins_editListFlagged() {
        val v = StreamInfo("video", startTimeSec = 0.0, durationSec = 15.0)
        val a = StreamInfo("audio", startTimeSec = 0.0, durationSec = 15.0)
        val t = resolveSkewAndDurations(v, a, packetVideoFirstPts = 0.0, packetAudioFirstPts = -0.021333,
            packetVideoDurationSec = 15.0, packetAudioDurationSec = 15.021)
        assertEquals(0.0, t.initialSkewMs, 1e-6)
        assertEquals(0.0, t.videoStartSec, 1e-9)
        assertEquals(15.0, t.audioDurationSec, 1e-9)
        assertTrue(t.editListAdjusted, "21ms delta between packet PTS and start_time is an edit list")
    }

    @Test
    fun resolveTiming_noAudioStream_fallsBackToPackets() {
        val t = resolveSkewAndDurations(
            videoStream = StreamInfo("video", 0.0, 15.0), audioStream = null,
            packetVideoFirstPts = 0.0, packetAudioFirstPts = -0.05,
            packetVideoDurationSec = 15.0, packetAudioDurationSec = 15.05,
        )
        assertEquals(50.0, t.initialSkewMs, 1e-6)     // (0.0 - (-0.05)) * 1000
        assertEquals(15.05, t.audioDurationSec, 1e-9)
        assertFalse(t.editListAdjusted)
    }

    @Test
    fun resolveTiming_durationNaButStartPresent_usesPacketDurationKeepsStartSkew() {
        val v = StreamInfo("video", startTimeSec = 0.0, durationSec = null)
        val a = StreamInfo("audio", startTimeSec = 0.0, durationSec = 12.0)
        val t = resolveSkewAndDurations(v, a, 0.0, 0.0, packetVideoDurationSec = 11.7, packetAudioDurationSec = 12.0)
        assertEquals(11.7, t.videoDurationSec, 1e-9)
        assertEquals(0.0, t.initialSkewMs, 1e-9)
    }

    @Test
    fun resolveTiming_genuineOffset() {
        val v = StreamInfo("video", startTimeSec = 0.14, durationSec = 60.0)
        val a = StreamInfo("audio", startTimeSec = 0.0, durationSec = 60.0)
        val t = resolveSkewAndDurations(v, a, 0.14, 0.0, 60.0, 60.0)
        assertEquals(140.0, t.initialSkewMs, 1e-6)
        assertTrue(t.editListAdjusted)
    }

    @Test
    fun resolveTiming_negativeResolvedDurationCoercedToZero() {
        val v = StreamInfo("video", 5.0, -3.0)
        val t = resolveSkewAndDurations(v, null, 5.0, 0.0, -3.0, 0.0)
        assertEquals(0.0, t.videoDurationSec, 1e-9)
    }
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncAnalyzerTest"`
Expected: FAIL — `parseStreamInfoBlocks`, `resolveSkewAndDurations`, `StreamInfo`, `AvSyncTiming` unresolved.

- [ ] **Step 3: Add `StreamInfo`, `AvSyncTiming`, `parseStreamInfoBlocks`, `resolveSkewAndDurations`**

In `AvSyncAnalyzer.kt`, add these top-level `internal` declarations right after the existing `avSyncIsProgressiveDrift` function (before `object AvSyncAnalyzer` — or immediately before `computeSyncPoints` if that's earlier; anywhere top-level in the file is fine):

```kotlin
/** One stream's edit-list-aware timing from `ffprobe -show_streams`. */
internal data class StreamInfo(
    val codecType: String,
    val startTimeSec: Double?,
    val durationSec: Double?,
)

internal data class AvSyncTiming(
    val videoStartSec: Double,
    val audioStartSec: Double,
    val initialSkewMs: Double,
    val videoDurationSec: Double,
    val audioDurationSec: Double,
    val editListAdjusted: Boolean,
)

/**
 * Parses `key=value` blocks from `ffprobe -show_entries stream=... -of
 * default=noprint_wrappers=1`. Each `index=` line starts a new stream block.
 * `N/A` / missing / unparseable numeric fields become null.
 */
internal fun parseStreamInfoBlocks(ffprobeOutput: String): List<StreamInfo> {
    val result = ArrayList<StreamInfo>()
    var codecType: String? = null
    var startTime: Double? = null
    var duration: Double? = null

    fun flush() {
        val ct = codecType
        if (ct != null) result.add(StreamInfo(ct, startTime, duration))
        codecType = null; startTime = null; duration = null
    }

    for (raw in ffprobeOutput.lineSequence()) {
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq)
        val value = line.substring(eq + 1)
        when (key) {
            "index" -> flush()
            "codec_type" -> codecType = value
            "start_time" -> startTime = value.toDoubleOrNull()
            "duration" -> duration = value.toDoubleOrNull()
        }
    }
    flush()
    return result
}

/**
 * Prefer the container's edit-list-aware `start_time` / `duration`; fall back to
 * the packet-derived values per field when a stream field is absent. Flags when
 * `start_time` and the first packet PTS disagree by > 5 ms (an edit list or
 * codec delay is trimming/shifting the stream).
 */
internal fun resolveSkewAndDurations(
    videoStream: StreamInfo?,
    audioStream: StreamInfo?,
    packetVideoFirstPts: Double,
    packetAudioFirstPts: Double,
    packetVideoDurationSec: Double,
    packetAudioDurationSec: Double,
): AvSyncTiming {
    val vStart = videoStream?.startTimeSec ?: packetVideoFirstPts
    val aStart = audioStream?.startTimeSec ?: packetAudioFirstPts
    val vDur = videoStream?.durationSec ?: packetVideoDurationSec
    val aDur = audioStream?.durationSec ?: packetAudioDurationSec
    val editListAdjusted =
        (videoStream?.startTimeSec?.let { abs(it - packetVideoFirstPts) > 0.005 } ?: false) ||
        (audioStream?.startTimeSec?.let { abs(it - packetAudioFirstPts) > 0.005 } ?: false)
    return AvSyncTiming(
        videoStartSec = vStart,
        audioStartSec = aStart,
        initialSkewMs = (vStart - aStart) * 1000.0,
        videoDurationSec = vDur.coerceAtLeast(0.0),
        audioDurationSec = aDur.coerceAtLeast(0.0),
        editListAdjusted = editListAdjusted,
    )
}
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncAnalyzerTest"`
Expected: PASS (the 10 new tests + all existing).

- [ ] **Step 5: Add `probeStreams` to `object AvSyncAnalyzer`**

Add a `private fun` inside `object AvSyncAnalyzer`, right after `probePackets`:

```kotlin
    private fun probeStreams(file: File): List<StreamInfo> {
        var process: Process? = null
        return try {
            process = ProcessManager.register(
                ProcessBuilder(
                    FfmpegLocator.ffprobePath(), "-v", "error",
                    "-show_entries", "stream=index,codec_type,start_time,duration",
                    "-of", "default=noprint_wrappers=1", file.absolutePath
                ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { FfmpegLocator.configureEnvironment(it) }.start()
            )
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            parseStreamInfoBlocks(text)
        } catch (e: Exception) {
            emptyList()
        } finally {
            process?.let {
                ProcessManager.terminate(it)
                ProcessManager.unregister(it)
            }
        }
    }
```

- [ ] **Step 6: Wire `analyze` to use `resolveSkewAndDurations`**

In `analyze`, replace this block (currently at ~lines 179–186):

```kotlin
            val vFirst = videoPackets.first().ptsSeconds
            val aFirst = audioPackets.first().ptsSeconds
            val initialSkewMs = (vFirst - aFirst) * 1000.0

            val vLast = videoPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }
            val aLast = audioPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }

            val videoDurationSec = (vLast - vFirst).coerceAtLeast(0.0)
            val audioDurationSec = (aLast - aFirst).coerceAtLeast(0.0)
            val durationDeltaSec = videoDurationSec - audioDurationSec
            val totalDurationSec = maxOf(videoDurationSec, audioDurationSec)
```

with:

```kotlin
            val vFirst = videoPackets.first().ptsSeconds
            val aFirst = audioPackets.first().ptsSeconds
            val vLast = videoPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }
            val aLast = audioPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }

            // Prefer edit-list-aware start_time / duration (what a player sees);
            // fall back to packet PTS per field when the stream fields are N/A.
            val streams = probeStreams(file)
            val timing = resolveSkewAndDurations(
                videoStream = streams.firstOrNull { it.codecType == "video" },
                audioStream = streams.firstOrNull { it.codecType == "audio" },
                packetVideoFirstPts = vFirst,
                packetAudioFirstPts = aFirst,
                packetVideoDurationSec = (vLast - vFirst).coerceAtLeast(0.0),
                packetAudioDurationSec = (aLast - aFirst).coerceAtLeast(0.0),
            )
            val initialSkewMs = timing.initialSkewMs
            val videoDurationSec = timing.videoDurationSec
            val audioDurationSec = timing.audioDurationSec
            val durationDeltaSec = videoDurationSec - audioDurationSec
            val totalDurationSec = maxOf(videoDurationSec, audioDurationSec)
```

Keep the `vFirst` / `aFirst` locals — they feed `timing` (as `packetVideoFirstPts`
/ `packetAudioFirstPts`) and diagnosis #1's fallback text. `computeSyncPoints`
re-derives its own first-PTS internally and is unchanged.

- [ ] **Step 7: Diagnosis #1 prints the effective start times**

In `analyze`, in the initial-skew diagnosis, both branches print
`비디오 시작 PTS` / `오디오 시작 PTS` from `vFirst` / `aFirst` (at ~lines 217–218
and ~235–236). Change all four `String.format(Locale.US, "%.3f", vFirst)` /
`... aFirst` to `timing.videoStartSec` / `timing.audioStartSec` respectively.

- [ ] **Step 8: `AvSyncReport` field + constructor**

Add to the `AvSyncReport` data class (after `overallSeverity`, keep it last with a default):

```kotlin
    val editListAdjusted: Boolean = false,
```

In the `AvSyncReport(...)` construction in `analyze` (after `overallSeverity = overallSeverity,`), add:

```kotlin
                editListAdjusted = timing.editListAdjusted,
```

- [ ] **Step 9: Window info line**

In `AvSyncAnalysisWindow.kt`, in `AvSyncReportContent`, immediately after the
metric-card `Row { … }` closes (the `}` before the `// 2. At-a-glance` comment,
~line 175), insert:

```kotlin
        if (report.editListAdjusted) {
            Text(
                "ℹ 편집 리스트/코덱 딜레이가 적용된 파일 — 시작 오프셋은 플레이어 기준(edit list 반영)으로 계산했습니다.",
                style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary),
            )
        }
```

- [ ] **Step 10: Full suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`. `AvSyncVisualizationTest` unaffected (its synthetic
`AvSyncReport` uses the `editListAdjusted` default). Confirm no other
`AvSyncReport(` construction site: `grep -rn "AvSyncReport(" app/src` → only
`AvSyncAnalyzer.kt` and the `AvSyncVisualizationTest` helper (which does not need
the new arg).

- [ ] **Step 11: Visual check**

Run: `./gradlew :app:run`. Open `~/Downloads/avsync_samples/1_perfect_sync.mp4`:
- 종합상태 PASS; "초기 립싱크" metric card ≈ **0.0 ms** (was +21.3); "트랙 길이
  차이" ≈ **0 ms**; the "ℹ 편집 리스트…" note is shown under the metric cards.
- Curve flat near 0, verdict "✅ 양호".

Open `2_constant_offset_audio_ahead_120ms.mp4` and `3_progressive_drift.mp4` —
still CRITICAL initial-skew and drift respectively.

Close the app. (If the GUI can't be driven from the shell, confirm clean launch
and that the per-sample reasoning holds.)

- [ ] **Step 12: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt \
        app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt \
        app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt
git commit -m "$(cat <<'EOF'
feat: A/V sync honors ffprobe start_time and edit lists

Initial skew and stream durations now come from `ffprobe -show_streams`
start_time / duration (edit-list-aware, what a compliant player sees) instead
of raw packet PTS, which includes AAC encoder priming — a perfectly-synced
clip was reporting +21 ms skew and a 55 ms length delta. Per-field fallback to
the packet-derived values when a stream field is N/A. New AvSyncReport
.editListAdjusted surfaces when start_time and first-packet PTS disagree.

New pure functions parseStreamInfoBlocks / resolveSkewAndDurations with tests;
computeSyncPoints and the verdict/visualization are untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec item | Step |
|---|---|
| `probeStreams` (ffprobe -show_streams, ProcessManager plumbing, emptyList on failure) | Step 5 |
| `parseStreamInfoBlocks` (index-delimited blocks, N/A→null, missing codec_type skipped) | Step 3 + tests Step 1 |
| `resolveSkewAndDurations` (start_time preferred, per-field packet fallback, editListAdjusted from >5ms delta, duration coerceAtLeast 0) | Step 3 + tests Step 1 |
| `AvSyncTiming` with `videoStartSec`/`audioStartSec` | Step 3 |
| `analyze` uses resolved skew + durations | Step 6 |
| diagnosis #1 prints effective start times (both branches) | Step 7 |
| `AvSyncReport.editListAdjusted` field + wiring | Step 8 |
| window info line when `editListAdjusted` | Step 9 |
| `computeSyncPoints` / verdict / segments / Canvas untouched | no step touches them |
| durations from `-show_streams` (fixes priming inflation) | Step 3/6 |
| tests: parseStreamInfoBlocks 5, resolveSkewAndDurations 5 | Step 1 |
| full suite green; no other AvSyncReport ctor | Step 10 |
| manual verification on the 3 samples | Step 11 |

**2. Placeholder scan** — none. Every function body and test is complete; the `analyze` edits are quoted old→new verbatim.

**3. Type consistency**

- `StreamInfo(codecType: String, startTimeSec: Double?, durationSec: Double?)` — same in Step 3 (def), Step 1 (test constructions), Step 6 (`streams.firstOrNull { it.codecType == ... }`). ✓
- `AvSyncTiming(videoStartSec, audioStartSec, initialSkewMs, videoDurationSec, audioDurationSec, editListAdjusted)` — def Step 3; consumed Step 6 (`timing.initialSkewMs` etc.), Step 7 (`timing.videoStartSec`), Step 8 (`timing.editListAdjusted`); tests Step 1. ✓
- `parseStreamInfoBlocks(String): List<StreamInfo>` — def Step 3, called by `probeStreams` Step 5, tested Step 1. ✓
- `resolveSkewAndDurations(StreamInfo?, StreamInfo?, Double, Double, Double, Double): AvSyncTiming` — def Step 3, called Step 6 with named args in that order, tested Step 1. ✓
- `AvSyncReport.editListAdjusted: Boolean = false` — field Step 8; the `AvSyncVisualizationTest` helper (Step 10 note) relies on the default; `analyze` passes it Step 8. ✓
- `FfmpegLocator.ffprobePath()` / `configureEnvironment` / `ProcessManager.register`/`terminate`/`unregister` — copied verbatim from the existing `probePackets` (`AvSyncAnalyzer.kt:354-401`). ✓
