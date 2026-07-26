# unwrapMedia

**unwrapMedia** is a forensic media analysis tool built with Kotlin and Compose Multiplatform for Desktop. It parses the internal structure of image and video files -- boxes, markers, IFDs, streams -- and turns them into inspectable, cross-referenced views: structure tree, hex bytes, decoded metadata, and live previews, all pointing at the same underlying offsets.

Inspired by tools like **JPEGsnoop**, **ExifTool**, and **MediaInfo**, unwrapMedia is aimed at engineers, researchers, and forensic analysts who need to see both the interpreted metadata *and* the raw bytes behind it.

---

## 🚀 Key Features

### 1. Image Inspector
Formats: **JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC**, plus camera RAW (**CR2, NEF, ARW, DNG**) via generic TIFF/IFD parsing and embedded-preview extraction (no full RAW/demosaic decode).
- **EXIF/TIFF metadata**: camera settings, lens info, GPS coordinates, orientation (label plus the raw numeric code).
- **XMP**: pretty-printed and left-aligned so multi-KB XML packets stay readable.
- **Color histograms** and a **JPEG DQT quantization heatmap** (8x8 grid) for spotting re-compression.
- **Embedded thumbnail extraction**, with aggressive fallback for HEIC and camera RAW.
- **HEIC/HEVC preview decoding** via ffmpeg when no usable embedded thumbnail exists.
- **Motion Photo support** (Samsung-style): previews the embedded video alongside the still image, with button-triggered codec-detail analysis.

### 2. Video Inspector
Formats: **MP4, MOV, M4V**
- **Built-in video player**: real-time playback with play/pause, replay-from-end, elapsed time, and a progress bar.
- **GOP frame-type graph**: per-frame I/P/B type and size as a bar chart. Click a frame (or step with prev/next) to seek the player to that timestamp; the graph highlights and auto-scrolls to whatever frame is currently on screen.
- **Per-stream codec details**: profile, level, chroma subsampling, bit depth, frame rate mode, bit rate, duration (ms precision), and frame count.

### 3. Raw Pixel Viewer
Opens headerless raw pixel dumps (**.raw / .rgb / .rgba / .yuv**) by asking for width, height, and pixel format up front, since the file itself carries none of that.
- **Formats**: YUV420sp (NV12/NV21), YUV420p (I420, YV12), RGB565/BGR565 (with selectable byte order), RGB888/BGR888, RGBA8888/ARGB8888.
- **Multi-frame sequences**: a file larger than one frame is treated as a raw video stream -- play/pause, click-to-seek progress bar, and prev/next frame stepping, with the frame rate adjustable live during playback (a raw dump carries no frame-rate metadata, so this is always a starting guess).

### 4. MediaInfo-Style Summaries
Unified General/Video/Audio summary cards for quick orientation before drilling into individual fields.

### 5. Interactive Binary Explorer
- **Structure tree**: hierarchical view of boxes/markers/IFDs; selecting a node auto-expands its ancestors and jumps the hex view to its byte offset.
- **Detailed Properties panel**: field-level data for the selected node, with structural-warning summaries shown by default.
- **Hex & raw byte viewer**: click-and-drag to select an arbitrary byte range (works across rows), copy the selection as hex.
- All panels (left structure tree, right properties, bottom hex viewer) are drag-resizable.

---

## 💾 Download & Installation

The application is automatically built for Windows, Linux, and macOS on every push. Download the latest build from **GitHub Actions**:

1. Go to the [Actions](https://github.com/abracadabra799/unwrapMedia/actions) page.
2. Select the most recent **"Package unwrapMedia"** run.
3. Scroll down to the **Artifacts** section.
4. Download the version for your OS:
    - **Windows**: `.exe` installer (Inno Setup; ffmpeg/ffprobe bundled, nothing extra to install).
    - **Linux**: `.deb` (ffmpeg/ffprobe bundled).
    - **macOS**: `.dmg` -- requires `ffmpeg` on your `PATH` (e.g. `brew install ffmpeg`) for video playback and HEIC preview decoding; everything else works without it.

---

## 🛠 Tech Stack

- **Language**: Kotlin
- **Framework**: Compose Multiplatform for Desktop (JVM)
- **Runtime**: Java 21+
- **Build System**: Gradle
- **Video/HEIC/raw-YUV decoding**: ffmpeg/ffprobe (external process, not a bundled library)

---

## 🏁 Getting Started

### Prerequisites
- JDK 21 or higher.
- `ffmpeg`/`ffprobe` on your `PATH` when running from source.

### Run the Application
```bash
./gradlew :app:run
```

### Run Tests
```bash
./gradlew test
```

### Build Distribution
```bash
./gradlew :app:packageDistributionForCurrentOS
```

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an issue for feature requests.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
