# unwrapMedia

**unwrapMedia** is a media file inspection tool (Kotlin + Compose Multiplatform for Desktop) that parses the internal structure of image, video, and audio files -- boxes, markers, IFDs, chunks -- into an inspectable structure tree, hex view, and decoded metadata, all cross-referenced by byte offset.

---

## Key Features

**Image Inspector** -- JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC, TIFF, plus camera RAW (CR2, NEF, ARW, DNG) via generic TIFF/IFD parsing.
- EXIF/TIFF metadata, GPS, Samsung MakerNote
- XMP (pretty-printed), color histograms, JPEG DQT heatmap
- Embedded thumbnail extraction (JPEG- or HEVC-coded thumbnail items alike), HEIC/HEVC preview decode via ffmpeg
- Motion Photo support (Samsung-style and Google-style: still + embedded video)
- Animated GIFs play back as an interactive full-width frame filmstrip

**Video Inspector** -- MP4, MOV, M4V, WebM.
- Built-in player (play/pause/seek/click-to-seek), GOP frame-type graph (I/P/B)
- **프레임 간격 분석** menu: per-frame interval scatter plot + data table (frame number, timestamp, interval, interval diff) for spotting irregular frame spacing in the file's own timestamps
- Per-stream codec details: profile, level, chroma, bit depth, frame rate, bit rate, duration
- Track extraction: pull a video or audio stream into its own file (stream copy, re-encode fallback)

**Audio Inspector** -- M4A, MP3, WAV, FLAC, OGG, Opus, AIFF/AIFF-C, plus headerless raw PCM (`.pcm`, format/rate/channels set at open).
- Playback with waveform (real decoded peaks) and spectrogram, both zoomable (mouse wheel) and pannable (trackpad scroll, scrollbar, or the always-visible minimap), click/drag-to-seek
- Each format has a dedicated structural parser (or reuses the MP4 box parser for M4A)
- Format, sample rate, channels, bit depth, duration

**Raw Pixel Viewer** -- headerless `.raw`/`.rgb`/`.rgba`/`.yuv` dumps; YUV420(sp/p), RGB/BGR565, RGB888/BGRA8888. Multi-frame files play back as raw video. (`.raw` is ambiguous with raw PCM audio -- opening one asks which it is.)

**Binary Explorer** -- structure tree, detailed field panel, hex/raw byte viewer with drag-select, all panels resizable. Dark/light theme toggle (View menu), preference persisted across launches.

**CLI Mode** -- inspect files from a script or CI, no GUI needed:
```bash
unwrapMedia dump <file>   # full structure tree as JSON, to stdout
unwrapMedia check <file>  # warnings only, as JSON ({"warningCount": N, "warnings": [...]})
```
Exit code is `0` on a successful parse (regardless of warning count) and `1` if the file couldn't be parsed at all.

---

## Supported Formats & Limits

| Category | Extensions |
|---|---|
| Image | `.jpg` `.jpeg` `.png` `.bmp` `.gif` `.webp` `.avif` `.heic` `.tif` `.tiff` `.cr2` `.nef` `.arw` `.dng` |
| Video | `.mp4` `.mov` `.m4v` `.webm` |
| Audio | `.m4a` `.mp3` `.wav` `.flac` `.ogg` `.opus` `.aiff` `.aif` `.aifc` `.pcm` |
| Raw pixel | `.raw` `.rgb` `.rgba` `.yuv` |

**Resolution limits**: above 8K (image) / 4K (video, continuous) shows a dismissible warning; above ~268 megapixels is refused outright (checked from the header, before decode).

---

## Download

Built automatically for Windows, Linux, and macOS on every push -- see the [Actions](https://github.com/abracadabra799/unwrapMedia/actions) page, latest **"Package unwrapMedia"** run, Artifacts section.
- **Windows**: `.exe` (ffmpeg/ffprobe bundled)
- **Linux**: `.deb` (ffmpeg/ffprobe bundled)
- **macOS**: `.dmg` -- needs `ffmpeg` on `PATH` (`brew install ffmpeg`) for video/audio playback and HEIC preview

---

## Tech Stack

Kotlin, Compose Multiplatform Desktop (JVM 21+), Gradle, ffmpeg/ffprobe (external process, not statically linked).

```bash
./gradlew :app:run                            # run
./gradlew test                                # test
./gradlew :app:packageDistributionForCurrentOS # build distribution
```

---

## ⚠️ Before Sharing Internally

This project uses ffmpeg/ffprobe as external LGPL-licensed binaries (invoked as a subprocess, not compiled in), and decodes/plays codecs including H.264, HEVC, and AAC. Open-source licensing (LGPL) covers ffmpeg's own code, but it does not clear codec patent licensing -- H.264/HEVC/AAC involve separate patent pools, and using or redistributing decoders for them commercially can carry its own licensing obligations depending on jurisdiction and use case. Have this checked by legal/compliance before distributing beyond personal use.

---

## License

MIT -- see [LICENSE](LICENSE).
