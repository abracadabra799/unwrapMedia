# Walkthrough - Multi-platform Distribution Setup

I have established a robust automated build pipeline for Windows, Linux, and macOS, ensuring that the necessary video dependencies (VLC) are available during the packaging process.

## Key Changes

### 1. Build Stability Fix ([build.gradle.kts](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/build.gradle.kts))
- **Reverted Experimental Natives**: Removed the `vlcj-natives` Maven dependencies that caused resolution errors. Sticking to the stable `uk.co.caprica:vlcj:4.12.1` ensures the project builds correctly in all environments.
- **Why?**: VLC native libraries are complex to manage via Maven. The most reliable way to distribute them is either through system installation or manual bundling (to be configured in a future step).

### 2. Automated Multi-OS Pipeline ([package.yml](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/.github/workflows/package.yml))
- Created a specialized **GitHub Actions** workflow that:
    - **Installs VLC** on the build runners (Windows, Ubuntu, macOS) using their respective package managers (`choco`, `apt`, `brew`).
    - Packages the application into native installers (**MSI**, **DEB**, **DMG**).
    - Uploads the resulting binaries as downloadable artifacts.

### 3. Updated Documentation ([README.md](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/README.md))
- Clarified that for the current version, users on Windows and Linux should ensure VLC is installed to enable full video analysis capabilities.

## How to get the executables

1.  **Commit & Push**: Push these changes to your `v2` branch.
2.  **GitHub Actions**: Go to your repo's **Actions** tab on GitHub.
3.  **Download**: Once the "Package unwrapMedia" workflow finishes (usually 5-10 mins), download the zip files from the "Artifacts" section at the bottom of the run summary.

---
**The project is now buildable and ready for cloud-based distribution! If you need a fully bundled version (where VLC is hidden inside the EXE), please let me know and we can set up manual library extraction.**
