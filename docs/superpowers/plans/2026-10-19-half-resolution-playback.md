# Half-resolution playback implementation

1. Add real-ffmpeg regression tests for the playback pipe output dimensions: a 1280×720 source becomes 640×360, a sub-640 source is not enlarged, and one frame has the expected BGRA byte count. Run tests and observe failure against the 1280-pixel cap.
2. Change the playback scale filter to a 640-pixel long-side cap; keep passthrough frame timing and the output-dimension watcher. Run focused tests.
3. Update `app/build.gradle.kts` `packageVersion`, `ui/I18n.kt` `APP_VERSION`, and `version.json` to v1.30.4. Include release notes for this change and the prior audio-EOF fix.
4. Run all app tests and compile, review the diff, fast-forward merge into main, and push origin/main as requested. Report that Windows performance still requires on-device validation.
