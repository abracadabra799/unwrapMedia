# Bundle ffmpeg into Windows/Linux Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Windows and Linux, the packaged app works with zero separate ffmpeg install. This is sub-project B of the VLC-removal effort (A: `FfmpegVideoPlayer`, done · B: this plan · C: migrate call sites and remove vlcj).

**Architecture:** Task 1 adds `FfmpegLocator` (resolves the bundled binary path at runtime via `compose.application.resources.dir`, falling back to `PATH` lookup whenever that system property is unset or the bundled file isn't found there) and updates `FfmpegImageSnapshotDecoder`/`FfmpegVideoPlayer` to use it instead of literal `"ffmpeg"`/`"ffprobe"`. Task 2 wires the actual bundling: `build.gradle.kts`'s `appResourcesRootDir`, and two new CI steps that download static `ffmpeg`/`ffprobe` builds from `BtbN/FFmpeg-Builds` and stage them into `app/resources/windows/bin/` or `app/resources/linux/bin/` before packaging.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop (`appResourcesRootDir`), GitHub Actions (PowerShell on `windows-latest`, bash on `ubuntu-latest`). No new Gradle dependency.

## Global Constraints

- Windows/Linux only — macOS is explicitly out of scope (no equivalently trustworthy static-build source; `FfmpegLocator` always falls through to `PATH` there since no `app/resources/macos/` bundle is ever created).
- Binary source: `BtbN/FFmpeg-Builds`, pinned to the `n8.1` release branch (not the floating `master-latest`), **LGPL** variant (not GPL — avoids copyleft obligations on this app). Exact assets: `ffmpeg-n8.1-latest-win64-lgpl-8.1.zip`, `ffmpeg-n8.1-latest-linux64-lgpl-8.1.tar.xz`, both containing `bin/ffmpeg[.exe]` and `bin/ffprobe[.exe]` at their archive root (verified by downloading and inspecting both).
- Binaries are downloaded fresh by CI on every build, never committed to git (each is 100MB+; `app/resources/` must be gitignored).
- `FfmpegLocator`'s destination-path lookup checks two candidates (`bin/<name>` and `<name>` directly under `compose.application.resources.dir`) and logs via `println` when neither matches — the exact jpackage destination layout was not independently confirmed before this plan was written (see spec's Background), so this must degrade gracefully and be diagnosable from a real run's console output, not hard-fail or silently mis-resolve.
- Spec: `docs/superpowers/specs/2026-07-25-ffmpeg-bundling-design.md`.

---

### Task 1: `FfmpegLocator` and updating the two ffmpeg call sites

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FfmpegLocator.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/FfmpegLocatorTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (sub-project A's `FfmpegVideoPlayer.kt` is already merged).
- Produces: `FfmpegLocator.ffmpegPath(): String`, `FfmpegLocator.ffprobePath(): String` — consumed by this same task's two call-site updates. Task 2 doesn't consume these directly (it only stages files on disk for this task's code to find at runtime) but depends on this task being done first, since Task 2's manual verification step needs `FfmpegLocator` in place to have anything to verify.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/FfmpegLocatorTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegLocatorTest {
    @Test
    fun `ffmpegPath returns the literal command when compose_application_resources_dir is unset`() {
        System.clearProperty("compose.application.resources.dir")
        assertEquals("ffmpeg", FfmpegLocator.ffmpegPath())
        assertEquals("ffprobe", FfmpegLocator.ffprobePath())
    }

    @Test
    fun `ffmpegPath returns the bundled binary's absolute path when it exists under resources_dir slash bin`() {
        val resourcesDir = File.createTempFile("ffmpeg-locator-test-", "").apply { delete(); mkdirs() }
        val binDir = File(resourcesDir, "bin").apply { mkdirs() }
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val bundledName = if (isWindows) "ffmpeg.exe" else "ffmpeg"
        val bundled = File(binDir, bundledName).apply { writeText("fake binary") }

        System.setProperty("compose.application.resources.dir", resourcesDir.absolutePath)
        try {
            assertEquals(bundled.absolutePath, FfmpegLocator.ffmpegPath())
        } finally {
            System.clearProperty("compose.application.resources.dir")
            resourcesDir.deleteRecursively()
        }
    }

    @Test
    fun `ffmpegPath falls back to the literal command when resources_dir is set but the file is not there`() {
        val resourcesDir = File.createTempFile("ffmpeg-locator-test-empty-", "").apply { delete(); mkdirs() }

        System.setProperty("compose.application.resources.dir", resourcesDir.absolutePath)
        try {
            assertEquals("ffmpeg", FfmpegLocator.ffmpegPath())
        } finally {
            System.clearProperty("compose.application.resources.dir")
            resourcesDir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegLocatorTest" --console=plain`
Expected: Compile error — `FfmpegLocator` does not exist yet.

- [ ] **Step 3: Implement `FfmpegLocator`**

Create `app/src/main/kotlin/com/multiviewer/ui/FfmpegLocator.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File

/**
 * Resolves the ffmpeg/ffprobe executable to invoke: the bundled binary if this is a packaged
 * Windows/Linux build that shipped one (see the ffmpeg-bundling design), otherwise the literal
 * command name, resolved via PATH by ProcessBuilder exactly as before bundling existed. This is
 * always the PATH fallback in development (`./gradlew :app:run`, where the
 * `compose.application.resources.dir` system property jpackage sets is never present) and on
 * macOS (which this bundling design doesn't cover).
 */
object FfmpegLocator {
    fun ffmpegPath(): String = resolve(unixName = "ffmpeg", windowsName = "ffmpeg.exe")
    fun ffprobePath(): String = resolve(unixName = "ffprobe", windowsName = "ffprobe.exe")

    private fun resolve(unixName: String, windowsName: String): String {
        val resourcesDirPath = System.getProperty("compose.application.resources.dir") ?: return unixName
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val binaryName = if (isWindows) windowsName else unixName
        val resourcesDir = File(resourcesDirPath)
        val candidates = listOf(File(resourcesDir, "bin/$binaryName"), File(resourcesDir, binaryName))
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            println("FfmpegLocator: bundled $binaryName not found under $resourcesDirPath (checked: ${candidates.map { it.path }}); falling back to PATH")
        }
        return found?.absolutePath ?: unixName
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegLocatorTest" --console=plain`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Update `FfmpegImageSnapshotDecoder.kt` to use the locator**

Replace:

```kotlin
                val process = ProcessBuilder(
                    "ffmpeg", "-y", "-i", file.absolutePath,
                    "-frames:v", "1", "-update", "1",
                    tempPng.absolutePath,
                )
```

with:

```kotlin
                val process = ProcessBuilder(
                    FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                    "-frames:v", "1", "-update", "1",
                    tempPng.absolutePath,
                )
```

(No import needed — `FfmpegLocator` is in the same package, `com.multiviewer.ui`.)

- [ ] **Step 6: Update `FfmpegVideoPlayer.kt` to use the locator (two call sites)**

In `probeVideo`, replace:

```kotlin
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
```

with:

```kotlin
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
```

In the `@Composable`'s `DisposableEffect`, replace:

```kotlin
            ProcessBuilder(
                "ffmpeg", "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
                "-r", info.fps.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
```

with:

```kotlin
            ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
                "-r", info.fps.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
```

- [ ] **Step 7: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (confirms `FfmpegImageSnapshotDecoderTest`/`FfmpegVideoPlayerTest`, both of which invoke real `ffmpeg`/`ffprobe`, still work — on this dev machine `compose.application.resources.dir` is unset, so `FfmpegLocator` resolves to the same literal commands these tests already exercised before this change, meaning zero behavior change for local test runs).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegLocator.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegLocatorTest.kt app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "Add FfmpegLocator and route both ffmpeg call sites through it"
```

---

### Task 2: Bundle the binaries in CI and declare the resources root

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/package.yml`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `FfmpegLocator` (Task 1) — this task doesn't call it directly, but its whole purpose is to put files where Task 1's code will find them at runtime.
- Produces: nothing consumed by other tasks — this is the last task in this plan. Sub-project C (a separate, later plan) is unaffected by this task either way.

No automated test: this is CI/build-infrastructure configuration with no test harness in this project. Verification is a real CI run (confirms the workflow YAML and Gradle wiring don't error) followed by a manual install-and-test cycle on an actual Windows machine (confirms the bundling genuinely works end-to-end) — the same posture as the earlier Windows-shortcut fix in this same file.

- [ ] **Step 1: Add `app/resources/` to `.gitignore`**

In `/Users/dong.kim/AndroidStudioProjects/multiViewer/.gitignore`, add a new line at the end:

```
/app/resources/
```

- [ ] **Step 2: Declare `appResourcesRootDir` in `build.gradle.kts`**

In `app/build.gradle.kts`, replace:

```kotlin
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.0.0"

            windows {
```

with:

```kotlin
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
```

- [ ] **Step 3: Verify the Gradle config still evaluates cleanly**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:tasks --console=plain`
Expected: BUILD SUCCESSFUL (this confirms `appResourcesRootDir` is valid DSL even though `app/resources/` doesn't exist locally yet — Compose Desktop only requires the directory to exist at packaging time, not at every Gradle invocation; if this step fails with a "directory does not exist" error instead, create an empty `app/resources/.gitkeep`-style placeholder and note that in your report).

- [ ] **Step 4: Add the ffmpeg download/staging steps to the CI workflow**

In `.github/workflows/package.yml`, replace:

```yaml
      - name: Grant Execute Permission for Gradlew
        if: matrix.os != 'windows-latest'
        run: chmod +x gradlew

      - name: Package Distribution
        run: ./gradlew :app:packageDistributionForCurrentOS
```

with:

```yaml
      - name: Grant Execute Permission for Gradlew
        if: matrix.os != 'windows-latest'
        run: chmod +x gradlew

      - name: Download and bundle ffmpeg (Windows)
        if: matrix.os == 'windows-latest'
        shell: pwsh
        run: |
          Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-win64-lgpl-8.1.zip" -OutFile ffmpeg.zip
          Expand-Archive -Path ffmpeg.zip -DestinationPath ffmpeg-extracted
          New-Item -ItemType Directory -Force -Path app/resources/windows/bin | Out-Null
          Copy-Item (Get-ChildItem -Path ffmpeg-extracted -Recurse -Filter ffmpeg.exe).FullName app/resources/windows/bin/ffmpeg.exe
          Copy-Item (Get-ChildItem -Path ffmpeg-extracted -Recurse -Filter ffprobe.exe).FullName app/resources/windows/bin/ffprobe.exe

      - name: Download and bundle ffmpeg (Linux)
        if: matrix.os == 'ubuntu-latest'
        run: |
          curl -sL -o ffmpeg.tar.xz "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-linux64-lgpl-8.1.tar.xz"
          mkdir -p ffmpeg-extracted
          tar -xf ffmpeg.tar.xz -C ffmpeg-extracted --strip-components=1
          mkdir -p app/resources/linux/bin
          cp ffmpeg-extracted/bin/ffmpeg app/resources/linux/bin/ffmpeg
          cp ffmpeg-extracted/bin/ffprobe app/resources/linux/bin/ffprobe
          chmod +x app/resources/linux/bin/ffmpeg app/resources/linux/bin/ffprobe

      - name: Package Distribution
        run: ./gradlew :app:packageDistributionForCurrentOS
```

(No step is added for `macos-latest` — its `Package Distribution` step runs exactly as before, with no `app/resources/macos/` ever created.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .github/workflows/package.yml .gitignore
git commit -m "Bundle ffmpeg/ffprobe into Windows and Linux packages via CI"
```

- [ ] **Step 6: Push and trigger CI**

Run: `git push origin v2`
Expected: push succeeds (this doesn't touch `.github/workflows` scope restrictions from earlier today — wait, it does modify `.github/workflows/package.yml`, so if the push is rejected with `refusing to allow an OAuth App to create or update workflow ... without workflow scope`, that's the same known issue from earlier — ask the user to run `gh auth refresh -h github.com -s workflow` again, or add the workflow file change via the GitHub web UI as was done before).

- [ ] **Step 7: Verify the CI run succeeds and inspect the resulting MSI/DEB**

After CI completes (check `https://github.com/abracadabra799/unwrapMedia/actions`), confirm all three OS jobs (`windows-latest`, `ubuntu-latest`, `macos-latest`) succeed, and that the Windows/Linux `Package Distribution` steps' logs show no errors related to the new resources (a build failure here would mean `appResourcesRootDir` or the file staging didn't work as expected — read the actual error rather than guessing a fix).

- [ ] **Step 8: Manual end-to-end verification on Windows**

Download the new `unwrapMedia-windows` artifact from the CI run, install it on a Windows machine that does **not** have `ffmpeg` on `PATH`, then launch the app from a terminal (not by double-clicking — so `FfmpegLocator`'s `println` diagnostics are visible if something's wrong) and:
- Open a HEIC file — expect it to decode via the bundled ffmpeg (no "Primary Image Decoding Failed").
- Open a video file — expect it to play via the bundled ffmpeg/ffprobe (once `FfmpegVideoPlayer` is actually wired into the UI by sub-project C — if sub-project C hasn't happened yet, `VideoInspectorUI` will still be running on VLC, so this check may need to wait; the HEIC check alone is sufficient to confirm this task worked, since `FfmpegImageSnapshotDecoder` is already wired in today).

If the console shows `FfmpegLocator: bundled ffmpeg.exe not found under ...`, that confirms the destination-path guess from the design needs a third candidate added — read the printed path, find where the file actually landed (e.g. via File Explorer in the install directory), and report back rather than guessing again.
