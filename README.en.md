**Language:** [한국어](README.md) | English

# unwrapMedia

**unwrapMedia** is a desktop media viewer and forensic analysis workbench. It brings playback, visual inspection, container structure, metadata, and byte-level data together so engineers can understand not only what a media file looks like, but how it is built.

## Explore the media

### Image viewing and analysis

View still images alongside parsed container structure, EXIF and camera metadata, image dimensions, color information, and synchronized hex data. When a JPEG contains an embedded EXIF thumbnail, unwrapMedia can show it next to the full-resolution image. Gain-map and HDR metadata tools help inspect modern image formats and their auxiliary images.

<p align="center">
  <img src="docs/screenshots/showcase/01-image-analysis.jpg" width="920" alt="A silver tabby cat in unwrapMedia's image inspector, with the embedded EXIF thumbnail beside the full-resolution image" />
</p>

### Video playback and frame analysis

Play a clip while inspecting its container and codec details. Frame analysis visualizes frame types and sizes, and the filmstrip makes it easy to scan the clip and jump to a frame.

<p align="center">
  <img src="docs/screenshots/showcase/02-video-filmstrip.jpg" width="920" alt="unwrapMedia playing a kitten video, with frame-type graph and a filmstrip of sampled frames" />
</p>

The original sample clip is [kitten-pounce.mp4](docs/showcase-media/video/kitten-pounce.mp4). Its short animated preview shows the motion represented by the filmstrip:

<p align="center">
  <img src="docs/showcase-media/video/kitten-pounce-preview.gif" width="480" alt="An orange kitten chasing a yellow feather toy" />
</p>

### Motion Photo playback and inspection

unwrapMedia detects Samsung- and Google-style Motion Photos, displays the still image, and can play, extract, and inspect the embedded video. The included campfire sample is a real JPEG Motion Photo with an embedded video segment.

<p align="center">
  <img src="docs/screenshots/showcase/03-motion-photo-fire.jpg" width="920" alt="unwrapMedia showing the campfire still image, EXIF thumbnail, parsed JPEG and Samsung Motion Photo structure, and embedded video player" />
</p>

Try [campfire-motion-photo.jpg](docs/showcase-media/images/campfire-motion-photo.jpg) or its [standalone video](docs/showcase-media/video/campfire-motion.mp4). The short animation below previews the embedded clip:

<p align="center">
  <img src="docs/showcase-media/video/campfire-motion-preview.gif" width="480" alt="A campfire flickering at blue hour, previewing the Motion Photo's embedded video" />
</p>

### Media comparison

Compare two files to inspect visual pixel differences, metadata, container structure, and byte-level changes. For video quality work, the benchmark tools report metrics such as VMAF, PSNR, and SSIM.

<p align="center">
  <img src="docs/screenshots/showcase/05-media-comparison.jpg" width="920" alt="unwrapMedia's Media Comparison Analyzer comparing two cat images with a visual difference view and split wiper" />
</p>

Try the [cat comparison pair](docs/showcase-media/images/): the images are nearly identical, except the second has a teal collar and bell. Open both files and choose **Tools → Compare Files** to see the pixel difference in the analyzer.

### Audio playback and analysis

Listen while examining audio metadata and the waveform. The player supports navigation and zoom, with channel controls available for supported audio layouts.

<p align="center">
  <img src="docs/screenshots/showcase/04-audio-waveform.jpg" width="920" alt="unwrapMedia's audio inspector showing WAV structure, metadata, hex data, and a green waveform" />
</p>

Try the generated [gentle-tones.wav](docs/showcase-media/audio/gentle-tones.wav).

## Diagnose timing and file health

### A/V sync and dropped-frame analysis

The A/V sync analyzer compares audio and video presentation timelines to help investigate offset and duration differences. Frame-interval analysis visualizes timestamp spacing and irregular intervals that may indicate timing problems or dropped-frame candidates; it is diagnostic evidence, not a guarantee that every capture-side drop can be identified.

<p align="center">
  <img src="docs/screenshots/showcase/07-frame-interval-analysis.jpg" width="920" alt="unwrapMedia frame-interval analysis showing a 125 ms timestamp gap against the clip's 42.4 ms baseline" />
</p>

Use [avsync-frame-gap.mp4](docs/showcase-media/video/avsync-frame-gap.mp4) to try both tools. This synthetic clip intentionally includes an audio delay and a video timestamp gap; see the sample-media notes for details.

### AI-assisted diagnosis

Structure checks flag parser-detected warnings, and the AI prompt generator prepares file-specific technical context that you can provide to an AI assistant. unwrapMedia generates the prompt; it does not require or bundle an AI service. On Windows, the prompt window can open an embedded PowerShell session. The terminal pane shown here is an illustrative Windows example, not a live PowerShell capture.

<p align="center">
  <img src="docs/screenshots/showcase/06-ai-cli-powershell.jpg" width="920" alt="unwrapMedia's AI analysis prompt beside an illustrative Windows PowerShell panel showing the claude CLI command and pasted media-analysis context" />
</p>

### Structure tree, hex viewer, and CLI

Explore parsed boxes and markers with byte offsets linked to the hex viewer. The command-line `dump` and `check` modes support scripted inspection and CI workflows.

## Sample media

The small, synthetic showcase assets are in [`docs/showcase-media/`](docs/showcase-media/README.md). They are generated for this repository; the A/V timing sample intentionally contains test anomalies. See the sample catalog for sizes, formats, and usage.

## Supported formats

| Category | Examples |
|---|---|
| Images | JPEG/JPG, PNG, GIF, WebP, AVIF, HEIC/HEIF, BMP, TIFF/TIF, camera RAW (CR2, NEF, ARW, DNG; metadata and embedded-preview inspection) |
| Video containers/files | MP4, MOV, M4V, WebM, IVF, AVI, FLV, WMV, ASF; standalone AV1 and APV streams |
| Video codecs | AVC/H.264, HEVC/H.265, AV1, AV2 (Windows playback), APV, VP8/VP9, Dolby Vision |
| Audio | WAV, MP3, M4A, AAC, FLAC, OGG, Opus, AIFF/AIF/AIFC, WMA, raw PCM |
| Raw pixels | RAW, RGB, RGBA, YUV, NV12, NV21 |

Codec playback and analysis depend on the container, stream profile, and available decoder. AV2 playback is currently Windows-only.

## Get started

Download a package from [GitHub Actions](https://github.com/abracadabra799/unwrapMedia/actions), or build with JDK 21+:

```bash
./gradlew :app:run
./gradlew test
./gradlew :app:package
```

CLI examples:

```bash
unwrapMedia dump <file>              # Dump the parsed structure as JSON
unwrapMedia check <file>             # Check for structural warnings
unwrapMedia check <file> --prompt    # Generate an AI diagnostic prompt
```

## License

MIT — see [LICENSE](LICENSE).
