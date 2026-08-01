# Ffmpeg Shared-Build Packaging Size Reduction Design

## Goal

Cut the packaged Windows/Linux installer size roughly in half by switching the bundled ffmpeg/ffprobe binaries from BtbN's static LGPL build to the shared LGPL build, and fix the CI workflow's stale branch trigger and lack of any runtime smoke test.

## Background

`.github/workflows/package.yml` downloads BtbN's static ffmpeg build for Windows and Linux and copies just the `ffmpeg`/`ffprobe` executables into `app/resources/{windows,linux}/bin/`. Since the app bundles both binaries side by side, and each statically links its own full copy of libavcodec/libavformat/etc., this duplicates ~70-140MB of library code per platform that a shared build would only include once. Measured directly from the real GitHub release assets: static Windows = 145MB / shared = 70MB; static Linux = 112MB / shared = 57MB (roughly 2x each).

Both archives were downloaded and inspected directly (not assumed from documentation) to confirm exactly what changes:
- **Windows shared build**: `bin/` contains the executables and all DLLs together in one directory. Windows searches an executable's own directory for DLLs by default, so copying the whole `bin/` folder is sufficient -- no code change needed.
- **Linux shared build**: `bin/` (executables) and `lib/` (`.so` files, with the usual `libX.so -> libX.so.MAJOR -> libX.so.MAJOR.MINOR.PATCH` symlink chain) are separate directories. The actual embedded build configuration string in the `ffmpeg` binary confirms `-Wl,-rpath=$ORIGIN -Wl,-rpath=$ORIGIN/../lib` -- so `lib/` must be a sibling of the directory containing the binary at runtime.
- Both builds also include `ffplay` (an unused executable this app never invokes -- confirmed via `FfmpegLocator.kt`, which only ever resolves `"ffmpeg"`/`"ffprobe"`), 18-20MB per platform, safe to drop from the bundle.

`FfmpegLocator.kt` only resolves the *executable's* path; shared-library resolution happens entirely at the OS/binary level (Windows same-directory search, Linux RPATH) with zero Kotlin involvement -- so this is a CI/packaging-script-only change, no application code changes.

Separately (found while reading the workflow file to make this change): `package.yml` only triggers on pushes/PRs to the `v2` branch, which no longer exists as the active branch now that it's been merged into `main`. Pushing this fix to `main` would silently never run unless the trigger is also updated.

## Design

### A. Switch to the shared build and copy both `bin/` and `lib/`

**Windows** (`package.yml`'s Windows ffmpeg step): download `ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip` instead of the `-lgpl-` (static) variant. Instead of cherry-picking `ffmpeg.exe`/`ffprobe.exe` by name (today's approach, which breaks for the shared build since the DLLs also need copying), locate the extracted `bin/` directory (via the same `Get-ChildItem -Recurse` pattern already used, robust against BtbN's exact top-level folder naming) and copy its entire contents except `ffplay.exe`.

**Linux** (`package.yml`'s Linux ffmpeg step): download `ffmpeg-n8.1-latest-linux64-lgpl-shared-8.1.tar.xz` instead of the static variant. Keep copying `ffmpeg`/`ffprobe` by name into `app/resources/linux/bin/` (as today -- this already excludes `ffplay`), but additionally copy the entire `lib/` directory (preserving symlinks, `cp -P`) into a new `app/resources/linux/lib/` sibling directory. `appResourcesRootDir` in `app/build.gradle.kts` already points at `app/resources/`, and Compose Desktop's packaging copies everything under `<root>/<os>/` wholesale -- no `build.gradle.kts` change needed for the new `lib/` subdirectory to be included.

### B. Fix the stale CI trigger

Change `package.yml`'s `on: push/pull_request: branches:` from `[v2]` to `[main]`, matching the branch the project now actually develops on.

### C. Add a runtime smoke test

After packaging (before the Windows Inno Setup step / artifact uploads), add a step per platform that finds the actual bundled `ffmpeg` binary inside the just-built output tree (via `find`/`Get-ChildItem -Recurse`, not a hardcoded jpackage path, since that layout isn't independently confirmed) and runs it with `-version`. If Linux's RPATH resolution or Windows' DLL search were broken by this change, this fails loudly and immediately in CI with a clear dynamic-linker error, instead of silently shipping a broken artifact that only fails when a user actually tries to play a video. This does not fully replace a real end-user install test (it doesn't exercise the app's own `ProcessBuilder`/`FfmpegLocator` invocation path, just confirms the OS loader can load the binary from its packaged location), but it catches the exact class of packaging mistake this change risks.

## Non-Goals

- Any change to `FfmpegLocator.kt` or any other Kotlin source -- confirmed unnecessary, since shared-library resolution is entirely OS/binary-level.
- Trimming which of ffmpeg's own linked libraries (avdevice, avfilter, etc.) are bundled -- BtbN's shared build is used as a coherent, complete redistributable unit; only the separately-invokable, provably-unused `ffplay` executable is dropped.
- macOS packaging -- untouched; it has never bundled ffmpeg and continues to rely on `PATH`.
- A full real end-user install-and-play-a-video test on physical/VM Windows and Linux machines -- the CI smoke test in this plan raises confidence significantly but the user's own manual install test (mentioned separately) remains the final check.

## Testing

- No Kotlin unit tests apply (this is a CI/packaging-script-only change).
- Verification is: trigger the real GitHub Actions workflow (now that its branch trigger is fixed) and confirm all three platform jobs succeed, specifically watching the new smoke-test step's output for both Windows and Linux, and comparing the uploaded artifact sizes against the ~50% reduction predicted from the measured raw archive sizes.
