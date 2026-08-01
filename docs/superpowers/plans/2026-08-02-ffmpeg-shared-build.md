# Ffmpeg Shared-Build Packaging Size Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the packaged Windows/Linux installer size roughly in half by switching bundled ffmpeg/ffprobe from BtbN's static LGPL build to the shared LGPL build, fix the CI workflow's stale `v2`-only trigger, and add a runtime smoke test so a broken shared-library resolution fails loudly in CI instead of silently shipping.

**Architecture:** A single-file change to `.github/workflows/package.yml` -- no Kotlin/application code changes, since `FfmpegLocator.kt` only resolves the executable's path and shared-library resolution happens entirely at the OS/binary level (confirmed by downloading and inspecting the real BtbN archives).

**Tech Stack:** GitHub Actions YAML, PowerShell (Windows job), bash (Linux job). No new dependencies.

## Global Constraints

- Windows: copy the entire extracted `bin/` directory (executables + DLLs together) except `ffplay.exe` -- confirmed empirically that BtbN's Windows shared build puts all DLLs in the same directory as the executables, and Windows searches an executable's own directory for DLLs by default.
- Linux: copy `ffmpeg`/`ffprobe` into `app/resources/linux/bin/` (unchanged from today) AND the entire `lib/` directory (preserving symlinks) into a new sibling `app/resources/linux/lib/` -- confirmed via the binary's own embedded build config string (`-Wl,-rpath=$ORIGIN -Wl,-rpath=$ORIGIN/../lib`) that `lib/` must be a sibling of the directory containing the binary.
- `ffplay` must never be bundled on either platform -- this app never invokes it (only `"ffmpeg"`/`"ffprobe"` are ever resolved by `FfmpegLocator.kt`), and it's 18-20MB of pure waste per platform.
- `app/build.gradle.kts` must NOT be touched -- `appResourcesRootDir` already points at `app/resources/`, and Compose Desktop's packaging already copies everything under `<root>/<os>/` wholesale, so the new `lib/` subdirectory needs no build-config change.
- The CI trigger must point at `main`, not `v2` -- `v2` is no longer the active development branch.
- A runtime smoke test (`ffmpeg -version` against the actual packaged binary, located via `find`/`Get-ChildItem -Recurse` rather than a hardcoded jpackage path) must run for Windows and Linux, after packaging and before the artifact-upload steps, so a broken build fails the CI job instead of silently uploading one.
- macOS's job is untouched -- it has never bundled ffmpeg and continues to rely on `PATH`.

---

### Task 1: Update `.github/workflows/package.yml`

**Files:**
- Modify: `.github/workflows/package.yml` (entire file replaced -- short file, multiple non-contiguous changes)

**Interfaces:**
- Consumes: nothing (this is the leaf of the change -- no other file references this workflow's internals).
- Produces: nothing consumed by a later task -- Task 2 verifies this task's output by triggering the real workflow.

- [ ] **Step 1: Replace the whole file**

Replace the entire contents of `.github/workflows/package.yml` with:

```yaml
name: Package unwrapMedia
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
jobs:
  package:
    name: Package for ${{ matrix.os }}
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [windows-latest, ubuntu-latest, macos-latest]
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Grant Execute Permission for Gradlew
        if: matrix.os != 'windows-latest'
        run: chmod +x gradlew

      - name: Download and bundle ffmpeg (Windows)
        if: matrix.os == 'windows-latest'
        shell: pwsh
        run: |
          Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip" -OutFile ffmpeg.zip
          Expand-Archive -Path ffmpeg.zip -DestinationPath ffmpeg-extracted
          New-Item -ItemType Directory -Force -Path app/resources/windows/bin | Out-Null
          $ffmpegExe = (Get-ChildItem -Path ffmpeg-extracted -Recurse -Filter ffmpeg.exe).FullName
          $sourceBinDir = Split-Path $ffmpegExe -Parent
          Copy-Item "$sourceBinDir\*" -Destination app/resources/windows/bin/ -Exclude ffplay.exe

      - name: Download and bundle ffmpeg (Linux)
        if: matrix.os == 'ubuntu-latest'
        run: |
          curl -sL -o ffmpeg.tar.xz "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-linux64-lgpl-shared-8.1.tar.xz"
          mkdir -p ffmpeg-extracted
          tar -xf ffmpeg.tar.xz -C ffmpeg-extracted --strip-components=1
          mkdir -p app/resources/linux/bin app/resources/linux/lib
          cp ffmpeg-extracted/bin/ffmpeg app/resources/linux/bin/ffmpeg
          cp ffmpeg-extracted/bin/ffprobe app/resources/linux/bin/ffprobe
          cp -P ffmpeg-extracted/lib/*.so* app/resources/linux/lib/
          chmod +x app/resources/linux/bin/ffmpeg app/resources/linux/bin/ffprobe

      - name: Package Distribution (Windows)
        if: matrix.os == 'windows-latest'
        run: ./gradlew :app:createDistributable

      - name: Package Distribution (macOS/Linux)
        if: matrix.os != 'windows-latest'
        run: ./gradlew :app:packageDistributionForCurrentOS

      - name: Smoke-test bundled ffmpeg (Windows)
        if: matrix.os == 'windows-latest'
        shell: pwsh
        run: |
          $bundledFfmpeg = (Get-ChildItem -Path app/build/compose/binaries -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
          if (-not $bundledFfmpeg) { throw "Could not find a packaged ffmpeg.exe under app/build/compose/binaries" }
          & $bundledFfmpeg -version

      - name: Smoke-test bundled ffmpeg (Linux)
        if: matrix.os == 'ubuntu-latest'
        run: |
          BUNDLED_FFMPEG=$(find app/build/compose/binaries -type f -name ffmpeg | head -1)
          if [ -z "$BUNDLED_FFMPEG" ]; then
            echo "Could not find a packaged ffmpeg binary under app/build/compose/binaries" >&2
            exit 1
          fi
          "$BUNDLED_FFMPEG" -version

      - name: Compile Windows Installer (Inno Setup)
        if: matrix.os == 'windows-latest'
        shell: pwsh
        run: |
          & "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" packaging\windows\installer.iss

      - name: Upload Artifacts (Windows)
        if: matrix.os == 'windows-latest'
        uses: actions/upload-artifact@v4
        with:
          name: unwrapMedia-windows
          path: packaging/windows/Output/*.exe

      - name: Upload Artifacts (Linux)
        if: matrix.os == 'ubuntu-latest'
        uses: actions/upload-artifact@v4
        with:
          name: unwrapMedia-linux
          path: app/build/compose/binaries/main/deb/*.deb

      - name: Upload Artifacts (macOS)
        if: matrix.os == 'macos-latest'
        uses: actions/upload-artifact@v4
        with:
          name: unwrapMedia-macos
          path: app/build/compose/binaries/main/dmg/*.dmg
```

- [ ] **Step 2: Validate YAML syntax locally**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/package.yml'))" && echo "YAML is syntactically valid"`
Expected: `YAML is syntactically valid` (this only checks the file parses as YAML -- it cannot validate GitHub Actions semantics like `${{ }}` expressions or step ordering; Task 2 is the real test, via an actual workflow run).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/package.yml
git commit -m "ci: bundle ffmpeg's shared build instead of static, fix stale v2 trigger, add smoke test"
```

---

### Task 2: Trigger and verify the real CI run (controller-performed)

No automated coverage is possible for this task -- it requires a real GitHub Actions run on real Windows/Linux/macOS runners, which only exists once Task 1 is pushed. This step is performed by the controller directly (using the `gh` CLI, already available and authenticated in this session), not dispatched to a subagent.

- [ ] Push Task 1's commit (it's already going to `main`, which is now the trigger branch) and confirm a new workflow run started: `gh run list --workflow=package.yml --limit 3`
- [ ] Watch the run to completion: `gh run watch <run-id>` (or `gh run list` again after a few minutes) -- confirm all three matrix jobs (`windows-latest`, `ubuntu-latest`, `macos-latest`) succeed
- [ ] Specifically inspect the two new smoke-test steps' logs (`gh run view <run-id> --log` or the Actions web UI) for both Windows and Linux -- confirm `ffmpeg -version` printed real version output, not a dynamic-linker/DLL-not-found error
- [ ] Download the Windows and Linux artifacts (`gh run download <run-id>`) and compare their sizes against the pre-change baseline (roughly half, per the measured raw archive sizes: ~145MB→~70MB static-to-shared delta for Windows, ~112MB→~57MB for Linux, though the final installer/deb size also includes the JRE/app code so the percentage reduction on the whole artifact will be smaller than the raw ffmpeg delta alone -- record the actual before/after numbers)
- [ ] If any job fails or the smoke test reports a linker error, treat it as a real bug -- return to systematic-debugging (this is exactly the scenario the smoke test exists to catch), don't just retry hoping it passes
- [ ] Report the final artifact sizes and CI run link back to the user -- they still need to do their own install-and-play-a-video test on real hardware, which this task does not replace

---

## Self-Review Notes

- **Spec coverage:** shared-build switch for both platforms ✅ (Task 1), `ffplay` exclusion on both platforms ✅ (Task 1), Linux `lib/` sibling directory with symlink preservation ✅ (Task 1), no `FfmpegLocator.kt`/`build.gradle.kts` changes ✅ (confirmed nothing in Task 1 touches either file), CI trigger fix (`v2`→`main`) ✅ (Task 1), runtime smoke test for Windows and Linux ✅ (Task 1), real-CI verification ✅ (Task 2).
- **Placeholder scan:** none found.
- **Type consistency:** N/A -- this plan has no Kotlin interfaces/signatures to keep consistent across tasks; Task 2 verifies Task 1's YAML output by running it for real, not by consuming a code interface.
