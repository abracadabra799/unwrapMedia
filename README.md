# unwrapMedia

**unwrapMedia** is a media file inspection tool (Kotlin + Compose Multiplatform for Desktop) that parses the internal structure of image, video, and audio files -- boxes, markers, IFDs, chunks -- into an inspectable structure tree, hex view, and decoded metadata, all cross-referenced by byte offset.

---

## Key Features

**Image Inspector** -- JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC, TIFF, plus camera RAW (CR2, NEF, ARW, DNG) via generic TIFF/IFD parsing.
- EXIF/TIFF metadata, GPS, Samsung MakerNote
- XMP (pretty-printed), color histograms, JPEG DQT heatmap
- Embedded thumbnail extraction (JPEG- or HEVC-coded thumbnail items alike), HEIC/HEVC preview decode via ffmpeg
- Motion Photo support (Samsung-style and Google-style: still + embedded video)

**Video Inspector** -- MP4, MOV, M4V.
- Built-in player (play/pause/seek), GOP frame-type graph (I/P/B, click-to-seek)
- Per-stream codec details: profile, level, chroma, bit depth, frame rate, bit rate, duration
- Track extraction: pull a video or audio stream into its own file (stream copy, re-encode fallback)

**Audio Inspector** -- M4A, MP3, WAV.
- M4A reuses the MP4 box parser (AAC/ALAC/AC-3); MP3 (ID3v2/v1 tags + frame sniffing) and WAV (RIFF/fmt/data chunks) have dedicated parsers
- Format, sample rate, channels, bit rate, duration
- No playback -- inspection only

**Raw Pixel Viewer** -- headerless `.raw`/`.rgb`/`.rgba`/`.yuv` dumps; YUV420(sp/p), RGB/BGR565, RGB888/BGRA8888. Multi-frame files play back as raw video.

**Binary Explorer** -- structure tree, detailed field panel, hex/raw byte viewer with drag-select, all panels resizable.

---

## Supported Extensions

| Category | Extensions |
|---|---|
| Image | `.jpg` `.jpeg` `.png` `.bmp` `.gif` `.webp` `.avif` `.heic` `.tif` `.tiff` `.cr2` `.nef` `.arw` `.dng` |
| Video | `.mp4` `.mov` `.m4v` |
| Audio | `.m4a` `.mp3` `.wav` |
| Raw pixel | `.raw` `.rgb` `.rgba` `.yuv` |

**Resolution limits**: above 8K (image) / 4K (video, continuous) shows a dismissible warning; above ~268 megapixels is refused outright (checked from the header, before decode).

---

## Download

Built automatically for Windows, Linux, and macOS on every push -- see the [Actions](https://github.com/abracadabra799/unwrapMedia/actions) page, latest **"Package unwrapMedia"** run, Artifacts section.
- **Windows**: `.exe` (ffmpeg/ffprobe bundled)
- **Linux**: `.deb` (ffmpeg/ffprobe bundled)
- **macOS**: `.dmg` -- needs `ffmpeg` on `PATH` (`brew install ffmpeg`) for video playback and HEIC preview

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
