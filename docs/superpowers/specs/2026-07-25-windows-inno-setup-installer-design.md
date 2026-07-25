# Windows Installer UX: Inno Setup Wrapper — Design

## Background

The Windows build currently ships as a bare jpackage MSI. jpackage's default WiX-based MSI wizard has no "create desktop icon" or "launch app now" checkboxes, and after install it just closes — the app isn't running and there's no obvious way to find it, creating a noticeable UX gap and delay before first launch. jpackage also has no icon configured for Windows, so the installed app currently shows the default Java coffee-cup icon everywhere (Start Menu, taskbar, exe).

## Goal

Windows installs go through an Inno Setup–generated wizard that offers the standard "create desktop icon" (checked by default) and "launch application now" (checked by default) checkboxes, installs per-user with no UAC prompt, and shows a real app icon throughout. The MSI output is retired entirely in favor of this EXE.

## Non-Goals

- No change to macOS (`.dmg`) or Linux (`.deb`) packaging — jpackage continues to own those, unchanged.
- No icon for macOS/Linux in this pass — Windows `.ico` only.
- No silent/unattended install mode, no license-agreement page, no custom wizard branding beyond the app icon.

## Design

### Icon

`app/icons/app.ico` — a 6-resolution (16/32/48/64/128/256px) Windows icon generated from a purpose-built SVG (dark rounded-square background `#12151A`, neon-cyan `#00F3FF` magnifying glass with a soft glow, matching `AppColors` from `Theme.kt`). Approved by the user for initial use.

The icon is a swappable asset: replacing `app/icons/app.ico` with any other valid `.ico` (e.g. regenerated from a user-supplied PNG) is a one-file change with no effect on the rest of this design. Not part of this plan's scope — can happen anytime after.

Wired into `app/build.gradle.kts`'s `compose.desktop.application.nativeDistributions.windows` block via `iconFile.set(...)`, so it's baked into `unwrapMedia.exe` by jpackage regardless of which installer wraps it.

### jpackage: app-image only, no more MSI

`app/build.gradle.kts` changes:
- `targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)` → `targetFormats(TargetFormat.Dmg, TargetFormat.Deb)` (Windows no longer gets a jpackage-produced installer format at all).
- `windows { shortcut = true; menuGroup = "unwrapMedia"; menu = true }` removed — those controlled jpackage's own MSI wizard, which no longer runs. Replaced with `windows { iconFile.set(project.layout.projectDirectory.file("icons/app.ico")) }`.
- `linux { shortcut = true }` untouched.

CI's Windows job now runs `./gradlew :app:createDistributable` instead of `:app:packageDistributionForCurrentOS`. This is the Gradle task Compose Desktop always exposes for producing a raw jpackage app-image (`app/build/compose/binaries/main/app/unwrapMedia/`, containing `unwrapMedia.exe`, bundled JRE, and the ffmpeg/ffprobe resources from sub-project B) without wrapping it in any installer. macOS/Linux CI jobs keep calling `packageDistributionForCurrentOS` unchanged (their formats, Dmg/Deb, are still in `targetFormats`).

### Inno Setup script

New file: `packaging/windows/installer.iss`.

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

Notes on values that must stay fixed:
- `AppId`'s GUID (`2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D`) was freshly generated for this app and must never change — Inno Setup uses it to recognize "this is an upgrade of the same app" across versions. Changing it would cause future installs to stack up as separate, uninstall-orphaned entries instead of upgrading in place.
- `PrivilegesRequired=lowest` + `DefaultDirName={userpf}\unwrapMedia` together give the no-UAC, per-user install the user asked for (`{userpf}` resolves to the current user's local `Programs` folder, the same pattern apps like VS Code and Discord use).
- Both `[Tasks]` (desktop icon) and `[Run]` (launch now) checkboxes default to checked — Inno Setup's default when no `unchecked` flag is present — per the user's explicit choice.

An uninstaller and an Add/Remove Programs entry (showing the app icon, via `UninstallDisplayIcon`) are generated automatically by Inno Setup; no extra config needed.

### CI (`.github/workflows/package.yml`)

Windows job changes only:
- "Package Distribution" step's Windows path becomes `./gradlew :app:createDistributable` (mac/linux steps unchanged, still `packageDistributionForCurrentOS`).
- New step, Windows-only, after packaging: compile the installer with `& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" packaging\windows\installer.iss`. `windows-latest` GitHub-hosted runners ship Inno Setup 6 preinstalled at that path — if a CI run shows `ISCC.exe` not found, the fallback is adding a `choco install innosetup -y` step before it (not pre-emptively added, since the preinstalled tool is expected to work).
- "Upload Artifacts (Windows)" path changes from `app/build/compose/binaries/main/msi/*.msi` to `packaging/windows/Output/*.exe`.

## Testing

No automated test is possible for a native Windows installer wizard's UI behavior. Verification is manual, after a CI run produces the new artifact:
1. Run the installer on a clean or existing Windows machine — confirm **no UAC/admin prompt** appears at any point.
2. Confirm both checkboxes ("Create a desktop icon", "Launch unwrapMedia") are pre-checked, and un-checking each behaves correctly (no shortcut / no auto-launch).
3. With both left checked (default path): confirm a desktop icon appears using the new app icon, a Start Menu entry appears, and the app launches automatically right after Finish.
4. Confirm the app icon (not the Java default) shows in the taskbar while running and in the exe's file icon in Explorer.
5. Confirm an uninstall entry named "unwrapMedia" appears in Windows Settings → Apps, with the app icon, and that running it removes the app and its shortcuts.
6. This should happen on the same Windows machine used for the earlier VLC-regression / ffmpeg-bundling verification, continuing to confirm no VLC or system ffmpeg install is required.
