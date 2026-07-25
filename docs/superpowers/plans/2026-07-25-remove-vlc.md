# Remove VLC/vlcj, Migrate to FfmpegVideoPlayer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both `VideoInspectorUI` and the Motion Photo video panel use `FfmpegVideoPlayer` instead of `VlcVideoPlayer`; `VlcVideoPlayer.kt` and the `vlcj` Gradle dependency are deleted. This is sub-project C, the last of the three-part VLC-removal effort (A: `FfmpegVideoPlayer`, done · B: ffmpeg bundling, done · C: this plan).

**Architecture:** Two one-line call-site swaps (same package, no import changes), then delete the now-unused file and dependency.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop. Net dependency change: removes `uk.co.caprica:vlcj:4.12.1`, adds nothing.

## Global Constraints

- No behavior change beyond the decoder swap — layout, labels, surrounding UI untouched.
- `FfmpegVideoPlayer.kt` (sub-project A) and `FfmpegLocator`/bundling (sub-project B) are not modified — only their two call sites change.
- Confirmed by full-codebase search: exactly two call sites (`VideoInspectorUI.kt`, `ImageInspectorUI.kt`), one file to delete (`VlcVideoPlayer.kt`), one Gradle dependency line to remove. No test file references `vlcj`/`VlcVideoPlayer`.
- Spec: `docs/superpowers/specs/2026-07-25-remove-vlc-design.md`.

---

### Task 1: Swap call sites and remove VLC entirely

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Delete: `app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier)` (sub-project A, already merged, same package — no import needed).
- Produces: nothing consumed by other tasks — this is the only task in this plan, and the last task in the whole VLC-removal effort.

No automated test: neither call site's Composable has existing test coverage (established convention). Verification is the full test suite compiling cleanly (proves removing the `vlcj` dependency doesn't break anything else) plus manual checks.

- [ ] **Step 1: Swap the call site in `VideoInspectorUI.kt`**

Replace:

```kotlin
                    VlcVideoPlayer(tab.file)
```

with:

```kotlin
                    FfmpegVideoPlayer(tab.file)
```

- [ ] **Step 2: Swap the call site in `ImageInspectorUI.kt`**

Replace:

```kotlin
    if (file != null) {
        VlcVideoPlayer(file, modifier = Modifier.fillMaxSize())
    } else if (error != null) {
```

with:

```kotlin
    if (file != null) {
        FfmpegVideoPlayer(file, modifier = Modifier.fillMaxSize())
    } else if (error != null) {
```

- [ ] **Step 3: Delete `VlcVideoPlayer.kt`**

```bash
git rm app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt
```

- [ ] **Step 4: Remove the `vlcj` dependency**

In `app/build.gradle.kts`, replace:

```kotlin
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

with:

```kotlin
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 5: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed — confirms both files compile cleanly against `FfmpegVideoPlayer` and that removing `vlcj` doesn't break anything else in the codebase.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/build.gradle.kts
git commit -m "Replace VlcVideoPlayer with FfmpegVideoPlayer, remove vlcj dependency"
```

- [ ] **Step 7: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors.

- [ ] **Step 8: Manually verify on macOS**

Open a standalone video file — confirm `VideoInspectorUI`'s "LIVE PLAYER" panel plays it (decoding placeholder, then play/pause working), with no `VLC Engine:`-prefixed console output at all (that println prefix only existed in the now-deleted `VlcVideoPlayer.kt`). Open a Motion Photo file — confirm the "MOTION PHOTO VIDEO" panel plays the extracted clip the same way.

- [ ] **Step 9: Push and get a fresh Windows/Linux build**

Run: `git push origin v2` (if this is rejected for touching `.github/workflows/` — it isn't, this task doesn't touch that file — the push should go through normally).

Once CI completes, this is the real test: download the new Windows build and, on the same machine that had the VLC regression (with **neither VLC nor a system ffmpeg install present**, relying solely on the bundled binaries from sub-project B), confirm both video playback paths (standalone video tab, Motion Photo panel) now work.
