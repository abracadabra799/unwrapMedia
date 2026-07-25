# Bundle ffmpeg into Windows/Linux Packages (Sub-project B of VLC Removal) — Design

## Background

Sub-project A built `FfmpegVideoPlayer` (an ffmpeg-subprocess-based replacement for `VlcVideoPlayer`), but it — like the existing `FfmpegImageSnapshotDecoder` (HEIC fallback decoding) — still calls the literal `"ffmpeg"`/`"ffprobe"` commands, resolved via the system `PATH`. Deploying to Windows/Linux means end users need `ffmpeg` installed separately, which defeats the whole point of moving off VLC (bundling VLC's full plugin runtime was ruled out earlier today for being much larger/more fragile than bundling one tool).

Two things were verified empirically before writing this spec:

- **Binary source**: `BtbN/FFmpeg-Builds` (a GitHub project, daily automated builds, stable download URLs via its `latest` release tag) provides static Windows and Linux builds. The exact assets used: `ffmpeg-n8.1-latest-win64-lgpl-8.1.zip` and `ffmpeg-n8.1-latest-linux64-lgpl-8.1.tar.xz` — pinned to the `n8.1` release branch (not the floating `master-latest`, which tracks ffmpeg's git master and could introduce breaking changes without warning) and the **LGPL** variant (not GPL — LGPL doesn't impose copyleft on the app bundling it; a GPL ffmpeg build would legally require this app itself to be GPL-licensed). Downloaded and inspected both: `bin/ffmpeg[.exe]` and `bin/ffprobe[.exe]` inside the archive root. **Size cost**: ~113MB per binary on Windows (both together ≈ 220MB added to the MSI) — this is the accepted cost of a full-featured static build; no attempt is made to build a trimmed/minimal ffmpeg in this design.
- **macOS is explicitly out of scope for this sub-project** (confirmed with the user) — no equivalently trustworthy, CI-automatable static build source exists for macOS (the two known sources, `evermeet.cx` and `osxexperts.net`, are individually-run sites without the same automated-build/checksum posture as BtbN). macOS continues to resolve `ffmpeg`/`ffprobe` via `PATH` for now (typically Homebrew on a dev machine), exactly as today.
- **Bundling mechanism**: Compose Multiplatform Desktop's `nativeDistributions.appResourcesRootDir` DSL property, pointed at a `resources/` directory containing OS-specific subfolders (`resources/windows/...`, `resources/linux/...`) whose contents are included only when packaging for that OS.
- **Runtime resolution**: `System.getProperty("compose.application.resources.dir")` is set (by the jpackage-generated launcher) only when running a *packaged* app — it is `null` during `./gradlew :app:run` (development), which is the mechanism this design uses to fall back to `PATH` lookup during development without any special-casing.

One thing **not** independently verified: the exact final on-disk path bundled resource files land at inside the packaged app (relative to `compose.application.resources.dir`) — documentation describes the *source-side* `resources/<os>/...` selection mechanism, not the *destination-side* layout precisely enough to hardcode with full confidence. This design compensates by checking multiple plausible destination paths at runtime (see below) and logging which one (if any) was found, rather than assuming a single path and failing silently if wrong — the same "verify, don't just assume" posture used throughout today's work (the Windows shortcut fix and the drag-and-drop fix both needed a real-environment round-trip to nail down an exact mechanism no amount of documentation-reading alone confirmed).

## Goal

On Windows and Linux, the packaged app (MSI/DEB) works with **zero separate ffmpeg install** — HEIC decoding (`FfmpegImageSnapshotDecoder`) and video playback (`FfmpegVideoPlayer`) both transparently use the bundled binaries. On macOS and in local development (`./gradlew :app:run` on any OS), behavior is unchanged — both still resolve `ffmpeg`/`ffprobe` via `PATH`.

## Non-Goals

- No macOS bundling (see Background) — deferred to a future increment if a trustworthy source is found.
- No architecture variants beyond x64 (`win64`/`linux64`) — ARM Windows/Linux users still need `PATH`-installed ffmpeg. Not a regression (nothing bundled for any architecture today), just not extending coverage that far yet.
- No attempt to shrink the static build (strip unused codecs/filters to reduce the ~220MB Windows footprint) — accepted cost for this iteration.
- No change to `FfmpegVideoPlayer`'s or `FfmpegImageSnapshotDecoder`'s actual decode/playback logic — only how they locate the `ffmpeg`/`ffprobe` executable.

## Design

### 1. `FfmpegLocator.kt` (new file, `com.multiviewer.ui`)

```kotlin
package com.multiviewer.ui

import java.io.File

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

- Returns the literal `"ffmpeg"`/`"ffprobe"` (resolved via `PATH` by `ProcessBuilder`, exactly like today) whenever: the `compose.application.resources.dir` system property isn't set (dev mode, or macOS even when packaged — macOS never gets bundled binaries in this design, so this path is always taken there), or the property is set but neither candidate file exists at runtime (bundling misconfigured, or this exact machine/OS wasn't a bundling target).
- Checks two candidate destination paths (`bin/<name>` and `<name>` directly) specifically because the destination-side layout isn't independently confirmed (see Background) — whichever the real jpackage output uses, this resolves correctly without needing a code change; if *neither* matches, the diagnostic `println` (this codebase's established pattern for startup/runtime diagnostics — see `Main.kt`'s `LaunchedEffect(Unit) { println("OS: ...") }`) records exactly what was checked, so a real Windows/Linux test run's console output immediately shows why bundling didn't take effect, rather than a silent, hard-to-diagnose fallback.
- Windows detection uses `os.name` containing `"Windows"`, matching the existing `VlcVideoPlayer.kt` pattern for OS-conditional logic (`--no-videotoolbox` gating) rather than introducing a new convention.

### 2. Update the two existing ffmpeg call sites

`FfmpegImageSnapshotDecoder.kt`: replace the literal `"ffmpeg"` in its `ProcessBuilder(...)` call with `FfmpegLocator.ffmpegPath()`.

`FfmpegVideoPlayer.kt`: replace the literal `"ffprobe"` in `probeVideo`'s `ProcessBuilder(...)` call with `FfmpegLocator.ffprobePath()`, and the literal `"ffmpeg"` in the raw-frame-piping `ProcessBuilder(...)` call (inside the `@Composable`) with `FfmpegLocator.ffmpegPath()`.

### 3. `app/build.gradle.kts` — declare the resources root

```kotlin
nativeDistributions {
    targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
    packageName = "unwrapMedia"
    packageVersion = "1.0.0"
    appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

    windows { ... }  // unchanged, from the earlier shortcut fix
    linux { ... }    // unchanged
}
```

`app/resources/` (new directory) is populated by CI immediately before packaging — not checked into git (the binaries are ~110MB+ each; committing them would permanently bloat repository size and history). `app/resources/` (or a more specific `app/resources/windows/` and `app/resources/linux/`) is added to `.gitignore`.

### 4. `.github/workflows/package.yml` — download and stage the binaries per OS

Two new steps, each gated to its OS via `if:` (matching the workflow's existing per-OS step pattern, e.g. `Upload Artifacts (Windows)`), inserted before the existing `Package Distribution` step:

```yaml
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
```

No step is added for `macos-latest` — its `Package Distribution` step runs unchanged, with no `app/resources/macos/` directory ever created, so `FfmpegLocator` always falls through to `PATH` there (matches today's behavior exactly, confirmed as the intended Non-Goal).

## Testing

- Unit: `FfmpegLocatorTest` — with `compose.application.resources.dir` unset, `ffmpegPath()`/`ffprobePath()` return the literal `"ffmpeg"`/`"ffprobe"`; with it set (via `System.setProperty` in a `try`/`finally` that clears it afterward, to avoid leaking state into other tests) to a temp directory containing a dummy `bin/ffmpeg` file, `ffmpegPath()` returns that file's absolute path; with it set to a temp directory that does *not* contain the expected file, falls back to the literal `"ffmpeg"`.
- No automated test for the CI workflow YAML itself or the Gradle resource-bundling wiring — this class of infrastructure change has no test harness in this project (matching how the earlier Windows-shortcut and workflow-scope fixes were also verified manually, not via automated test) and can only be truly confirmed by a real CI run followed by an actual install-and-test cycle on Windows.
- Manual (after CI produces a new MSI): fresh install on a Windows machine (or VM) with **no ffmpeg on `PATH`**, then open a HEIC file (should decode via the bundled ffmpeg) and a video file (should play via the bundled ffmpeg/ffprobe) — this is the only test that actually proves the bundling worked end-to-end, and it's the step most likely to reveal that `FfmpegLocator`'s destination-path guess needs adjusting (in which case the console log's `FfmpegLocator: bundled ... not found ...` line, visible via running the installed `.exe` from a terminal rather than double-clicking it, tells us exactly what path to add as a third candidate).
