# GOP Analysis Button Gating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Disable the "프레임 분석 시작" button until `FfmpegVideoPlayer`'s background frame-timestamp probe finishes, so it can't run concurrently with that same-cost ffprobe scan.

**Architecture:** A new per-tab `videoReadyForAnalysis` flag (`TabState`), flipped by a new `onProbeComplete` callback on `FfmpegVideoPlayer` fired once its background timestamp probe finishes (success or not), wired through `VideoInspectorUI`, read by `GopAnalysisView`'s button.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- `FfmpegVideoPlayer`'s own `probing` flag and the timing of when the player itself becomes interactive are unchanged -- this plan only adds a new, separate signal for a different purpose (gating the GOP button), it does not touch or slow down the player's existing fast-interactivity behavior.
- `onProbeComplete` fires exactly once per `LaunchedEffect(file)` run, unconditionally (whether or not `probeVideo` succeeded), so a failed/unprobeable video still ends up with the button enabled rather than permanently disabled.
- No changes to `probeFrameTypes`/`FrameTypeAnalyzer.kt` or to how analysis results are displayed once available.

---

### Task 1: Add videoReadyForAnalysis gating end-to-end

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

**Interfaces:**
- Produces: `TabState.videoReadyForAnalysis: Boolean` (new field, default `false`); `FfmpegVideoPlayer`'s new parameter `onProbeComplete: () -> Unit = {}`.
- Consumes: nothing from elsewhere -- this task is self-contained (all 4 files must change together for the app to compile and the feature to work end-to-end, so this is one task, not split further).

- [ ] **Step 1: Confirm current TabState fields**

Run: `grep -n "var isAnalyzingFrames\|var selectedFrame" app/src/main/kotlin/com/multiviewer/ui/AppState.kt`

Expected:
```
app/src/main/kotlin/com/multiviewer/ui/AppState.kt:126:    var isAnalyzingFrames: Boolean by mutableStateOf(false)
app/src/main/kotlin/com/multiviewer/ui/AppState.kt:127:    var selectedFrame: FrameInfo? by mutableStateOf(null)
```
If different, stop and re-read the surrounding `TabState` class before editing.

- [ ] **Step 2: Add the new TabState field**

Find:

```kotlin
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
```

Replace with:

```kotlin
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
    // Set once FfmpegVideoPlayer's own background frame-timestamp probe finishes (see its
    // onProbeComplete callback) -- gates the "프레임 분석 시작" button so it can't launch a second,
    // same-cost full-file ffprobe scan while that background one is still running.
    var videoReadyForAnalysis: Boolean by mutableStateOf(false)
```

- [ ] **Step 3: Confirm current FfmpegVideoPlayer signature and LaunchedEffect(file) block**

Run: `grep -n "fun FfmpegVideoPlayer" -A 8 app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

Expected:
```kotlin
fun FfmpegVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onElapsedChanged: (Double) -> Unit = {},
    seekRequestSeconds: Double = 0.0,
    seekRequestTick: Int = 0,
) {
    var videoBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember(file) { mutableStateOf(false) }
```
If different, stop and re-read the whole function before editing.

- [ ] **Step 4: Add the onProbeComplete parameter**

Find:

```kotlin
fun FfmpegVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onElapsedChanged: (Double) -> Unit = {},
    seekRequestSeconds: Double = 0.0,
    seekRequestTick: Int = 0,
) {
```

Replace with:

```kotlin
fun FfmpegVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onElapsedChanged: (Double) -> Unit = {},
    seekRequestSeconds: Double = 0.0,
    seekRequestTick: Int = 0,
    onProbeComplete: () -> Unit = {},
) {
```

- [ ] **Step 5: Call onProbeComplete at the end of the background probe**

Find:

```kotlin
        probedInfo = info
        probing = false
        if (info != null) {
            frameTimestamps = withContext(Dispatchers.IO) { probeFrameTimestamps(file) }
        }
    }
```

Replace with:

```kotlin
        probedInfo = info
        probing = false
        if (info != null) {
            frameTimestamps = withContext(Dispatchers.IO) { probeFrameTimestamps(file) }
        }
        onProbeComplete()
    }
```

- [ ] **Step 6: Confirm current VideoInspectorUI.kt call site**

Run: `grep -n "FfmpegVideoPlayer(" -A 5 app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected:
```kotlin
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                        )
```
If different, stop and re-read the surrounding function before editing.

- [ ] **Step 7: Wire onProbeComplete in VideoInspectorUI.kt**

Find:

```kotlin
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                        )
```

Replace with:

```kotlin
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                            onProbeComplete = { tab.videoReadyForAnalysis = true },
                        )
```

- [ ] **Step 8: Confirm current GopAnalysisView.kt button block**

Run: `grep -n "frames == null ->" -A 4 app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

Expected:
```kotlin
            frames == null -> {
                Button(onClick = onAnalyze, modifier = Modifier.align(Alignment.Center)) {
                    Text("프레임 분석 시작")
                }
            }
```
If different, stop and re-read the surrounding `when` block before editing.

- [ ] **Step 9: Gate the button and add the explanatory caption**

Find:

```kotlin
            frames == null -> {
                Button(onClick = onAnalyze, modifier = Modifier.align(Alignment.Center)) {
                    Text("프레임 분석 시작")
                }
            }
```

Replace with:

```kotlin
            frames == null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(onClick = onAnalyze, enabled = tab.videoReadyForAnalysis) {
                        Text("프레임 분석 시작")
                    }
                    if (!tab.videoReadyForAnalysis) {
                        Text(
                            "동영상 분석이 끝나면 활성화됩니다",
                            style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                        )
                    }
                }
            }
```

`Column` is already imported in this file (`androidx.compose.foundation.layout.Column`) -- no new import needed.

- [ ] **Step 10: Verify all four edits**

Run:
```bash
grep -n "videoReadyForAnalysis" app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
grep -n "onProbeComplete" app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
```
Expected: `videoReadyForAnalysis` appears once in `AppState.kt` (the field declaration), once in `VideoInspectorUI.kt` (the callback assignment), and twice in `GopAnalysisView.kt` (`enabled = tab.videoReadyForAnalysis` and the `if (!tab.videoReadyForAnalysis)` check). `onProbeComplete` appears in `FfmpegVideoPlayer.kt` twice (parameter declaration and the call at the end of `LaunchedEffect`), and once in `VideoInspectorUI.kt` (the wiring).

- [ ] **Step 11: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task (no existing test constructs `FfmpegVideoPlayer` or exercises `GopAnalysisView`'s Composable body directly, so none of them are affected by these signature/field additions).

- [ ] **Step 13: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open a video. Confirm the "프레임 분석 시작" button is greyed out immediately after opening, with the "동영상 분석이 끝나면 활성화됩니다" caption showing beneath it, and confirm it becomes clickable (caption disappears) once the background probe finishes -- a longer video makes this window easier to observe than a short one. Confirm clicking it once enabled still starts frame analysis normally.

- [ ] **Step 14: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
git commit -m "Disable frame analysis button until the background video probe finishes"
```

---
