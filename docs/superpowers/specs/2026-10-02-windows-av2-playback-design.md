# Windows AV2 Playback and Stream Analysis

## Goal

Enable unwrapMedia on Windows to inspect AV2 sample streams and play AV2 ISO-BMFF video without requiring a user-installed decoder. The installer remains one user-facing application; it places an internal `avmdec.exe` beside the existing bundled media tools.

## Scope

This release targets Windows x64 only. It supports AV2 carried in an ISO-BMFF video track with `av02`/`av2C`; raw AV2, IVF, WebM, Matroska, audio playback, macOS, and Linux are excluded. Existing AV2 container and configuration inspection remains available when the decoder cannot run.

## Architecture

The Windows GitHub Actions package job builds AVM's decoder executable from a pinned AVM revision and copies it to `app/resources/windows/bin/avmdec.exe`. `AvmLocator` follows `FfmpegLocator`'s packaged-resource resolution rules, with no PATH fallback in a packaged Windows build: an absent helper is an actionable application error.

`Av2SampleIndexer` reads ISO-BMFF sample tables (`stts`, `stsc`, `stco`/`co64`, `stsz`) and creates immutable sample records containing decode time, duration, file range, OBU type list, and random-access classification. It validates every length-delimited OBU against its sample boundary. This index powers the GOP/stream view even when pixel decoding is unavailable.

`Av2BitstreamAssembler` writes a temporary AV2 length-delimited stream from the active `av2C` configuration OBUs and indexed samples. It never reads an entire source video into memory. `AvmDecoderProcess` starts the helper with that stream, receives its Y4M output, parses its header and planar YUV frames, and converts display-sized frames to Compose `ImageBitmap` values. It drains stderr, supports cancellation, limits pipe buffering, and deletes temporary streams on process completion.

`Av2VideoPlayer` reuses the player controls and timing contract used by `FfmpegVideoPlayer`. Phase one supports play/pause, sequential playback, first-frame preview, and thumbnails. Seeking restarts decoding at the nearest indexed random-access sample and decodes forward; if no random-access point is known, seeking is disabled with an explanatory label.

## Failure Handling

- Missing `avmdec.exe`, non-zero decoder exit, invalid Y4M header, unsupported chroma/bit depth, or premature frame data stops playback and displays the captured decoder diagnostic.
- Invalid sample-table offsets, truncated LEB128, or an OBU crossing a sample boundary leaves the structure tree available and reports a warning. It never starts the helper with a reconstructed corrupt stream.
- Decoder output is capped to the existing player display edge. Full-resolution decoding and frame-perfect seek are intentionally outside the first release.

## Packaging

The Windows installer contains `unwrapMedia.exe`, current ffmpeg/ffprobe resources, and `avmdec.exe`. The UI invokes only `unwrapMedia.exe`; no additional shortcut, installer, PATH configuration, or user action is required. AVM's licensing notice and pinned source revision are included in the distribution notices.

## Tests

Unit tests cover AV2 sample-table indexing, OBU sample-boundary validation, bitstream assembly ordering, helper argument construction, Y4M header/frame parsing, cancellation, and missing-helper errors. Integration tests use a small valid AV2 MP4 fixture plus malformed samples. Windows CI verifies `avmdec.exe` is staged in the jpackage resource tree and performs a decode smoke test before packaging.

## Deferred Work

macOS and Linux packaging, JNI integration, raw/IVF/WebM AV2, multi-layer composition, audio synchronization, complete AV2 frame-header semantics, colour/film-grain rendering, and hardware acceleration are deferred.
