# Windows player pacing implementation

1. Add failing tests for an audio-derived monotonic clock that continues after PCM EOF and does not advance while paused. Test no-audio fallback and stale-frame dropping after EOF. Run the focused tests to observe failure.
2. Implement the clock in the player module. Retain the last rendered position if the audio line closes. Replace the EOF switch to self pacing with the clock's continued position. Mirror that same position into the progress UI. Keep the no-audio and audio-failure paths intact. Run focused tests.
3. Add bounded diagnostic counters around raw-frame reads and bitmap construction, logging a summary only when the video is substantially behind the timeline. Review the diff for UI-thread work and process lifecycle changes.
4. Run the full app test suite and compile task. Review all changed files and record the Windows manual validation steps for regular video, inline motion photo, and popup playback. Do not claim Windows performance is fixed until measured on Windows.
