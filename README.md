# unwrapMedia

**unwrapMedia** is a media file inspection tool built with Kotlin and Compose Multiplatform for Desktop. It parses the internal structure of image and video files -- boxes, markers, IFDs, streams -- and turns them into inspectable, cross-referenced views: structure tree, hex bytes, decoded metadata, and live previews, all pointing at the same underlying offsets.

Inspired by tools like **JPEGsnoop**, **ExifTool**, and **MediaInfo**, unwrapMedia is aimed at engineers, QA, and anyone who needs to see both the interpreted metadata *and* the raw bytes behind a media file.

---

## 🚀 Key Features

### 1. Image Inspector
Formats: **JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC**, plus camera RAW (**CR2, NEF, ARW, DNG**) via generic TIFF/IFD parsing and embedded-preview extraction (no full RAW/demosaic decode).
- **EXIF/TIFF metadata**: camera settings, lens info, GPS coordinates, orientation (label plus the raw numeric code), Exif/GPS/Interop sub-IFDs.
- **MakerNote**: decoded with named tags for Samsung's EXIF-format MakerNote (detected from the Make field); other manufacturers still get every tag, just labeled by raw hex ID rather than name.
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
- **Track extraction** (추출 menu): pull every video stream into a new file in the source's own container format, or every audio stream into an M4A -- stream copy when possible (lossless, no re-encode), falling back to a re-encode only if the source codec can't go into the target container as-is.

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

## 📋 Supported Specs & Limits

Opening a file that falls outside these limits is refused up front with a popup -- no partial parse, no attempted decode.

**Supported extensions** (matched case-insensitively; anything else is rejected before any parsing is attempted):
| Category | Extensions |
|---|---|
| Image | `.jpg` `.jpeg` `.png` `.bmp` `.gif` `.webp` `.avif` `.heic` `.cr2` `.nef` `.arw` `.dng` |
| Video | `.mp4` `.mov` `.m4v` |
| Raw pixel | `.raw` `.rgb` `.rgba` `.yuv` |

**Resolution**: there's no artificial cap on ordinary files, but very large resolutions run into real memory limits (the app runs on the JVM's default heap, with no `-Xmx` override):
- **Above 8K** (7680x4320, static images / a single raw pixel frame) or **above 4K** (3840x2160, video / a raw pixel stream played back continuously): still opens, but shows a dismissible warning that memory use and decode/playback speed may suffer.
- **Above ~268 megapixels** (the point where a single decoded frame would need about 1GB): opening is refused outright with a popup, both for parsed image/video files (checked from the header, before any pixel decode is attempted) and for raw pixel dumps (checked against the width/height you enter, before the "열기" button is enabled).

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
