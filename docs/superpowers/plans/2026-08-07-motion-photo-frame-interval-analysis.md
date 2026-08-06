# Motion Photo Frame Interval Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "모션포토 동영상 프레임 드랍 분석" menu item under the existing "모션포토" menu that opens the app's existing frame-interval-analysis view (scatter plot + table) against a Motion Photo's **embedded video** — not the live preview player, not the photo file itself.

**Architecture:** New `TabState` fields + an `AppState.analyzeMotionPhotoFrames` function mirroring the existing `analyzeMotionPhotoCodecDetails`'s extract-to-temp-file pattern exactly, but calling the existing `probeFrameTypes`/`probeVideo` (the same functions the regular-video path already uses) on that temp file. The existing `FrameIntervalAnalysisWindow` composable is split into a data-agnostic content composable plus two thin wrappers — the existing one (unchanged behavior) and a new `MotionPhotoFrameIntervalAnalysisWindow`. One new menu item and one new window-open flag in `Main.kt`.

**Tech Stack:** Kotlin, Compose Desktop, existing `extractEmbeddedVideo`/`probeFrameTypes`/`probeVideo`/`computeFrameIntervals` functions, `kotlin.test` with real ffmpeg-generated fixtures (matching this codebase's established `AppStateTest.kt` convention for background-thread `AppState` functions).

## Global Constraints

- Reference design doc: `docs/superpowers/specs/2026-08-07-motion-photo-frame-interval-analysis-design.md`.
- "모션포토 동영상" means the embedded video byte stream (`tab.embeddedVideo`, extracted via the existing `extractEmbeddedVideo`) — never `tab.file` (the photo itself) and never `tab.motionPhotoPreview` (the separate autoplay-preview clip).
- The new menu item goes under the existing `Menu("모션포토")` block in `Main.kt`, not the separate `Menu("프레임 간격 분석")` block.
- No changes to the existing "프레임 간격 분석" menu/window behavior for regular video files.
- No changes to `computeFrameIntervals`, `FrameIntervalAnalysisView` (the scatter plot + table composable), `extractEmbeddedVideo`, `probeFrameTypes`, or `probeVideo` — all reused exactly as they are.

---

### Task 1: `TabState` fields and `AppState.analyzeMotionPhotoFrames`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (new `TabState` fields after line 163, new function after `analyzeMotionPhotoCodecDetails` at line 598)
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (append before the final closing `}`; also add one new private helper alongside the existing `waitForFrameAnalysis`)

**Interfaces:**
- Produces: `TabState.motionPhotoGopFrames: List<FrameInfo>?` (null = "never asked", matching `gopFrames`'s own convention), `TabState.isAnalyzingMotionPhotoFrames: Boolean`, `TabState.motionPhotoVideoFps: Double?`; `AppState.fun analyzeMotionPhotoFrames(tab: TabState)`. Task 2 consumes all four by name.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, add this private helper right after the existing `waitForFrameAnalysis` (around line 36):

```kotlin
    private fun waitForMotionPhotoFrameAnalysis(tab: TabState, timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (tab.isAnalyzingMotionPhotoFrames) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for motion photo frame analysis of ${tab.file.name}" }
            Thread.sleep(10)
        }
    }
```

Then add this test before `AppStateTest`'s final closing `}`:

```kotlin
    @Test
    fun `analyzeMotionPhotoFrames populates motionPhotoGopFrames and motionPhotoVideoFps from the real embedded video`() {
        // Build a real, decodable embedded video via ffmpeg (same lavfi source as
        // analyzeFrames's own test above, so the same 20-frame/'I'-first-frame assertions apply),
        // then wrap it in a minimal ftyp+mpvd shell -- the same structural pattern
        // MediaSummaryBuilderTest and MotionPhotoExtractorTest already use to represent a
        // Samsung/HEIC-style motion photo (mpvd box containing the embedded video's own ftyp as
        // its first child).
        val embeddedVideo = File.createTempFile("motion-photo-frame-test-embedded-", ".mp4")
        embeddedVideo.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            embeddedVideo.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val embeddedVideoBytes = embeddedVideo.readBytes()
        embeddedVideo.delete()

        val outerFtyp = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val mpvdSize = 8L + embeddedVideoBytes.size
        val mpvdHeader = byteArrayOf(
            ((mpvdSize shr 24) and 0xFF).toByte(), ((mpvdSize shr 16) and 0xFF).toByte(),
            ((mpvdSize shr 8) and 0xFF).toByte(), (mpvdSize and 0xFF).toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
        )
        val photoFile = File.createTempFile("motion-photo-frame-test-photo-", ".mp4")
        photoFile.deleteOnExit()
        photoFile.writeBytes(outerFtyp + mpvdHeader + embeddedVideoBytes)

        val appState = AppState()
        appState.openFile(photoFile)
        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertTrue(tab.embeddedVideo != null, "Expected embeddedVideo to be populated from the mpvd box")

        appState.analyzeMotionPhotoFrames(tab)
        assertEquals(true, tab.isAnalyzingMotionPhotoFrames)

        waitForMotionPhotoFrameAnalysis(tab)

        assertEquals(false, tab.isAnalyzingMotionPhotoFrames)
        assertEquals(20, tab.motionPhotoGopFrames?.size)
        assertEquals('I', tab.motionPhotoGopFrames?.first()?.type)
        assertTrue((tab.motionPhotoVideoFps ?: 0.0) > 0.0, "Expected a positive fps from the embedded video, got ${tab.motionPhotoVideoFps}")
        photoFile.delete()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.ui.AppStateTest"
```
Expected: FAIL to compile — `motionPhotoGopFrames`, `isAnalyzingMotionPhotoFrames`, `motionPhotoVideoFps`, and `analyzeMotionPhotoFrames` don't exist yet.

- [ ] **Step 3: Add the three `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find (around line 157-163):
```kotlin
    // Motion Photo Video codec-detail enrichment (see StreamCodecDetails.kt) -- button-triggered
    // since, unlike the main video, this requires extracting the embedded video to a temp file
    // before ffprobe can see it. motionPhotoVideoSections is already non-null before this runs
    // (built by buildMediaSummary), so unlike gopFrames its nullability can't signal "not yet
    // asked" -- a separate flag is needed.
    var isAnalyzingMotionPhotoCodec: Boolean by mutableStateOf(false)
    var motionPhotoCodecDetailsLoaded: Boolean by mutableStateOf(false)
```

Add right after it:
```kotlin

    // Motion Photo Video frame-interval analysis (see FrameIntervalAnalysisView.kt) -- same
    // extract-to-temp-file requirement as the codec-detail enrichment above, but reuses the
    // regular video path's own probeFrameTypes/probeVideo instead of probeStreamDetails. null
    // motionPhotoGopFrames means "never asked" (same convention as gopFrames above).
    var motionPhotoGopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingMotionPhotoFrames: Boolean by mutableStateOf(false)
    var motionPhotoVideoFps: Double? by mutableStateOf(null)
```

- [ ] **Step 4: Add `analyzeMotionPhotoFrames`**

Find `analyzeMotionPhotoCodecDetails`'s closing `}` (currently ends at line 598, right before the class's own final closing `}` at line 599):
```kotlin
            EventQueue.invokeLater {
                val summary = tab.mediaSummary
                if (details != null && summary != null) {
                    val currentSections = summary.motionPhotoVideoSections ?: emptyList()
                    val merged = mergeStreamCodecDetailsIntoSections(currentSections, details.videoFields, details.audioFields)
                    tab.mediaSummary = summary.copy(motionPhotoVideoSections = merged)
                }
                tab.motionPhotoCodecDetailsLoaded = true
                tab.isAnalyzingMotionPhotoCodec = false
            }
        }
    }
}
```

Insert the new function right after `analyzeMotionPhotoCodecDetails`'s closing `}`, before the class's own closing `}`:
```kotlin

    fun analyzeMotionPhotoFrames(tab: TabState) {
        val video = tab.embeddedVideo ?: return
        if (tab.isAnalyzingMotionPhotoFrames || tab.motionPhotoGopFrames != null) return
        tab.isAnalyzingMotionPhotoFrames = true
        runInBackground {
            val temp = try {
                val dest = File.createTempFile("motion-photo-frame-interval-", ".${video.extension}")
                dest.deleteOnExit()
                extractEmbeddedVideo(tab.file, video, dest)
                dest
            } catch (e: Exception) {
                null
            }
            val frames = temp?.let { probeFrameTypes(it) }
            val videoInfo = temp?.let { probeVideo(it) }
            temp?.delete()
            EventQueue.invokeLater {
                tab.motionPhotoGopFrames = frames ?: emptyList()
                tab.motionPhotoVideoFps = videoInfo?.fps
                tab.isAnalyzingMotionPhotoFrames = false
            }
        }
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.ui.AppStateTest"
```
Expected: PASS, the new test plus all pre-existing `AppStateTest` cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "Add analyzeMotionPhotoFrames and its TabState fields"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 2: Split `FrameIntervalAnalysisWindow` and add `MotionPhotoFrameIntervalAnalysisWindow`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt` (lines 289-325)

**Interfaces:**
- Consumes: Task 1's `TabState.motionPhotoGopFrames`/`isAnalyzingMotionPhotoFrames`/`motionPhotoVideoFps`, `AppState.analyzeMotionPhotoFrames`.
- Produces: `private fun FrameIntervalAnalysisWindowContent(title: String, frames: List<FrameInfo>?, isAnalyzing: Boolean, fps: Double?, onCloseRequest: () -> Unit)`; `fun MotionPhotoFrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit)`. Task 3 calls the latter by name.

No test for this task — it's a pure refactor (existing behavior preserved exactly) plus new UI wiring with no new pure logic; verified by the full test suite staying green and by Task 4's manual verification.

- [ ] **Step 1: Replace `FrameIntervalAnalysisWindow` with the split version**

Find (currently lines 289-325):
```kotlin
// Owns data-fetching: reuses the same tab.gopFrames/AppState.analyzeFrames the GOP panel already
// populates (no duplicate ffprobe call if the user already opened GOP analysis for this tab), plus
// a fresh probeVideo call for fps (not cached anywhere else on TabState). Opens an independent,
// resizable Window rather than a modal Dialog since the data table can be long.
@Composable
fun FrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab) {
        appState.analyzeFrames(tab)
    }
    var videoInfo by remember(tab) { mutableStateOf<VideoInfo?>(null) }
    LaunchedEffect(tab) {
        videoInfo = withContext(Dispatchers.IO) { probeVideo(tab.file) }
    }

    Window(onCloseRequest = onCloseRequest, title = "프레임 간격 분석 - ${tab.file.name}") {
        val frames = tab.gopFrames
        val intervals = remember(frames) { frames?.let { computeFrameIntervals(it) } ?: emptyList() }

        Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
            when {
                tab.isAnalyzingFrames || frames == null -> {
                    DecodingIndicator("프레임 분석 중...", modifier = Modifier.align(Alignment.Center))
                }
                intervals.isEmpty() -> {
                    Text(
                        "간격 정보 없음",
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                    )
                }
                else -> {
                    FrameIntervalAnalysisView(intervals = intervals, fps = videoInfo?.fps, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
```

Replace with:
```kotlin
// Owns data-fetching: reuses the same tab.gopFrames/AppState.analyzeFrames the GOP panel already
// populates (no duplicate ffprobe call if the user already opened GOP analysis for this tab), plus
// a fresh probeVideo call for fps (not cached anywhere else on TabState). Opens an independent,
// resizable Window rather than a modal Dialog since the data table can be long.
@Composable
fun FrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab) {
        appState.analyzeFrames(tab)
    }
    var videoInfo by remember(tab) { mutableStateOf<VideoInfo?>(null) }
    LaunchedEffect(tab) {
        videoInfo = withContext(Dispatchers.IO) { probeVideo(tab.file) }
    }

    FrameIntervalAnalysisWindowContent(
        title = "프레임 간격 분석 - ${tab.file.name}",
        frames = tab.gopFrames,
        isAnalyzing = tab.isAnalyzingFrames,
        fps = videoInfo?.fps,
        onCloseRequest = onCloseRequest,
    )
}

// Same shape as FrameIntervalAnalysisWindow above, but analyzes the Motion Photo's *embedded*
// video (tab.embeddedVideo, extracted to a temp file by analyzeMotionPhotoFrames) -- never the
// live preview player and never tab.file itself, which is the photo, not a video.
@Composable
fun MotionPhotoFrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab) {
        appState.analyzeMotionPhotoFrames(tab)
    }

    FrameIntervalAnalysisWindowContent(
        title = "모션포토 동영상 프레임 간격 분석 - ${tab.file.name}",
        frames = tab.motionPhotoGopFrames,
        isAnalyzing = tab.isAnalyzingMotionPhotoFrames,
        fps = tab.motionPhotoVideoFps,
        onCloseRequest = onCloseRequest,
    )
}

@Composable
private fun FrameIntervalAnalysisWindowContent(
    title: String,
    frames: List<FrameInfo>?,
    isAnalyzing: Boolean,
    fps: Double?,
    onCloseRequest: () -> Unit,
) {
    Window(onCloseRequest = onCloseRequest, title = title) {
        val intervals = remember(frames) { frames?.let { computeFrameIntervals(it) } ?: emptyList() }

        Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
            when {
                isAnalyzing || frames == null -> {
                    DecodingIndicator("프레임 분석 중...", modifier = Modifier.align(Alignment.Center))
                }
                intervals.isEmpty() -> {
                    Text(
                        "간격 정보 없음",
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                    )
                }
                else -> {
                    FrameIntervalAnalysisView(intervals = intervals, fps = fps, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile and run the full suite**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileKotlin
./gradlew :app:test
```
Expected: `compileKotlin` succeeds (confirms `FrameIntervalAnalysisWindow`'s existing call site in `Main.kt` still compiles unchanged against the new signature — its own signature didn't change, only its body). Full test suite passes, 0 failures, 0 regressions.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt
git commit -m "Split FrameIntervalAnalysisWindow into a shared content composable, add the Motion Photo variant"
```

---

### Task 3: Menu wiring in `Main.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (state declaration near line 141, `Menu("모션포토")` block at lines 147-159, window-open conditional block at lines 308-315)

**Interfaces:**
- Consumes: Task 2's `MotionPhotoFrameIntervalAnalysisWindow(appState, tab, onCloseRequest)`.

No test for this task — pure UI wiring in `Main.kt`, which this codebase's established convention does not unit-test (verified by compilation plus Task 4's manual verification, matching how the original "프레임 간격 분석" menu item itself was wired with no dedicated test).

- [ ] **Step 1: Add the new window-open state**

Find (around line 141):
```kotlin
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
```
Change to:
```kotlin
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
        var motionPhotoFrameIntervalWindowOpen by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the menu item under `Menu("모션포토")`**

Find (currently lines 147-159):
```kotlin
            Menu("모션포토") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "모션포토 동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    "모션포토 미리보기 재생용 비디오 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
            }
```

Replace with:
```kotlin
            Menu("모션포토") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "모션포토 동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    "모션포토 미리보기 재생용 비디오 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
                Item(
                    "모션포토 동영상 프레임 드랍 분석",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { motionPhotoFrameIntervalWindowOpen = true },
                )
            }
```

- [ ] **Step 3: Open the window when the flag is set**

Find (currently lines 308-315):
```kotlin
            if (frameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    FrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { frameIntervalWindowOpen = false })
                } else {
                    frameIntervalWindowOpen = false
                }
            }
```

Replace with:
```kotlin
            if (frameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    FrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { frameIntervalWindowOpen = false })
                } else {
                    frameIntervalWindowOpen = false
                }
            }
            if (motionPhotoFrameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    MotionPhotoFrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { motionPhotoFrameIntervalWindowOpen = false })
                } else {
                    motionPhotoFrameIntervalWindowOpen = false
                }
            }
```

- [ ] **Step 4: Compile and run the full suite**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileKotlin
./gradlew :app:test
```
Expected: both succeed, 0 failures, 0 regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Add 모션포토 동영상 프레임 드랍 분석 menu item"
```

---

### Task 4: Manual verification

**Files:** none (no code changes)

**Interfaces:** none

- [ ] **Step 1: Run the full suite one more time as a clean baseline**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test
```
Expected: full suite passes, 0 failures.

- [ ] **Step 2: Launch the app and verify against a real Motion Photo file**

```
./gradlew :app:run
```

Open a real Samsung or Google Motion Photo file (a `.jpg`/`.heic` with an embedded video — several were already used for verification in this session's earlier JPEG/image-formats/video Overview sub-projects). Confirm:
- With the Motion Photo tab selected, the "모션포토" menu's "모션포토 동영상 프레임 드랍 분석" item is enabled (not greyed out).
- Clicking it opens a new window titled "모션포토 동영상 프레임 간격 분석 - <filename>".
- The window shows a loading indicator briefly, then a real scatter plot + frame data table — not empty, not stuck loading.
- The frame count and intervals shown are plausible for the *embedded video specifically* (a short clip, typically a few seconds) — not the full photo file, not the live preview player's own timing.
- Open a plain (non-Motion-Photo) image or video file and confirm the same menu item is disabled (greyed out), and the existing "프레임 간격 분석 보기" item (for regular video files) still behaves exactly as before this plan.

- [ ] **Step 3: Report result**

If both checks pass, this plan is complete. If anything looks wrong, root-cause it (same discipline as the JPEG sub-project's Task 3 bug), fix, add a regression test if the root cause is in pure/testable logic, and re-verify before considering the plan done.
