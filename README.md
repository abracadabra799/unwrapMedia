# unwrapMedia

**unwrapMedia** is a forensic media analysis tool built with Kotlin and Compose Multiplatform for Desktop. It parses the internal structure of image and video files -- boxes, markers, IFDs, streams -- and turns them into inspectable, cross-referenced views: structure tree, hex bytes, decoded metadata, and live previews, all pointing at the same underlying offsets.

Inspired by tools like **JPEGsnoop**, **ExifTool**, and **MediaInfo**, unwrapMedia is aimed at engineers, researchers, and forensic analysts who need to see both the interpreted metadata *and* the raw bytes behind it.

---

## 🚀 Key Features

### 1. Image Inspector
Formats: **JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC**
- **EXIF/TIFF metadata**: camera settings, lens info, GPS coordinates, orientation (shown with both the human-readable label and the raw numeric code).
- **XMP**: pretty-printed and left-aligned so multi-KB XML packets stay readable instead of wrapping mid-tag.
- **Color histograms**: R, G, B, and luminance channel distribution.
- **JPEG DQT quantization heatmap**: 8x8 grid to spot re-compression and quality-level inconsistencies.
- **Embedded thumbnail extraction**, including aggressive fallback extraction for HEIC.
- **HEIC/HEVC preview decoding** via ffmpeg (Skia can't decode these natively) when no usable embedded thumbnail exists.
- **Motion Photo support** (Samsung-style): extracts and previews the embedded video alongside the still image, with button-triggered codec-detail analysis (profile, bit rate, frame count, duration).

### 2. Video Inspector
Formats: **MP4, MOV, M4V**
- **Built-in video player**: real-time playback of the actual file (no external player dependency), with play/pause, replay-from-end, elapsed time, and a progress bar.
- **GOP frame-type graph**: per-frame I/P/B type and size, visualized as a size-proportional bar chart. Click a frame (or step with the prev/next buttons) to seek the player to that exact timestamp; the graph highlights and auto-scrolls to whichever frame is currently on screen during playback.
- **Per-stream codec details**: profile, level, chroma subsampling, bit depth, frame rate mode, bit rate, duration (millisecond precision), and frame count for both video and audio streams.

### 3. MediaInfo-Style Summaries
Unified General/Video/Audio summary cards for quick orientation before drilling into individual fields.

### 4. Interactive Binary Explorer
- **Structure tree**: hierarchical view of boxes/markers/IFDs; selecting a node auto-expands its ancestors and jumps the hex view to its byte offset.
- **Detailed Properties panel**: field-level data for the selected node, resizable, with structural-warning summaries shown by default before anything is selected.
- **Hex & raw byte viewer**: click-and-drag to select an arbitrary byte range (works across rows), copy the selection as hex to the clipboard, and jump to any offset from the structure tree.
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
- **Video/HEIC decoding**: ffmpeg/ffprobe (external process, not a bundled library)

---

## 🏁 Getting Started

### Prerequisites
- JDK 21 or higher.
- `ffmpeg`/`ffprobe` on your `PATH` when running from source (video playback, HEIC preview decode, and GOP/codec analysis all shell out to them).

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

## 📸 UI Overview

A 4-pane, all-resizable layout:
1. **Left**: Structure tree (boxes/markers/IFDs)
2. **Center**: Live preview, GOP graph, and media summaries
3. **Right**: Detailed Properties for the selected node
4. **Bottom**: Hex & raw byte viewer

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an issue for feature requests.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
