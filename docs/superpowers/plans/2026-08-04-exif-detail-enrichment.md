# EXIF/TIFF/DNG Detail Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `ExifDecoder.kt`'s tag coverage toward ExifTool's public-spec baseline (IFD0/Exif/GPS standard tags plus DNG private tags), translate enum/flag/rational values into human-readable strings, and let the user click any field in Detailed Properties to jump the hex viewer straight to its bytes.

**Architecture:** Tasks 1-2 only touch `ExifDecoder.kt`'s existing lookup-table pattern (bigger maps, one new value-interpretation dispatch point) -- no changes to IFD traversal logic. Task 3 wires the `offset`/`length` that `BoxField` already carries per-field into the hex viewer's existing `highlightRange` mechanism, which today only fires for whole-node tree selection.

**Tech Stack:** Kotlin, existing `ExifDecoder.kt`/`BoxNode.kt` parser model, Compose Desktop UI.

## Global Constraints

- Scope is the public EXIF 2.32 / TIFF 6.0 / Adobe DNG 1.6 tag spaces only. Camera-vendor MakerNote formats beyond the existing Samsung Type2 decoder (Canon, Nikon, Sony, etc.) are out of scope.
- Unmapped tags and unmapped values keep exactly today's fallback behavior (`Tag 0xXXXX` name, raw formatted value) -- coverage only grows, nothing regresses.
- Field-level hex highlighting applies to the plain `PropertyRow`-rendered fields in `DetailedPropertiesPanel` (works for every format's `BoxField`s, not just EXIF) -- `GridDisplay`, `EmbeddedTableView`, and `XmpFieldDisplay` are unchanged.

---

## Task 1: Expand IFD0/Exif/GPS tag tables and add DNG tags

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt:20-73` (the three tag-name maps)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt` (append)

**Interfaces:**
- Consumes: nothing new.
- Produces (consumed by Task 2): the expanded `TAG_NAMES_IFD0`, `TAG_NAMES_EXIF`, `TAG_NAMES_GPS` maps (same `Map<Int, String>` type as before -- Task 2 reads from these plus a new value-label table keyed by the same tag IDs).

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`, inside `class ExifDecoderTest { ... }`, before the closing `}`:

```kotlin
    @Test
    fun `resolves a newly-added IFD0 baseline tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x03, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, // Compression (0x0103), SHORT, count=1, value=6
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("6", ifds[0].fields.first { it.name == "Compression" }.value)
        reader.close()
    }

    @Test
    fun `resolves a newly-added Exif sub-IFD tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x35, 0xA4.toByte(), 0x02, 0x00, 0x08, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // LensModel (0xA435), ASCII, count=8, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x35, 0x30, 0x6d, 0x6d, 0x20, 0x66, 0x31, 0x00, // "50mm f1\0" at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("50mm f1", exifIfd.fields.first { it.name == "LensModel" }.value)
        reader.close()
    }

    @Test
    fun `resolves a newly-added GPS tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x25, 0x88.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x1d, 0x00, 0x02, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // GPSDateStamp (0x001D), ASCII, count=11, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x32, 0x30, 0x32, 0x36, 0x3a, 0x30, 0x38, 0x3a, 0x30, 0x34, 0x00, // "2026:08:04\0" at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val gpsIfd = ifds[0].children.first { it.type == "GPS" }
        assertEquals("2026:08:04", gpsIfd.fields.first { it.name == "GPSDateStamp" }.value)
        reader.close()
    }

    @Test
    fun `resolves a DNG private tag stored in IFD0`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0xC6.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x04, 0x00, // DNGVersion (0xC612), BYTE, count=4, value=1.1.4.0
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("01 01 04 00", ifds[0].fields.first { it.name == "DNGVersion" }.value)
        reader.close()
    }

    @Test
    fun `existing tags and the unmapped-tag fallback are unaffected by the table expansion`() {
        // Same fixture as "unrecognized tag falls back to a hex label" above -- re-asserted here
        // to make the no-regression guarantee explicit for this task.
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x34, 0x12, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("42", ifds[0].fields.first { it.name == "Tag 0x1234" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ExifDecoderTest"`
Expected: the 4 new tests fail (`Compression`/`LensModel`/`GPSDateStamp`/`DNGVersion` not found, falls back to `Tag 0x....`); the unaffected-fallback test passes already (it's a regression guard, not new behavior).

- [ ] **Step 3: Replace the three tag-name maps**

In `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`, find:

```kotlin
private val TAG_NAMES_IFD0 = mapOf(
    0x0100 to "ImageWidth",
    0x0101 to "ImageLength",
    0x010F to "Make",
    0x0110 to "Model",
    0x0112 to "Orientation",
    0x011A to "XResolution",
    0x011B to "YResolution",
    0x0128 to "ResolutionUnit",
    0x0131 to "Software",
    0x0132 to "DateTime",
    0x0213 to "YCbCrPositioning",
)

private val TAG_NAMES_EXIF = mapOf(
    0x829A to "ExposureTime",
    0x829D to "FNumber",
    0x8822 to "ExposureProgram",
    0x8827 to "ISOSpeedRatings",
    0x9000 to "ExifVersion",
    0x9003 to "DateTimeOriginal",
    0x9004 to "DateTimeDigitized",
    0x9010 to "OffsetTime",
    0x9011 to "OffsetTimeOriginal",
    0x9201 to "ShutterSpeedValue",
    0x9202 to "ApertureValue",
    0x9203 to "BrightnessValue",
    0x9204 to "ExposureBiasValue",
    0x9205 to "MaxApertureValue",
    0x9207 to "MeteringMode",
    0x9209 to "Flash",
    0x920A to "FocalLength",
    0x9290 to "SubSecTime",
    0x9291 to "SubSecTimeOriginal",
    0x9292 to "SubSecTimeDigitized",
    0xA001 to "ColorSpace",
    0xA002 to "PixelXDimension",
    0xA003 to "PixelYDimension",
    0xA402 to "ExposureMode",
    0xA403 to "WhiteBalance",
    0xA404 to "DigitalZoomRatio",
    0xA405 to "FocalLengthIn35mmFilm",
    0xA406 to "SceneCaptureType",
    0xA420 to "ImageUniqueID",
)

private val TAG_NAMES_GPS = mapOf(
    0x0001 to "GPSLatitudeRef",
    0x0002 to "GPSLatitude",
    0x0003 to "GPSLongitudeRef",
    0x0004 to "GPSLongitude",
    0x0005 to "GPSAltitudeRef",
    0x0006 to "GPSAltitude",
)
```

Replace with:

```kotlin
// TIFF 6.0 baseline tags plus Adobe DNG 1.6's private tag range (0xC612-0xC761) -- DNG stores its
// tags directly in IFD0 alongside standard TIFF tags (not a separate pointer-based sub-IFD), so
// they belong in this same map rather than a parallel one threaded through decodeIfd separately.
private val TAG_NAMES_IFD0 = mapOf(
    0x00FE to "NewSubfileType",
    0x0100 to "ImageWidth",
    0x0101 to "ImageLength",
    0x0102 to "BitsPerSample",
    0x0103 to "Compression",
    0x0106 to "PhotometricInterpretation",
    0x010E to "ImageDescription",
    0x010F to "Make",
    0x0110 to "Model",
    0x0111 to "StripOffsets",
    0x0112 to "Orientation",
    0x0115 to "SamplesPerPixel",
    0x0116 to "RowsPerStrip",
    0x0117 to "StripByteCounts",
    0x011A to "XResolution",
    0x011B to "YResolution",
    0x011C to "PlanarConfiguration",
    0x0128 to "ResolutionUnit",
    0x0131 to "Software",
    0x0132 to "DateTime",
    0x013B to "Artist",
    0x013E to "WhitePoint",
    0x013F to "PrimaryChromaticities",
    0x0211 to "YCbCrCoefficients",
    0x0212 to "YCbCrSubSampling",
    0x0213 to "YCbCrPositioning",
    0x0214 to "ReferenceBlackWhite",
    0x8298 to "Copyright",
    0x828D to "CFARepeatPatternDim",
    0x828E to "CFAPattern",
    // -- DNG private tags (Adobe DNG 1.6 spec) --
    0xC612 to "DNGVersion",
    0xC613 to "DNGBackwardVersion",
    0xC614 to "UniqueCameraModel",
    0xC615 to "LocalizedCameraModel",
    0xC616 to "CFAPlaneColor",
    0xC617 to "CFALayout",
    0xC618 to "LinearizationTable",
    0xC619 to "BlackLevelRepeatDim",
    0xC61A to "BlackLevel",
    0xC61B to "BlackLevelDeltaH",
    0xC61C to "BlackLevelDeltaV",
    0xC61D to "WhiteLevel",
    0xC61E to "DefaultScale",
    0xC61F to "DefaultCropOrigin",
    0xC620 to "DefaultCropSize",
    0xC621 to "ColorMatrix1",
    0xC622 to "ColorMatrix2",
    0xC623 to "CameraCalibration1",
    0xC624 to "CameraCalibration2",
    0xC627 to "AnalogBalance",
    0xC628 to "AsShotNeutral",
    0xC629 to "AsShotWhiteXY",
    0xC62A to "BaselineExposure",
    0xC62B to "BaselineNoise",
    0xC62C to "BaselineSharpness",
    0xC62D to "BayerGreenSplit",
    0xC62E to "LinearResponseLimit",
    0xC62F to "CameraSerialNumber",
    0xC630 to "LensInfo",
    0xC633 to "ShadowScale",
    0xC635 to "MakerNoteSafety",
    0xC65A to "CalibrationIlluminant1",
    0xC65B to "CalibrationIlluminant2",
    0xC65C to "BestQualityScale",
    0xC65D to "RawDataUniqueID",
    0xC68B to "OriginalRawFileName",
    0xC6BF to "ColorimetricReference",
    0xC6F8 to "ProfileName",
    0xC6FD to "ProfileEmbedPolicy",
    0xC6FE to "ProfileCopyright",
    0xC714 to "ForwardMatrix1",
    0xC715 to "ForwardMatrix2",
    0xC761 to "NoiseProfile",
)

private val TAG_NAMES_EXIF = mapOf(
    0x829A to "ExposureTime",
    0x829D to "FNumber",
    0x8822 to "ExposureProgram",
    0x8827 to "ISOSpeedRatings",
    0x8830 to "SensitivityType",
    0x8831 to "StandardOutputSensitivity",
    0x8832 to "RecommendedExposureIndex",
    0x9000 to "ExifVersion",
    0x9003 to "DateTimeOriginal",
    0x9004 to "DateTimeDigitized",
    0x9010 to "OffsetTime",
    0x9011 to "OffsetTimeOriginal",
    0x9101 to "ComponentsConfiguration",
    0x9102 to "CompressedBitsPerPixel",
    0x9201 to "ShutterSpeedValue",
    0x9202 to "ApertureValue",
    0x9203 to "BrightnessValue",
    0x9204 to "ExposureBiasValue",
    0x9205 to "MaxApertureValue",
    0x9206 to "SubjectDistance",
    0x9207 to "MeteringMode",
    0x9208 to "LightSource",
    0x9209 to "Flash",
    0x920A to "FocalLength",
    0x9214 to "SubjectArea",
    0x9286 to "UserComment",
    0x9290 to "SubSecTime",
    0x9291 to "SubSecTimeOriginal",
    0x9292 to "SubSecTimeDigitized",
    0xA000 to "FlashpixVersion",
    0xA001 to "ColorSpace",
    0xA002 to "PixelXDimension",
    0xA003 to "PixelYDimension",
    0xA20B to "FlashEnergy",
    0xA20E to "FocalPlaneXResolution",
    0xA20F to "FocalPlaneYResolution",
    0xA210 to "FocalPlaneResolutionUnit",
    0xA214 to "SubjectLocation",
    0xA215 to "ExposureIndex",
    0xA217 to "SensingMethod",
    0xA300 to "FileSource",
    0xA301 to "SceneType",
    0xA302 to "CFAPattern",
    0xA401 to "CustomRendered",
    0xA402 to "ExposureMode",
    0xA403 to "WhiteBalance",
    0xA404 to "DigitalZoomRatio",
    0xA405 to "FocalLengthIn35mmFilm",
    0xA406 to "SceneCaptureType",
    0xA407 to "GainControl",
    0xA408 to "Contrast",
    0xA409 to "Saturation",
    0xA40A to "Sharpness",
    0xA40C to "SubjectDistanceRange",
    0xA420 to "ImageUniqueID",
    0xA431 to "BodySerialNumber",
    0xA432 to "LensSpecification",
    0xA433 to "LensMake",
    0xA434 to "LensModel",
    0xA435 to "LensSerialNumber",
    0xA460 to "CompositeImage",
)

private val TAG_NAMES_GPS = mapOf(
    0x0000 to "GPSVersionID",
    0x0001 to "GPSLatitudeRef",
    0x0002 to "GPSLatitude",
    0x0003 to "GPSLongitudeRef",
    0x0004 to "GPSLongitude",
    0x0005 to "GPSAltitudeRef",
    0x0006 to "GPSAltitude",
    0x0007 to "GPSTimeStamp",
    0x0008 to "GPSSatellites",
    0x0009 to "GPSStatus",
    0x000A to "GPSMeasureMode",
    0x000B to "GPSDOP",
    0x000C to "GPSSpeedRef",
    0x000D to "GPSSpeed",
    0x000E to "GPSTrackRef",
    0x000F to "GPSTrack",
    0x0010 to "GPSImgDirectionRef",
    0x0011 to "GPSImgDirection",
    0x0012 to "GPSMapDatum",
    0x0013 to "GPSDestLatitudeRef",
    0x0014 to "GPSDestLatitude",
    0x0015 to "GPSDestLongitudeRef",
    0x0016 to "GPSDestLongitude",
    0x0017 to "GPSDestBearingRef",
    0x0018 to "GPSDestBearing",
    0x0019 to "GPSDestDistanceRef",
    0x001A to "GPSDestDistance",
    0x001B to "GPSProcessingMethod",
    0x001C to "GPSAreaInformation",
    0x001D to "GPSDateStamp",
    0x001E to "GPSHPositioningError",
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ExifDecoderTest"`
Expected: BUILD SUCCESSFUL, all tests pass (the 5 new plus all pre-existing ones -- confirms no tag ID collisions and no regressions).

- [ ] **Step 5: Run the full test suite (regression check)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions elsewhere.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "feat: expand IFD0/Exif/GPS tag coverage and add DNG private tags"
```

---

## Task 2: Human-readable value interpretation

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt` (add value tables + dispatch, modify `decodeIfd`'s `else ->` branch)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt` (append)

**Interfaces:**
- Consumes (from Task 1): `TAG_NAMES_IFD0`, `TAG_NAMES_EXIF`, `TAG_NAMES_GPS`.
- Produces: `interpretedDisplay(group: String, tag: Int, fieldType: Int, count: Int, reader: ByteReader, valuePos: Long, littleEndian: Boolean): String?` -- returns a human-readable override when one applies, `null` otherwise (caller falls back to the existing `formatTiffValue`). Not consumed by any later task, but this is the one dispatch point Task 3's UI work does NOT need to touch (value formatting is fully contained in this file).

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`, before the closing `}`:

```kotlin
    @Test
    fun `translates Orientation into a human-readable label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, // Orientation (0x0112), SHORT, count=1, value=6
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("Rotate 90 CW", ifds[0].fields.first { it.name == "Orientation" }.value)
        reader.close()
    }

    @Test
    fun `translates ExposureProgram into a human-readable label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x22, 0x88.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, // ExposureProgram (0x8822), SHORT, count=1, value=2
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("Normal program", exifIfd.fields.first { it.name == "ExposureProgram" }.value)
        reader.close()
    }

    @Test
    fun `formats FNumber as an f-stop instead of a raw fraction`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x9d, 0x82.toByte(), 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // FNumber (0x829D), RATIONAL, count=1, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x1c, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, // 28/10 at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("f/2.8", exifIfd.fields.first { it.name == "FNumber" }.value)
        reader.close()
    }

    @Test
    fun `formats a sub-one-second ExposureTime as a fraction with a unit suffix`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x9a, 0x82.toByte(), 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // ExposureTime (0x829A), RATIONAL, count=1, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x7d, 0x00, 0x00, 0x00, // 1/125 at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("1/125s", exifIfd.fields.first { it.name == "ExposureTime" }.value)
        reader.close()
    }

    @Test
    fun `a tag with no value-interpretation entry still shows the raw formatted value`() {
        // ISOSpeedRatings (0x8827) has a tag name but no enum/rational entry in this task's
        // tables -- must still show the plain formatted number, not throw or show blank.
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x27, 0x88.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, // ISOSpeedRatings (0x8827), SHORT, count=1, value=100
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("100", exifIfd.fields.first { it.name == "ISOSpeedRatings" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ExifDecoderTest"`
Expected: the 4 new interpretation tests fail (values still show raw `"6"` / `"2"` / `"28/10"` / `"1/125"`); the "no entry" test already passes (it's asserting today's existing behavior, which this task must not change).

- [ ] **Step 3: Add the value-interpretation tables and dispatch function**

In `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`, find:

```kotlin
fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
```

Replace with:

```kotlin
// Enum/flag tags whose raw integer value is translated to a label ExifTool would also show --
// keyed by (IFD group label, tag ID) so the same numeric tag ID under a different group (e.g. a
// vendor MakerNote reusing a low tag number) is never misinterpreted. Only single-value SHORT/
// LONG/SSHORT/SLONG fields are looked up here (see interpretedDisplay) -- ASCII ref tags like
// GPSLatitudeRef ("N"/"S") are already human-readable as raw text and never reach this table.
private val TAG_VALUE_LABELS: Map<Pair<String, Int>, Map<Int, String>> = mapOf(
    ("IFD0" to 0x0112) to mapOf(
        1 to "Horizontal (normal)", 2 to "Mirror horizontal", 3 to "Rotate 180",
        4 to "Mirror vertical", 5 to "Mirror horizontal and rotate 270 CW", 6 to "Rotate 90 CW",
        7 to "Mirror horizontal and rotate 90 CW", 8 to "Rotate 270 CW",
    ),
    ("IFD0" to 0x0128) to mapOf(1 to "None", 2 to "inches", 3 to "cm"),
    ("IFD0" to 0x0213) to mapOf(1 to "Centered", 2 to "Co-sited"),
    ("IFD0" to 0x0106) to mapOf(0 to "WhiteIsZero", 1 to "BlackIsZero", 2 to "RGB", 6 to "YCbCr"),
    ("IFD0" to 0x0103) to mapOf(1 to "Uncompressed", 6 to "JPEG (old-style)", 7 to "JPEG", 8 to "Adobe Deflate"),
    ("Exif" to 0x8822) to mapOf(
        0 to "Not defined", 1 to "Manual", 2 to "Normal program", 3 to "Aperture priority",
        4 to "Shutter priority", 5 to "Creative program", 6 to "Action program",
        7 to "Portrait mode", 8 to "Landscape mode",
    ),
    ("Exif" to 0x9207) to mapOf(
        0 to "Unknown", 1 to "Average", 2 to "Center-weighted average", 3 to "Spot",
        4 to "Multi-spot", 5 to "Pattern", 6 to "Partial", 255 to "Other",
    ),
    // ExifTool's published Flash bitmask table -- the low bits mean "fired", higher bits encode
    // return-light detection and flash mode, so this is a lookup table of known composite values
    // rather than a formula.
    ("Exif" to 0x9209) to mapOf(
        0x0 to "No Flash", 0x1 to "Fired", 0x5 to "Fired, Return not detected",
        0x7 to "Fired, Return detected", 0x8 to "Did not fire, compulsory",
        0x9 to "Fired, compulsory", 0x10 to "Did not fire, auto mode",
        0x18 to "Did not fire, auto mode", 0x19 to "Fired, auto mode",
        0x1D to "Fired, auto mode, Return not detected", 0x1F to "Fired, auto mode, Return detected",
        0x20 to "No flash function", 0x41 to "Fired, red-eye reduction",
        0x45 to "Fired, red-eye reduction, Return not detected",
        0x47 to "Fired, red-eye reduction, Return detected",
        0x49 to "Fired, compulsory, red-eye reduction",
        0x4D to "Fired, compulsory, red-eye reduction, Return not detected",
        0x4F to "Fired, compulsory, red-eye reduction, Return detected",
        0x59 to "Fired, auto, red-eye reduction",
    ),
    ("Exif" to 0xA403) to mapOf(0 to "Auto", 1 to "Manual"),
    ("Exif" to 0xA001) to mapOf(1 to "sRGB", 65535 to "Uncalibrated"),
    ("Exif" to 0xA402) to mapOf(0 to "Auto", 1 to "Manual", 2 to "Auto bracket"),
    ("Exif" to 0xA406) to mapOf(0 to "Standard", 1 to "Landscape", 2 to "Portrait", 3 to "Night scene"),
    ("Exif" to 0x9208) to mapOf(
        0 to "Unknown", 1 to "Daylight", 2 to "Fluorescent", 3 to "Tungsten", 4 to "Flash",
        9 to "Fine weather", 10 to "Cloudy", 11 to "Shade", 17 to "Standard light A",
        18 to "Standard light B", 19 to "Standard light C", 24 to "ISO studio tungsten", 255 to "Other",
    ),
    ("Exif" to 0xA217) to mapOf(
        1 to "Not defined", 2 to "One-chip color area", 3 to "Two-chip color area",
        4 to "Three-chip color area", 5 to "Color sequential area", 7 to "Trilinear",
        8 to "Color sequential linear",
    ),
    ("Exif" to 0xA300) to mapOf(3 to "Digital Camera"),
    ("Exif" to 0xA301) to mapOf(1 to "Directly photographed"),
    ("Exif" to 0xA401) to mapOf(0 to "Normal", 1 to "Custom"),
    ("Exif" to 0xA407) to mapOf(
        0 to "None", 1 to "Low gain up", 2 to "High gain up", 3 to "Low gain down", 4 to "High gain down",
    ),
    ("Exif" to 0xA408) to mapOf(0 to "Normal", 1 to "Soft", 2 to "Hard"),
    ("Exif" to 0xA409) to mapOf(0 to "Normal", 1 to "Low saturation", 2 to "High saturation"),
    ("Exif" to 0xA40A) to mapOf(0 to "Normal", 1 to "Soft", 2 to "Hard"),
    ("Exif" to 0xA40C) to mapOf(0 to "Unknown", 1 to "Macro", 2 to "Close", 3 to "Distant"),
)

// Direct-unit rational tags (the stored fraction IS the displayed unit, e.g. FNumber's "28/10"
// literally means f/2.8) -- deliberately excludes APEX-encoded tags like ApertureValue/
// ShutterSpeedValue/BrightnessValue, whose displayed value requires a 2^(APEX/n) conversion, not
// just a nicer fraction format; those still show as a raw rational until that conversion is added.
private fun formatExposureTime(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    if (num == 0L) return "0s"
    return if (num < den) "$num/${den}s" else "%.1fs".format(num.toDouble() / den)
}

private fun formatFNumber(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    return "f/%.1f".format(num.toDouble() / den)
}

private fun formatFocalLength(num: Long, den: Long): String {
    if (den == 0L) return "$num/$den"
    return "%.1fmm".format(num.toDouble() / den)
}

private val RATIONAL_FORMATTERS: Map<Pair<String, Int>, (Long, Long) -> String> = mapOf(
    ("Exif" to 0x829A) to ::formatExposureTime,
    ("Exif" to 0x829D) to ::formatFNumber,
    ("Exif" to 0x920A) to ::formatFocalLength,
)

// Returns a human-readable override for a single-value SHORT/LONG/SSHORT/SLONG (enum/flag lookup)
// or RATIONAL/SRATIONAL (unit formatter) field when this task's tables have an entry for
// (group, tag); null otherwise, in which case the caller falls back to formatTiffValue's raw
// output exactly as before. Multi-value fields (count != 1) are never interpreted -- an "average"
// or "list" reading of an enum table wouldn't be meaningful.
private fun interpretedDisplay(group: String, tag: Int, fieldType: Int, count: Int, reader: ByteReader, valuePos: Long, littleEndian: Boolean): String? {
    if (count != 1) return null
    return when (fieldType) {
        3 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt16Endian(reader, valuePos, littleEndian))
        8 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt16Endian(reader, valuePos, littleEndian).toShort().toInt())
        4 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt32Endian(reader, valuePos, littleEndian).toInt())
        9 -> TAG_VALUE_LABELS[group to tag]?.get(readUInt32Endian(reader, valuePos, littleEndian).toInt())
        5 -> {
            val num = readUInt32Endian(reader, valuePos, littleEndian)
            val den = readUInt32Endian(reader, valuePos + 4, littleEndian)
            RATIONAL_FORMATTERS[group to tag]?.invoke(num, den)
        }
        10 -> {
            val num = readUInt32Endian(reader, valuePos, littleEndian).toInt().toLong()
            val den = readUInt32Endian(reader, valuePos + 4, littleEndian).toInt().toLong()
            RATIONAL_FORMATTERS[group to tag]?.invoke(num, den)
        }
        else -> null
    }
}

fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
```

- [ ] **Step 4: Wire the dispatch into `decodeIfd`'s tag-formatting branch**

In `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`, find:

```kotlin
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
```

Replace with:

```kotlin
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = interpretedDisplay(label, tag, fieldType, count.toInt(), reader, valueAbsolutePos, littleEndian)
                        ?: formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
```

(`label` is `decodeIfd`'s existing group-name parameter -- `"IFD0"`, `"Exif"`, `"GPS"`, etc. -- already in scope at this call site.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ExifDecoderTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Run the full test suite (regression check)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "feat: translate enum/flag EXIF values and direct-unit rationals into human-readable strings"
```

---

## Task 3: Field-level hex highlighting

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add `TabState.selectedField`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (make field rows clickable)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (hex scroll/highlight prefers the selected field)

**Interfaces:**
- Consumes: `BoxField(name, value, offset, length)` (`BoxNode.kt`, already exists, no changes).
- Produces: `TabState.selectedField: BoxField?` -- read by `Main.kt`'s existing hex-scroll `LaunchedEffect` and `HexView`'s `highlightRange` computation.

No automated test for this task (Compose click/selection UI, consistent with this project's existing convention -- see the frame-interval-analysis and audio zoom/pan plans' UI tasks). Verified by compiling, the full regression suite, and the controller's manual run.

- [ ] **Step 1: Add `selectedField` to `TabState`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

```kotlin
    var selected: BoxNode? by mutableStateOf(null)
```

Replace with:

```kotlin
    var selected: BoxNode? by mutableStateOf(null)
    // Drives field-level hex highlighting (Main.kt) -- deliberately NOT reset every time
    // `selected` changes; instead Main.kt checks membership (`selectedField in selected.fields`)
    // before using it, so a stale value from a previously-selected node is simply ignored rather
    // than needing to be cleared at every one of `selected`'s several assignment sites.
    var selectedField: BoxField? by mutableStateOf(null)
```

- [ ] **Step 2: Make field rows clickable in `DetailedPropertiesPanel`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, find:

```kotlin
                        items(selectedNode.fields) { field ->
                            if (field.name == "xmp") {
                                XmpFieldDisplay(field.value)
                            } else {
                                PropertyRow(field.name, field.value)
                            }
                        }
```

Replace with:

```kotlin
                        items(selectedNode.fields) { field ->
                            if (field.name == "xmp") {
                                XmpFieldDisplay(field.value)
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (tab.selectedField == field) AppColors.Selection else Color.Transparent)
                                        .clickable { tab.selectedField = field },
                                ) {
                                    PropertyRow(field.name, field.value)
                                }
                            }
                        }
```

- [ ] **Step 3: Prefer the selected field in the hex viewer's scroll/highlight**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
                        val currentTab = appState.tabs[appState.selectedTabIndex]
                        val hexListState = remember(currentTab) { androidx.compose.foundation.lazy.LazyListState() }

                        LaunchedEffect(currentTab.selected) {
                            currentTab.selected?.let {
                                hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                            }
                        }
```

Replace with:

```kotlin
                        val currentTab = appState.tabs[appState.selectedTabIndex]
                        val hexListState = remember(currentTab) { androidx.compose.foundation.lazy.LazyListState() }
                        // A field selected on a PREVIOUSLY-selected node is stale once the tree
                        // selection moves on -- membership-checking against the current node's own
                        // field list here means Main.kt never has to hunt down and reset
                        // selectedField at every place currentTab.selected gets reassigned.
                        val activeField = currentTab.selected?.fields?.let { fields ->
                            currentTab.selectedField?.takeIf { it in fields }
                        }

                        LaunchedEffect(currentTab.selected, currentTab.selectedField) {
                            val field = activeField
                            if (field != null) {
                                hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                            } else {
                                currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }
```

Then find:

```kotlin
                            HexView(
                                file = currentTab.file,
                                highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                listState = hexListState,
                            )
```

Replace with:

```kotlin
                            HexView(
                                file = currentTab.file,
                                highlightRange = activeField?.let { it.offset until (it.offset + it.length) }
                                    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                listState = hexListState,
                            )
```

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite (regression check)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: clicking a Detailed Properties field jumps the hex viewer to its exact bytes"
```

---

## Task 4: Controller-performed manual verification

This task has no automated test and no subagent dispatch -- run it directly in the controlling session, matching this project's established precedent for real runtime verification.

- [ ] **Step 1: Launch the app**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

- [ ] **Step 2: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- Open a JPEG with EXIF data: previously-unlabeled `Tag 0x....` entries in IFD0/Exif/GPS now show real names for standard tags (exact set depends on what the test file actually contains).
- Orientation, ExposureProgram, Flash, WhiteBalance (or whichever enum tags the test file has) show readable labels, not bare numbers.
- FNumber/ExposureTime/FocalLength (if present) show as `f/x.x` / `1/xs` / `xmm`, not raw fractions.
- A tag with no interpretation entry still shows its raw value -- nothing is blank or crashes.
- If a DNG or camera RAW file is available: DNG-range tags (e.g. `DNGVersion`, `ColorMatrix1`) show real names instead of `Tag 0xC6..`.
- Clicking a field row in Detailed Properties highlights that row and scrolls the hex viewer to its exact byte offset; clicking a different field moves the highlight; clicking a different tree node still falls back to highlighting that whole node's range (today's existing behavior, unaffected).

- [ ] **Step 3: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1-3's commit range and the outcome of this manual verification (pass, or any issues found and how they were resolved).
