# Google Motion Photo (XMP) Support — Design

## Background

`MotionPhotoExtractor.findEmbeddedVideo` currently locates an embedded Motion Photo video two ways: a top-level `mpvd` box (HEIC) or a `sefd` trailer's `MotionPhoto_Data` field (Samsung JPEG). Neither covers plain Google/Pixel Motion Photo JPEGs, which have no Samsung SEFD trailer at all — they record the embedded video's location purely in XMP metadata (an APP1 segment's `<x:xmpmeta>` block).

Google documents two XMP schemes for this, confirmed via their official developer documentation and corroborating real-world write-ups:

- **Current spec (v1)**: a `Container:Directory` — an ordered list of items (`Item:Mime`, `Item:Semantic`, `Item:Length`, `Item:Padding`). The item with `Item:Semantic="MotionPhoto"` describes the embedded video; per Google's own spec text, "readers can start from the end of the file and move back `Length` bytes" to find its start.
- **Legacy (2016-2021 Pixel/Google Camera "MVIMG" files)**: `GCamera:MicroVideoOffset` — explicitly deprecated in the current spec, but real-world-common. Confirmed via independent write-ups: "the offset is actually from the end of the file" — identical end-of-file-relative math to the current spec, just a single flat value instead of a directory.

Real encoders serialize RDF properties as either XML elements (`<Item:Semantic>MotionPhoto</Item:Semantic>`, per Google's own worked example) or compact XML attributes (`Item:Semantic="MotionPhoto"`, the common Adobe-XMP-toolkit convention) — this is genuinely encoder-dependent, not something a fixed assumption can safely pick one of.

## Goal

Extend `findEmbeddedVideo` to also recognize both Google XMP schemes, so `MotionPhoto > 동영상 추출` works on Google/Pixel Motion Photo JPEGs in addition to the Samsung/HEIC formats already supported — without changing behavior for either existing format.

## Non-Goals

- No support for the `GainMap` (Ultra HDR) item semantic — only `MotionPhoto` items are extracted.
- No XMP writing/editing — read-only, matching the rest of this app.
- No change to HEIC handling — HEIC Motion Photos are already correctly handled via the `mpvd` box, which is unaffected by this change (the same box shape the Google spec itself mandates for HEIC/AVIF).
- No change to `AppState.kt`/`Main.kt` — `EmbeddedVideo`'s shape and `findEmbeddedVideo`'s signature are unchanged, so this is entirely contained within `MotionPhotoExtractor.kt`.

## Design

### 1. Locating the XMP text

The raw XMP XML is already parsed into a plain `String` field named `"xmp"` on some node in the tree — the root-level `APP1` node for JPEG (`JpegWalker.kt`'s `decodeApp1`), or an `iloc` item node for HEIC (`MetaBoxDecoder.kt`'s `enrichIlocItem`, for the item whose type is `mime` with `content_type == "application/rdf+xml"`). A single generic lookup finds it regardless of file type or nesting depth:

```kotlin
findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
    ?.fields?.find { it.name == "xmp" }?.value
```

### 2. Priority order in `findEmbeddedVideo`

```
mpvd (HEIC)
  → sefd MotionPhoto_Data (Samsung JPEG)
    → XMP Container:Directory MotionPhoto item (Google v1)
      → XMP GCamera:MicroVideoOffset (Google legacy)
        → null
```

XMP-based detection only ever gets reached for JPEG files that have neither an `mpvd` box nor a SEFD trailer — i.e., it's purely additive, never overriding an already-working Samsung/HEIC result.

### 3. Parsing the XMP (new, internal to `MotionPhotoExtractor.kt`)

Uses the JDK's built-in `javax.xml.parsers.DocumentBuilder` — no new Gradle dependency. Since this parses untrusted file content, the `DocumentBuilderFactory` is hardened against XXE: DOCTYPE declarations are disabled outright (`setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`), which is the standard, sufficient mitigation for this class of attack.

Elements/attributes are matched by **local name only** (e.g. `"Semantic"`, `"Length"`, `"Mime"`), not by namespace prefix — real files use varying prefixes (`GCamera` vs `Camera`, etc.) for the same namespace URI, and matching on local name sidesteps that entirely. For a given `rdf:li` (Directory item) node, a property's value is looked up by checking, in order: the node's own attributes, then its child elements' text content, then (for the officially-nested form) one level of descendant — covering every real serialization shape found in research without hardcoding one.

```kotlin
private fun findGoogleMotionPhotoVideo(xmpText: String): EmbeddedVideoSpec? {
    // Try Container:Directory first, then legacy GCamera:MicroVideoOffset.
    // Returns (length-from-end-of-file, mimeType) or null.
}
```

Any parse failure (malformed/truncated XMP) is caught and treated as "not found," falling through to legacy MicroVideo, then to `null` — consistent with this codebase's established graceful-degradation pattern for every other optional metadata source.

### 4. Computing the byte range

`root.size` is already the total file length (set in `ParseFile.kt`). For either XMP scheme:

```
videoStart = root.size - length
videoEnd = root.size
```

`Item:Mime` (when present, from the Container:Directory form) sets the extension directly: `"video/quicktime"` → `.mov`, anything else (including absent, e.g. the legacy form) → `.mp4`. This mirrors the existing `major_brand`-based extension logic used by the Samsung/HEIC paths, just sourced from XMP instead of a sniffed `ftyp` box — no byte-sniffing is needed since the format is already stated explicitly.

`extractEmbeddedVideo` needs no changes — the video is still a contiguous byte range in the same source file, exactly like the existing two formats.

## Testing

- Synthetic JPEG fixtures (byte arrays, matching the existing `JpegWalkerTest`/`MotionPhotoExtractorTest` style) with a trailing video blob and an APP1 XMP segment, covering:
  - Container:Directory in **element form** (matching Google's official worked example) — asserts correct byte range and `.mp4` extension from `video/mp4`.
  - Container:Directory in **attribute form** (`Item:Semantic="MotionPhoto"` etc. as XML attributes) — asserts the same result, proving the parser isn't tied to one serialization style.
  - Container:Directory with `Item:Mime="video/quicktime"` — asserts `.mov`.
  - Legacy `GCamera:MicroVideoOffset` only (no Container:Directory) — asserts correct byte range and `.mp4`.
  - Malformed/truncated XMP — asserts graceful `null`, not a thrown exception.
  - A file with a `sefd` `MotionPhoto_Data` field *and* Google XMP present — asserts the Samsung path still wins (priority order holds).
  - A file with no motion-photo signal of any kind — still returns `null` (existing behavior, regression guard).
