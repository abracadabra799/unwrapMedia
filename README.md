**Language:** English | [한국어](README.ko.md)

# unwrapMedia

**unwrapMedia** is a high-performance, zero-overhead media inspection and debugging tool (Kotlin + Compose Multiplatform for Desktop). It parses the internal binary structure of image, video, and audio files (boxes, markers, IFDs, chunks, bitstreams) into an interactive structure tree, hex viewer, and decoded metadata view -- all cross-referenced by exact byte offsets.

---

## <font color="#0969da">⚡ Key Features</font>

### <font color="#1f6feb">🖼️ Image Inspector</font>
- **Formats**: JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC/HEIF, TIFF, and Camera RAW (CR2, NEF, ARW, DNG).
- **HEVC Grid Tile Overlay & Popup**: Visualizes HEVC/HEIF grid tiles with interactive outline overlays, bidirectional tree sync, and a dedicated zoomable popup viewer.
- **Apple & Samsung Metadata**: Apple MakerNote (lens, sensor, focus, HDR gain/headroom, Smart Style binary plist), Samsung SEFD.
- **Apple HEIF Auxiliary Images**: HDR gain map, depth/disparity, portrait effects, and semantic segmentation mattes.
- **Deep Inspection**: XMP, color histograms, JPEG DQT heatmaps, embedded thumbnail extractors, and animated GIF filmstrips.
- **Motion Photos**: Supports both Samsung-style (`MotionPhoto_Data`) and Google-style (still + embedded video).

### <font color="#8250df">🎬 Video Inspector</font>
- **Container Formats**: MP4, MOV, M4V, WebM, APV, AV1, IVF.
- **Deep Codec & Bitstream Decoders**:
  - **APV (Advanced Professional Video)**: `apvC` box, Frame Header (Chroma 4:2:2/4:4:4/4:4:4:4, Bit Depth 10/12/14/16-bit, Tile Grid, Color Primaries).
  - **AV1**: `av1C` box, Sequence Header (Profile, Level, Tier, Bit Depth, Color Primaries, Timing), Frame Header (OBU).
  - **HEVC (H.265)**: `hvcC` box, VPS, SPS, PPS (Profile, Level, Tier, Main 10, Tile Grid).
  - **AVC (H.264)**: `avcC` box, SPS (Profile, Level, Chroma 4:2:0/4:2:2/4:4:4, Bit Depth, VUI), PPS (CABAC/CAVLC, 8x8 Transform).
  - **Dolby Vision**: `dvcC`, `dvvC` configuration records.
- **Playback & GOP Graph**: Minimalist built-in player synced with GOP frame-type analysis (I/P/B frames).
- **Apple Metadata & Dolby Vision**: `com.apple.quicktime.*`, Live Photo, timed metadata (`mebx`/`mdta`), and Dolby Vision (`dvcC`/`dvvC`).
- **High-Performance Frame Interval Analysis**:
  - Scatter plot and data table detecting timestamp irregularities.
  - **LOD (Level of Detail) Downsampling**: Renders 200,000+ frames at a smooth 120 FPS.
  - **Asynchronous Chunk Streaming (`Flow`)**: Instant UI response without blocking.
- **2-Tier Index Caching**: Instant tab switching and reloading with L1 memory LRU + L2 compact binary disk cache.
- **Track Extraction**: Lossless stream copy or re-encode fallback.

### <font color="#0969da">🎵 Audio Inspector</font>
- **Formats**: M4A, MP3, WAV, FLAC, OGG, Opus, AIFF, and headerless raw PCM (`.pcm`).
- **Visuals**: Real decoded peak waveforms and spectrograms with zoom/pan and minimap.

### <font color="#57606a">🔲 Raw Pixel Viewer</font>
- **Formats**: Headerless `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` dumps.
- **Color Formats**: YUV420 (NV12/NV21/I420/YV12), RGB565, RGB888, RGBA8888, ARGB8888. Multi-frame raw video playback supported.

### <font color="#bf3989">🤖 AI Diagnostic Assistant</font>
- **One-Click Diagnostic Prompt**: Automatically generates structured, domain-rich prompts containing file info, exact structural defects (JSON), and ISO/IEC spec references.
- **OS Clipboard Integration**: Instant copy for immediate analysis with ChatGPT, Claude, or Gemini.

---

## <font color="#0969da">💻 CLI Mode</font>

Inspect files from terminal scripts, CI/CD pipelines, or generate AI debugging prompts:

```bash
# 1. Output full structure tree as JSON
unwrapMedia dump <file>

# 2. Output structural warnings only (for CI linters)
unwrapMedia check <file>

# 3. Generate structured AI diagnostic prompt
unwrapMedia check <file> --prompt

# 4. Generate AI prompt and copy directly to OS clipboard
unwrapMedia check <file> -p --clipboard
```

*Exit Codes*: `0` on successful inspection, `1` on parse error or file missing.

---

## <font color="#0969da">📦 Supported Formats & Codecs</font>

| Category | Supported Extensions & Codecs |
|---|---|
| **Image** | `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.webp`, `.avif`, `.heic`, `.tif`, `.tiff`, `.cr2`, `.nef`, `.arw`, `.dng` |
| **Video Containers** | `.mp4`, `.mov`, `.m4v`, `.webm`, `.apv`, `.av1`, `.ivf` |
| **Video Codecs** | **APV**, **AV1**, **HEVC (H.265)**, **AVC (H.264)**, **Dolby Vision**, MPEG-4, VP8/VP9 |
| **Audio** | `.m4a`, `.mp3`, `.wav`, `.flac`, `.ogg`, `.opus`, `.aiff`, `.aif`, `.aifc`, `.pcm` |
| **Raw Pixel** | `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` |

---

## <font color="#0969da">🚀 Installation & Running</font>

### <font color="#1a7f37">Pre-built Binaries</font>
Download the latest binary from GitHub Actions [Artifacts](https://github.com/abracadabra799/unwrapMedia/actions):
- **macOS**: `.dmg` (requires `ffmpeg` on PATH: `brew install ffmpeg`)
- **Windows**: `.exe` (bundled with ffmpeg/ffprobe)
- **Linux**: `.deb` (bundled with ffmpeg/ffprobe)

### <font color="#57606a">Build from Source</font>
Requirements: JDK 21+ and Gradle.

```bash
# Run application
./gradlew :app:run

# Run full test suite
./gradlew test

# Build distribution package for current OS
./gradlew :app:packageDistributionForCurrentOS
```

---

## <font color="#1a7f37">🛡️ Reliability & Process Safety</font>

- **Zero Zombie Processes**: Global `ProcessManager` and JVM shutdown hooks ensure all background `ffmpeg`/`ffprobe` processes are terminated immediately upon exit or cancellation.
- **Safe Resource Management**: Strict `.use { ... }` auto-close patterns prevent file locking and memory leaks.
- **Global Error Handling**: Uncaught exceptions are intercepted gracefully without silent freezes.

---

## <font color="#57606a">📄 License</font>

MIT -- see [LICENSE](LICENSE).
