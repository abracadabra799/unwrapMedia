# A/V Sync — Honor `start_time` and Edit Lists — Design

Date: 2026-09-06
Status: Approved for planning
Scope: `AvSyncAnalyzer.kt`. One field added to `AvSyncReport`; one line added to `AvSyncAnalysisWindow.kt`.

## Problem

`AvSyncAnalyzer` derives the initial A/V skew and the stream durations from raw
packet PTS (`ffprobe -show_packets`):

```kotlin
val vFirst = videoPackets.first().ptsSeconds
val aFirst = audioPackets.first().ptsSeconds
val initialSkewMs = (vFirst - aFirst) * 1000.0
val videoDurationSec = (vLast - vFirst)   // vLast = last pkt pts + its duration
val audioDurationSec = (aLast - aFirst)
```

Raw packet PTS includes AAC encoder priming (a negative-PTS look-ahead frame that
a compliant player skips via the container edit list) and trailing padding. For
`1_perfect_sync.mp4` (verified):

| | `-show_packets` first PTS | `-show_streams` `start_time` | `-show_streams` `duration` |
|---|---|---|---|
| video | 0.000 | 0.000 | 15.000 |
| audio | **−0.021333** (priming) | **0.000** | **15.000** |

So the tool reports `initialSkewMs = +21.3 ms` and `durationDeltaSec ≈ −55 ms`
for a file that a player treats as perfectly aligned. `-show_streams`'
`start_time` / `duration` are computed by ffmpeg **with the edit list applied** —
they are what a compliant player sees.

## Goal

Use `-show_streams` `start_time` / `duration` as the primary source for the skew
and durations; fall back to the packet-derived values only when the stream fields
are missing or `N/A`. Surface when an edit list / codec delay is in play.

## Non-Goals

- Parsing the `elst` box directly (ffprobe CLI does not expose it reliably) —
  edit-list presence is inferred from `|start_time − firstPacketPTS| > 5 ms`.
- Changing `computeSyncPoints`' internal `vFirst`/`aFirst` (elapsed + residual
  matching stay packet-relative and self-consistent; only the `initialSkewMs`
  model term changes).
- Changing `avSyncErrorModel`, `avSyncIsProgressiveDrift`, `avSyncVerdict`,
  `avSyncSegments`, or the visualization Canvas code.
- Multi-stream selection beyond "first video stream, first audio stream" (matches
  what `probePackets` already does — it merges by `codec_type`).

## Changes — `AvSyncAnalyzer.kt`

### New: `probeStreams`

```kotlin
private fun probeStreams(file: File): List<StreamInfo>
```

Runs:
```
ffprobe -v error -show_entries stream=index,codec_type,start_time,duration \
  -of default=noprint_wrappers=1 <file>
```
(same `FfmpegLocator` / `ProcessManager` / env plumbing as `probePackets`.)
Reads stdout, calls `parseStreamInfoBlocks` on it, returns the result. On any
exception (ffprobe missing, non-zero exit, IO) returns `emptyList()`.

`StreamInfo` (new, `internal`):
```kotlin
internal data class StreamInfo(
    val codecType: String,       // "video" | "audio" | other
    val startTimeSec: Double?,   // null when "N/A" / missing / unparseable
    val durationSec: Double?,
)
```

### New pure parser: `parseStreamInfoBlocks`

```kotlin
/** Parses `key=value` blocks from ffprobe `-show_streams` output. */
internal fun parseStreamInfoBlocks(ffprobeOutput: String): List<StreamInfo>
```

- A stream block is delimited by `index=` lines (or start-of-input). Within a
  block collect `codec_type`, `start_time`, `duration`.
- `"N/A"`, `""`, or a non-numeric value → `null` for that field.
- Missing `codec_type` → skip the block.

### New pure combiner: `resolveSkewAndDurations`

```kotlin
internal data class AvSyncTiming(
    val videoStartSec: Double,
    val audioStartSec: Double,
    val initialSkewMs: Double,   // (videoStartSec - audioStartSec) * 1000
    val videoDurationSec: Double,
    val audioDurationSec: Double,
    val editListAdjusted: Boolean,
)

/**
 * Prefer the container's edit-list-aware start_time / duration; fall back to the
 * packet-derived values per field when a stream field is absent.
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

### `analyze` wiring

After the packet lists are built and `vFirst`/`aFirst`/`vLast`/`aLast` computed
(keep them — they feed the fallback and `computeSyncPoints`):

```kotlin
val streams = probeStreams(file)
val vStream = streams.firstOrNull { it.codecType == "video" }
val aStream = streams.firstOrNull { it.codecType == "audio" }

val packetVideoDur = (vLast - vFirst).coerceAtLeast(0.0)
val packetAudioDur = (aLast - aFirst).coerceAtLeast(0.0)
val timing = resolveSkewAndDurations(vStream, aStream, vFirst, aFirst, packetVideoDur, packetAudioDur)

val initialSkewMs = timing.initialSkewMs
val videoDurationSec = timing.videoDurationSec
val audioDurationSec = timing.audioDurationSec
val durationDeltaSec = videoDurationSec - audioDurationSec
```

Everything downstream (`totalDurationSec`, `computeSyncPoints(...)`,
`driftRateMsPerMin`, `overallSeverity`, the duration-mismatch and drift diagnosis
blocks) is unchanged — it just receives the corrected scalars.

**Diagnosis #1 (initial skew)** currently prints `vFirst` / `aFirst` (raw packet
PTS) in its `technicalDetails`. Replace those two lines with
`timing.videoStartSec` / `timing.audioStartSec` so the printed start times match
the `initialSkewMs` they now drive. (Same `String.format(Locale.US, "%.3f", …)`.)

### `AvSyncReport` — one new field

```kotlin
val editListAdjusted: Boolean = false,
```

Set from `timing.editListAdjusted`. Existing constructor call site (only
`analyze`) passes it; the test helper in `AvSyncVisualizationTest` keeps the
default.

## Change — `AvSyncAnalysisWindow.kt`

One line, under the metric-card `Row`, before the visualization `Card`:

```kotlin
if (report.editListAdjusted) {
    Text(
        "ℹ 편집 리스트/코덱 딜레이가 적용된 파일입니다 — 시작 오프셋은 플레이어 기준(edit list 반영)으로 계산했습니다.",
        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary),
    )
}
```

## Expected results

| Sample | `initialSkewMs` before → after | `durationDeltaSec` before → after | overall |
|---|---|---|---|
| `1_perfect_sync` | +21.3 → **0.0** | −55 ms → **0** | PASS, flat curve |
| `2_constant_offset` | +140 → still large (video `start_time` genuinely shifted) | ~0 | CRITICAL initial skew |
| `3_progressive_drift` | ~0 → ~0 | ~169 ms → ~169 ms (stream `duration` still differs) | drift |

## Testing

New tests in `AvSyncAnalyzerTest.kt` (pure):

- `parseStreamInfoBlocks`:
  - the real 2-stream sample output → 2 `StreamInfo`, correct types/values.
  - `start_time=N/A` / `duration=N/A` → `null` fields.
  - a block missing `codec_type` → skipped.
  - empty / garbage input → empty list.
  - multi-audio → both audio blocks present (first is picked downstream).
- `resolveSkewAndDurations`:
  - both streams present, priming case (`videoStream.startTimeSec=0`,
    `audioStream.startTimeSec=0`, packet `packetAudioFirstPts=−0.021`) →
    `initialSkewMs == 0.0`, `videoStartSec == 0.0`, `editListAdjusted == true`.
  - `audioStream == null` → skew falls back to
    `packetVideoFirstPts − packetAudioFirstPts`, `editListAdjusted == false`.
  - `videoStream.durationSec == null` (but `startTimeSec` present) → uses
    `packetVideoDurationSec` for the duration, still uses `startTimeSec` for skew.
  - genuine offset (`videoStream.startTimeSec=0.14`, `audioStream.startTimeSec=0`,
    packets both ~0) → `initialSkewMs == 140.0`, `editListAdjusted == true`.
  - resolved duration would be negative → coerced to `0.0`.
- `AvSyncVisualizationTest` unaffected (`editListAdjusted` defaulted).

Full `./gradlew :app:test` green.

## Manual verification (owed)

`1_perfect_sync.mp4` → 종합상태 PASS, initial-skew card ~0 ms, and the edit-list
info note **is** shown (the AAC priming edit list is real — packet `aFirst`
−0.021 vs `start_time` 0 is a 21 ms delta > 5 ms). `2` and `3` still flag
correctly; `2` shows the note too if its video-track shift came via an edit list.

## Rollback

Single file + one field + one UI line. Revert; packet-PTS behaviour returns.
