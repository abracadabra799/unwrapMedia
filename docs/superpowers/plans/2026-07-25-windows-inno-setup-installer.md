# Windows Inno Setup Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Windows CI output switches from a bare jpackage MSI to an Inno Setup–built installer with "create desktop icon" and "launch app now" checkboxes (both checked by default), a real app icon, and a per-user install requiring no UAC prompt.

**Architecture:** jpackage is repointed from producing an MSI to producing a raw app-image (`createDistributable`); a new Inno Setup script wraps that app-image into the final installer `.exe`. A new `.ico` (generated from a checked-in SVG source) is wired into jpackage so the icon shows in the app-image regardless of installer. CI's Windows job gains one new step (compiling the `.iss` script) and two path changes (packaging command, upload path). macOS/Linux packaging is untouched.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop (Gradle plugin's `createDistributable`/jpackage), Inno Setup 6 (preinstalled on `windows-latest` GitHub Actions runners), `librsvg`'s `rsvg-convert` (icon generation, local/dev-machine only — not needed in CI since the generated `.ico` is checked into git).

## Global Constraints

- No change to macOS (`.dmg`) or Linux (`.deb`) packaging — jpackage continues to own those, unchanged.
- Windows-only icon in this pass (`app/icons/app.ico`) — no macOS/Linux icon.
- No silent/unattended install mode, no license page, no custom wizard branding beyond the app icon.
- Per-user install, no UAC prompt: `PrivilegesRequired=lowest`, `DefaultDirName={userpf}\unwrapMedia`.
- Both installer checkboxes ("create desktop icon", "launch app now") default to **checked**.
- The Inno Setup `AppId` GUID `2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D` must never change once shipped — it's what lets future versions upgrade in place instead of installing side-by-side.
- Spec: `docs/superpowers/specs/2026-07-25-windows-inno-setup-installer-design.md`.

---

### Task 1: App icon + Gradle packaging config

**Files:**
- Create: `app/icons/app.svg`
- Create: `app/icons/app.ico`
- Modify: `gradle.properties`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: nothing from other tasks (this is the first task).
- Produces: `app/icons/app.ico` (referenced by Task 2's Inno Setup script only as `SetupIconFile`/`UninstallDisplayIcon`, both by relative path — no code interface). `app/build.gradle.kts`'s `targetFormats` no longer includes `TargetFormat.Msi`, and the `windows { }` block only sets `iconFile` — Task 2 relies on this indirectly (CI must stop invoking any Msi-producing task, which Task 2 changes).

No automated test framework applies to build config or binary icon assets — verification is `./gradlew :app:createDistributable` succeeding locally and producing a real app-image directory, plus the full test suite still passing (proves nothing else silently depended on the removed `Msi` target format or the removed `windows { shortcut/menu/menuGroup }` lines).

- [ ] **Step 1: Write the icon source SVG**

Create `app/icons/app.svg`:

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <defs>
    <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
      <feGaussianBlur stdDeviation="6" result="blur"/>
      <feMerge>
        <feMergeNode in="blur"/>
        <feMergeNode in="SourceGraphic"/>
      </feMerge>
    </filter>
  </defs>

  <rect x="8" y="8" width="240" height="240" rx="52" fill="#12151A"/>
  <rect x="8" y="8" width="240" height="240" rx="52" fill="none" stroke="#30363D" stroke-width="2"/>

  <g filter="url(#glow)">
    <circle cx="104" cy="104" r="52" fill="none" stroke="#00F3FF" stroke-width="18"/>
    <line x1="145" y1="145" x2="192" y2="192" stroke="#00F3FF" stroke-width="22" stroke-linecap="round"/>
  </g>
</svg>
```

This matches `AppColors` from `app/src/main/kotlin/com/multiviewer/ui/Theme.kt` (`Background = 0xFF12151A`, `Border = 0xFF30363D`, `NeonBlue = 0xFF00F3FF`) — a dark rounded-square background with a neon-cyan magnifying glass, already approved by the user.

- [ ] **Step 2: Rasterize the SVG to each required size**

If `rsvg-convert` isn't installed yet: `brew install librsvg`

Run from the repo root:

```bash
mkdir -p /tmp/app-icon-build
for size in 16 32 48 64 128 256; do
  rsvg-convert -w $size -h $size app/icons/app.svg -o /tmp/app-icon-build/icon_$size.png
done
```

Expected: six PNG files created in `/tmp/app-icon-build/`, no errors.

- [ ] **Step 3: Pack the PNGs into a multi-resolution Windows .ico**

Run from the repo root:

```bash
python3 << 'EOF'
import struct

sizes = [16, 32, 48, 64, 128, 256]
images = []
for s in sizes:
    with open(f"/tmp/app-icon-build/icon_{s}.png", "rb") as f:
        images.append((s, f.read()))

num = len(images)
header = struct.pack("<HHH", 0, 1, num)

dir_entries = b""
data_blob = b""
offset = 6 + 16 * num

for s, data in images:
    w = s if s < 256 else 0
    h = s if s < 256 else 0
    entry = struct.pack("<BBBBHHII", w, h, 0, 0, 1, 32, len(data), offset)
    dir_entries += entry
    data_blob += data
    offset += len(data)

with open("app/icons/app.ico", "wb") as f:
    f.write(header)
    f.write(dir_entries)
    f.write(data_blob)

print("wrote app/icons/app.ico")
EOF
```

- [ ] **Step 4: Verify the .ico file is valid**

Run: `file app/icons/app.ico`
Expected: output starts with `app/icons/app.ico: MS Windows icon resource - 6 icons`

- [ ] **Step 5: Work around the Homebrew JDK vendor check for local packaging verification**

Compose Desktop's jpackage wrapper refuses to run packaging tasks (`createDistributable`, `packageDmg`, etc.) under a Homebrew-distributed JDK unless this is set — a false positive here since actual release builds run in CI under Temurin, not this flag. Without it, Step 7 below fails immediately with "Homebrew's JDK distribution may cause issues with packaging."

Append to `gradle.properties`:

```properties

# Homebrew's JDK distribution trips a jpackage vendor-string sanity check that's a false
# positive here; CI packages with Temurin, so this only affects local dev verification.
compose.desktop.packaging.checkJdkVendor=false
```

- [ ] **Step 6: Update `app/build.gradle.kts`**

Replace:

```kotlin
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                // Without these, jpackage's MSI installs the app with no Start Menu entry and
                // no desktop icon -- it's on disk but unreachable from the UI.
                shortcut = true
                menuGroup = "unwrapMedia"
                menu = true
            }
```

with:

```kotlin
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                // No jpackage-produced installer for Windows anymore (see targetFormats above) --
                // an Inno Setup script wraps the createDistributable app-image instead and owns
                // shortcuts/Start Menu/desktop icon. This icon still gets baked into the .exe
                // itself by jpackage regardless of which installer wraps it.
                iconFile.set(project.layout.projectDirectory.file("icons/app.ico"))
            }
```

(The `linux { shortcut = true }` block below it is unrelated and stays as-is.)

- [ ] **Step 7: Verify locally**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:createDistributable --console=plain`
Expected: `BUILD SUCCESSFUL`, and `app/build/compose/binaries/main/app/` contains a built app image (on macOS this is `unwrapMedia.app`; the equivalent Windows CI run will produce a `unwrapMedia/` folder containing `unwrapMedia.exe` — that folder layout is what Task 2's Inno Setup script reads from, verified end-to-end only when CI actually runs on `windows-latest`).

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing — confirms removing `Msi` from `targetFormats` and the old `windows { shortcut/menu/menuGroup }` lines didn't break anything else.

- [ ] **Step 8: Commit**

```bash
git add app/icons/app.svg app/icons/app.ico gradle.properties app/build.gradle.kts
git commit -m "Add Windows app icon, switch Windows packaging to app-image only"
```

---

### Task 2: Inno Setup installer script + CI wiring

**Files:**
- Create: `packaging/windows/installer.iss`
- Modify: `.github/workflows/package.yml`

**Interfaces:**
- Consumes: Task 1's `app/build/compose/binaries/main/app/unwrapMedia/` app-image directory (read by this task's `[Files]` section) and `app/icons/app.ico` (read by this task's `SetupIconFile`/`UninstallDisplayIcon`). Consumes Task 1's confirmation that `targetFormats` no longer includes `Msi`, meaning the CI workflow must not call any Msi-producing task anymore (this task changes that call).
- Produces: `packaging/windows/Output/unwrapMedia-Setup.exe` (the CI artifact Windows users download) — nothing else depends on this within the plan; it's the final deliverable.

No automated test exists for a native Windows installer wizard's UI behavior (documented in the spec's Testing section) — verification is a real GitHub Actions run on `windows-latest` producing the artifact, followed by manual install testing outside this plan's scope (tracked separately by the user).

- [ ] **Step 1: Write the Inno Setup script**

Create `packaging/windows/installer.iss`:

```ini
[Setup]
AppId={{2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D}
AppName=unwrapMedia
AppVersion=1.0.0
DefaultDirName={userpf}\unwrapMedia
DefaultGroupName=unwrapMedia
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=unwrapMedia-Setup
Compression=lzma2
SolidCompression=yes
SetupIconFile=..\..\app\icons\app.ico
UninstallDisplayIcon={app}\unwrapMedia.exe

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\..\app\build\compose\binaries\main\app\unwrapMedia\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs

[Icons]
Name: "{group}\unwrapMedia"; Filename: "{app}\unwrapMedia.exe"
Name: "{autodesktop}\unwrapMedia"; Filename: "{app}\unwrapMedia.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\unwrapMedia.exe"; Description: "{cm:LaunchProgram,unwrapMedia}"; Flags: nowait postinstall skipifsilent
```

Notes for whoever reviews this — every value here is deliberate, not a placeholder:
- `AppId`'s GUID must stay exactly `2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D` forever (see Global Constraints).
- No `unchecked` flag on the `[Tasks]` or `[Run]` entries — Inno Setup's default is checked, which is what both checkboxes need to be.
- `..\..\app\icons\app.ico` and `..\..\app\build\compose\binaries\main\app\unwrapMedia\*` are relative to this script's own location (`packaging/windows/`), pointing back up to `app/icons/app.ico` and Task 1's `createDistributable` output.

- [ ] **Step 2: Split the shared "Package Distribution" CI step by OS**

In `.github/workflows/package.yml`, replace:

```yaml
      - name: Package Distribution
        run: ./gradlew :app:packageDistributionForCurrentOS
```

with:

```yaml
      - name: Package Distribution (Windows)
        if: matrix.os == 'windows-latest'
        run: ./gradlew :app:createDistributable

      - name: Package Distribution (macOS/Linux)
        if: matrix.os != 'windows-latest'
        run: ./gradlew :app:packageDistributionForCurrentOS

      - name: Compile Windows Installer (Inno Setup)
        if: matrix.os == 'windows-latest'
        shell: pwsh
        run: |
          & "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" packaging\windows\installer.iss
```

`windows-latest` GitHub-hosted runners ship Inno Setup 6 preinstalled at that exact path (confirmed via GitHub's `runner-images` tool manifest). If a CI run reports `ISCC.exe` not found, that assumption was wrong for the image version in use — the fix is adding a `choco install innosetup -y` step before this one, not changing the script itself.

- [ ] **Step 3: Update the Windows artifact upload path**

In the same file, replace:

```yaml
      - name: Upload Artifacts (Windows)
        if: matrix.os == 'windows-latest'
        uses: actions/upload-artifact@v4
        with:
          name: unwrapMedia-windows
          path: app/build/compose/binaries/main/msi/*.msi
```

with:

```yaml
      - name: Upload Artifacts (Windows)
        if: matrix.os == 'windows-latest'
        uses: actions/upload-artifact@v4
        with:
          name: unwrapMedia-windows
          path: packaging/windows/Output/*.exe
```

- [ ] **Step 4: Commit**

```bash
git add packaging/windows/installer.iss .github/workflows/package.yml
git commit -m "Wrap Windows app-image with an Inno Setup installer in CI"
```

- [ ] **Step 5: Push**

Run: `git push origin v2`

If this is rejected for touching `.github/workflows/package.yml` (a recurring restriction this session — pushes that modify workflow files need a GitHub token `workflow` OAuth scope this session's token has repeatedly lacked): fall back to the pattern used every previous time this happened — paste the new `.github/workflows/package.yml` content into GitHub's web editor and commit there directly, then locally `git fetch origin v2` and `git rebase origin/v2` to reconcile, verifying the web-committed content matches this step's content exactly before continuing.

- [ ] **Step 6: Watch the CI run and confirm the new artifact**

Once pushed (by either path), find the run for this commit (`gh run list --branch v2 --limit 3`) and watch it (`gh run watch <run-id> --exit-status`).

Expected: the `Package for windows-latest` job succeeds through "Compile Windows Installer (Inno Setup)" and "Upload Artifacts (Windows)", and the `unwrapMedia-windows` artifact contains a `.exe` (not a `.msi`). `macos-latest`/`ubuntu-latest` jobs are unaffected and should also still succeed.

Manual installer verification (UAC prompt absence, checkbox behavior, icon, uninstall entry — per the spec's Testing section) happens separately, by the user, on a real Windows machine after this step — not part of this plan's automated verification.
