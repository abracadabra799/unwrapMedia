# unwrapMedia

**unwrapMedia** is a professional-grade media analysis and forensic tool built with Kotlin and Compose Multiplatform for Desktop. It transforms complex binary data from image and video files into intuitive, high-visibility dashboards.

Inspired by industry-standard tools like **JPEGsnoop**, **MP4Box**, and **MediaInfo**, unwrapMedia provides a modern, neon-accented dark UI designed for engineers, researchers, and forensic analysts.

---

## 🚀 Key Features

### 1. Forensic Image Inspector
Analyze images (JPEG, PNG, BMP, GIF) with precision:
- **Pixel-Level Inspection**: Real-time coordinate and RGB readout on mouse hover.
- **Color Histograms**: Visual distribution of R, G, B, and Luminance channels.
- **Quantization Heatmaps**: Visualize JPEG DQT matrices in an 8x8 grid to detect re-compression and quality level consistency.
- **Exif & Metadata**: Deep dive into camera settings, lens info, and GPS coordinates.

### 2. Modern Video Inspector
Inspect MP4 and MOV containers with advanced visualizers:
- **VBR Bitrate Analysis**: Time-based line charts showing variable bitrate fluctuations (calculated from `stsz` and `stts` boxes).
- **Box Volume Treemap**: A visual block diagram showing the byte-distribution of file structures (e.g., `mdat` vs `moov`).
- **Camera LCD Infographic**: High-contrast display of Shutter Speed, ISO, Aperture, and Lens model.

### 3. MediaInfo-Style Dashboards
- **Unified Summaries**: Instant access to General, Video, and Audio stream summaries in a clean, card-based format.
- **Motion Photo Support**: Specialized handling for Samsung Motion Photos, displaying both image and embedded video metadata side-by-side.

### 4. Interactive Binary Explorer
- **Box/Marker Tree**: Hierarchical view of the file's internal structure.
- **Hex Syncing**: Clicking any structure element instantly scrolls the Hex viewer to the exact binary offset.
- **Color Coding**: Visual cues for different data types (Video=Green, Audio=Blue, Meta=Purple).

---

## 💾 Download & Installation

The application is automatically built for Windows, Linux, and macOS. You can download the latest installers from the **GitHub Actions** tab:

1. Go to the [Actions](https://github.com/abracadabra799/unwrapMedia/actions) page.
2. Select the most recent **"Package unwrapMedia"** run.
3. Scroll down to the **Artifacts** section.
4. Download the version corresponding to your OS:
    - **Windows**: `.msi` (includes bundled VLC)
    - **Linux**: `.deb` (includes bundled VLC)
    - **macOS**: `.dmg` (uses system-installed VLC)

---

## 🛠 Tech Stack

- **Language**: Kotlin
- **Framework**: Compose Multiplatform for Desktop (JVM)
- **Runtime**: Java 21+
- **Build System**: Gradle

---

## 🏁 Getting Started

### Prerequisites
- JDK 21 or higher installed on your machine.

### Run the Application
You can run the application directly using Gradle:
```bash
./gradlew :app:run
```

### Build Distribution
To package the app for your OS:
```bash
./gradlew :app:packageDistributionForCurrentOS
```

---

## 📸 UI Overview

The application features a modular 3-column layout:
1. **Left**: Media Structure (Tree View)
2. **Center**: Analysis Dashboard (Visual Preview, Charts, and Summaries)
3. **Right**: Detailed Properties (Field-level data and Grid matrices)
4. **Bottom**: Hex & Raw Data Viewer

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an issue for feature requests.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
