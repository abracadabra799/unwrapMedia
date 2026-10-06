# Explorer Thumbnail Priority and Progressive Preview Design

## Goal

Make the media comparison explorer's first viewport feel ready sooner when opening a folder with many large JPEGs. The current loader already decodes to a 240-pixel edge, uses three background workers, keeps a 200-item memory LRU, and now has a bounded disk cache. This change focuses on first-open responsiveness rather than changing final thumbnail quality or increasing decoder concurrency.

## User-visible behavior

1. Thumbnails for items currently visible in the grid are decoded before queued work for off-screen items.
2. When a JPEG contains a valid EXIF IFD1 thumbnail, show it as a temporary preview as soon as it is decoded.
3. Replace that preview with the existing 240-pixel scaled decode when ready. Only the final decode is written to the disk thumbnail cache.
4. If there is no embedded preview, or it is malformed/unsupported, use the existing decoder and fallback path without failing the item.
5. Keep in-progress decodes bounded to the existing three workers. A viewport change reprioritizes queued work; it does not interrupt a decode already running.

## Design

### Request scheduling

Replace direct FIFO submission in `ExplorerThumbnailLoader` with a small testable priority scheduler. Requests have a stable file identity, FIFO order within the same priority, and a mutable rank derived from distance to the visible grid range. Newly visible work is promoted; queued work that leaves the viewport is demoted behind visible work. Duplicate requests share one decode and all interested UI callbacks receive both preview and final updates. Running jobs are not preempted, and the existing three-worker limit remains.

The grid supplies visible row/column positions from its `LazyGridState`. Only the immediately following grid row is queued as prefetch; visible items always outrank that row. Queued work outside the visible range and this one-row look-ahead is demoted to background priority. List view retains FIFO order and does not need a separate viewport policy.

### Progressive JPEG decode

Add a focused `ImageAnalyzer` entry point for JPEG embedded thumbnails. It parses JPEG/EXIF structure using existing `ByteReader`, `parseJpegSegments`, and EXIF IFD1 thumbnail support; it does not call full image analysis, calculate a histogram, or decode the primary image. The extracted bytes are decoded and scaled only if the embedded preview exceeds the explorer's target size.

The loader checks memory and disk caches first. On a miss, it emits a valid embedded thumbnail as a non-final update if available, then runs the existing scaled decode (or existing fallback) and emits the final bitmap. If the preview extraction fails, it proceeds directly to the final decode. The preview is not persisted as the canonical disk entry; only the final result is cached. Non-JPEG media follows the current path.

### Error handling and lifecycle

Malformed EXIF, invalid thumbnail offsets, unsupported embedded data, cache errors, and encoding errors are treated as cache/preview misses. They must not prevent the normal decoder from running. Deduplication remains in force across preview and final stages. A scroll update changes queued priority only and does not cancel active native decoder work.

## Tests

- Scheduler unit tests verify visible items precede prefetch, stable FIFO ordering among equal ranks, queued promotion/demotion, and request deduplication.
- EXIF extraction tests cover a valid embedded JPEG thumbnail, absent IFD1 thumbnail, and malformed/out-of-bounds offsets.
- Loader tests verify preview then final delivery, final-only persistence, and fallback when preview extraction fails.
- Run the complete Gradle test suite and `git diff --check`.

## Acceptance criteria

- On a cold folder open, queued visible thumbnails are not blocked behind off-screen prefetch requests.
- A supported EXIF thumbnail can appear before the full 240-pixel decode, and is replaced by the final result.
- Existing cache behavior and non-JPEG thumbnail fallback remain unchanged.
- Memory decode concurrency remains capped at three.
- No file names or paths are added to diagnostic logs.
- Automated tests pass. Actual Windows/FastStone timing remains a separate measurement; this design does not claim a specific speedup without that benchmark.

## Out of scope

- Changing folder enumeration/sorting to asynchronous work.
- Increasing the worker count or tuning it per storage device.
- Windows Shell thumbnail API integration.
- Comparing performance against FastStone or promising a numeric speedup.
