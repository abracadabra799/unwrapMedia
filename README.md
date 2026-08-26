**Language:** English | [한국어](README.ko.md)

# unwrapMedia

**unwrapMedia** is a fast, lightweight media structure inspector and forensic debugging tool built with Kotlin and Compose Multiplatform for Desktop. It parses the internal binary containers of image, video, and audio files into an interactive structure tree and hex viewer synchronized by exact byte offsets.

---

## ⚡ Core Workflow & Key Features

```
┌─────────────────┐     ┌──────────────────────────────┐     ┌──────────────────────────────┐
│  Open Any Media │ ──► │  Interactive Structure Tree  │ ──► │  Forensics & Visual Overlays │
│  (Drag & Drop)  │     │   & Byte-Offset Hex Sync     │     │ (Gain Map, QP, Waveform, AI) │
└─────────────────┘     └──────────────────────────────┘     └──────────────────────────────┘
```

### 🖼️ Image & HDR Gain Map
* **Deep Structure**: EXIF, Apple MakerNote, Samsung SEFD, and HEVC Grid tile outlines.
* **HDR Gain Map**: Dedicated viewer for ISO 21496-1, Ultra HDR, Apple MPF, and Adobe HDRGM with headroom curves, raw XMP XML inspector, and gain map image extraction.
* **Motion Photos**: Instant detection and playback for Samsung and Google Motion Photos.

### 🎬 Video & Bitstream Forensics
* **Modern Codecs**: In-depth header and parameter parsing for **APV**, **AV1**, **HEVC (H.265)**, **AVC (H.264)**, and **Dolby Vision**.
* **Visual Overlays**: Macroblock **Motion Vectors** and **QP Heatmap** rendered directly over video playback.
* **Frame Drop Analysis**: Scatter plot detecting timestamp jitter across 200,000+ frames with 120 FPS LOD rendering.

### 🎵 Audio & Raw PCM
* **Waveform & Spectrogram**: High-precision interactive peak waveforms and FFT spectrograms with zoom/pan.
* **Formats**: WAV, MP3, AAC/M4A, FLAC, OGG, Opus, AIFF, and headerless raw **PCM** (`.pcm`, `.raw`).

### 🔲 Raw Pixel & Comparison
* **Raw Pixel Viewer**: Instant preview for headerless YUV420 (NV12/NV21/I420) and RGB dumps with multi-frame playback.
* **Side-by-Side Compare**: Structure, metadata, and byte-level diffs between two files.
* **Video Quality Benchmark**: Frame-by-frame VMAF, PSNR, and SSIM metrics calculation.

### 🤖 Diagnostics & AI Prompts
* **Structure Check**: Immediate container defect linting categorized by severity (`CRITICAL`, `WARNING`, `INFO`).
* **AI Diagnostic Prompts**: Generates spec-rich ISO/IEC prompt templates copied directly to the clipboard.
* **CLI Mode**: Terminal commands (`dump`, `check`) for CI/CD integration.

---

## 📦 Supported Formats

| Category | Supported Formats |
|---|---|
| **Image** | JPEG, PNG, GIF, WebP, AVIF, HEIC/HEIF, BMP, TIFF, Camera RAW (DNG, CR2, NEF, ARW) |
| **Video** | MP4, MOV, M4V, WebM, APV, AV1, IVF (Codecs: APV, AV1, HEVC, AVC, Dolby Vision, VP8/VP9) |
| **Audio** | WAV, MP3, M4A/AAC, FLAC, OGG, Opus, AIFF, Headerless Raw PCM (`.pcm`) |
| **Raw Pixel** | `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` |

---

## 🚀 Quick Start

### Download Pre-built App
Download the latest binaries from [Releases / GitHub Actions Artifacts](https://github.com/abracadabra799/unwrapMedia/actions):
* **macOS**: `.dmg` (requires `brew install ffmpeg`)
* **Windows**: `.exe` (installer bundled with ffmpeg/ffprobe)
* **Linux**: `.deb` (bundled with ffmpeg/ffprobe)

### Build from Source
Requires JDK 21+ and Gradle:
```bash
./gradlew :app:run         # Run application
./gradlew test             # Run test suite
./gradlew :app:package     # Build OS package
```

### CLI Usage
```bash
unwrapMedia dump <file>              # Dump full structure tree as JSON
unwrapMedia check <file>             # Lint structural defects & warnings
unwrapMedia check <file> --prompt    # Generate AI diagnostic prompt
unwrapMedia check <file> -p -c       # Generate prompt and copy to clipboard
```

---

## 📄 License

MIT -- see [LICENSE](LICENSE).
