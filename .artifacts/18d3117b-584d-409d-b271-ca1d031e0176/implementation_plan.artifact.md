# Implementation Plan - Self-contained Multi-platform Packaging with VLC

Enable unwrapMedia to be distributed as a standalone application for Windows and Linux by bundling VLC native libraries and setting up an automated GitHub Actions build pipeline.

## User Review Required

> [!IMPORTANT]
> - **Package Size**: Each installer (.msi, .deb, .dmg) will increase in size by approximately **100MB-150MB** due to the inclusion of VLC codecs and libraries.
> - **GitHub Actions**: The builds will happen on GitHub's cloud servers. You will need to push the code to your repository and download the results from the "Actions" or "Releases" tab.

## Proposed Changes

### [Component: Build Configuration]

#### [MODIFY] [app/build.gradle.kts](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/build.gradle.kts)
- Add the following dependencies to ensure native libraries for all platforms are available at runtime:
    - `implementation("uk.co.caprica:vlcj-natives-windows-x86-64:4.8.0")`
    - `implementation("uk.co.caprica:vlcj-natives-linux-x86-64:4.8.0")`
    - `implementation("uk.co.caprica:vlcj-natives-macos-all:4.8.0")`

### [Component: UI - Video Player]

#### [MODIFY] [VlcVideoPlayer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt)
- Update discovery logic to gracefully handle bundled natives if system-wide VLC is missing.

### [Component: CI/CD]

#### [NEW] [.github/workflows/package.yml](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/.github/workflows/package.yml)
- Create a multi-platform build matrix:
    - `os: [windows-latest, ubuntu-latest, macos-latest]`
- Steps:
    1. Checkout code.
    2. Set up JDK 21.
    3. Run `./gradlew :app:packageDistributionForCurrentOS`.
    4. Upload the generated installers as artifacts.

## Verification Plan

### Manual Verification
1. Push the changes to the `v2` branch.
2. Monitor the "Package unwrapMedia" workflow on GitHub.
3. Download the resulting Windows `.msi` and Linux `.deb`.
4. Run them on target machines to confirm "out-of-the-box" video playback.
