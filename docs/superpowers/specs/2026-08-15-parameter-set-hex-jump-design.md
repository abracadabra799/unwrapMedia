# SPS/PPS/VPS → Hex Viewer Jump — Design Spec

**Goal:** In the Detail Properties panel's "H.264 Parameter Sets" / "HEVC Parameter Sets" sections (added by the two merged per-frame-parameter-set features), let clicking a SPS/PPS/VPS id row scroll and highlight the hex viewer to that specific parameter set's actual raw NAL bytes inside the file's `avcC`/`hvcC` box — a different file region than the currently-selected frame's own sample bytes.

**Context:** The hex viewer's scroll/highlight is driven by a single fallback chain, duplicated in two places in `Main.kt` (the highlight-range computation and a `LaunchedEffect` that scrolls to match), reading from several `TabState` fields in priority order: `tileHighlightRange` (HEIC/HEIF tile selection) → `activeField` (selected `BoxField`) → `selected` (selected `BoxNode`) → `selectedFrame?.byteOffset` (GOP/filmstrip frame selection). Nothing about this chain is codec-specific; a new highlight source is added the same way `tileHighlightRange` was: a new `TabState` field, consumed by both existing chains.

Today, `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets` extract each SPS/PPS/VPS's raw bytes from the `avcC`/`hvcC` box but discard the file offset each NAL was read from (a `pos` cursor already walks past it during extraction). `PropertyRow` (`ui/Components.kt`) is a pure read-only label/value row — no click support today. The two Parameter Sets sections currently show combined rows ("SPS ID / PPS ID", "VPS ID / SPS ID / PPS ID") that would need per-id decomposition before any single row could unambiguously map to one click target.

## Scope

- Split the existing combined id rows into one row per parameter set: H.264's "SPS ID / PPS ID" becomes two rows ("SPS ID", "PPS ID"); HEVC's "VPS ID / SPS ID / PPS ID" becomes three ("VPS ID", "SPS ID", "PPS ID"). Every other existing row in both sections is unchanged.
- Each id row is clickable when its byte location is known; clicking sets a new highlight that takes top priority in the hex viewer's existing fallback chain. HEVC's VPS row is not clickable when VPS resolution failed (`vps == null`, per the existing best-effort VPS lookup) — same "-" placeholder as today, just non-interactive.
- Clicking a *different* frame (GOP bar, filmstrip, arrow-key stepping — any path that changes `TabState.selectedFrame`) clears this highlight automatically, so the hex viewer reverts to that new frame's own byte position, matching the existing "selecting a frame highlights its bytes" behavior. No other user action is required to "get back" to normal frame-highlight behavior.
- `PropertyRow` gains a small, generically-reusable `onClick` capability (not parameter-set-specific) — the natural, minimal way to make specific rows clickable without duplicating the row layout at each call site the way `ImageInspectorUI.kt`'s field-list and warnings-list rows currently do (they each wrap their own `Box.clickable{}` around bespoke content, not `PropertyRow`).
- Out of scope: making the hex viewer's OWN click/selection jump back to a "which parameter set is this" label (the reverse direction); a tree-node/`BoxField` representation of individual SPS/PPS/VPS entries inside `avcC`/`hvcC` (today only aggregate counts are exposed there, per `docs/superpowers/specs/2026-07-17-box-detail-parsing-design.md`'s original deferral) — this feature reads the same raw bytes the parameter-set extraction already walks past, without adding new tree nodes.

## Current state (verified against the real code)

**Hex highlight chain** (`Main.kt`, computed twice — once for the actual highlight, once for scroll-to-item — with a pre-existing, unrelated ordering difference between the two that this feature preserves rather than fixes):

```kotlin
// highlight computation
val hexHighlightRange = currentTab.tileHighlightRange
    ?: activeField?.let { it.offset until (it.offset + it.length) }
    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
    ?: currentTab.selectedFrame?.let { frame ->
        frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
    }
```
```kotlin
// scroll-to-item (LaunchedEffect keyed on selected/selectedField/tileHighlightRange/selectedFrame)
when {
    tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
    field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
    frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
    else -> currentTab.selected?.let { hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt()) }
}
```

**`PropertyRow`** (`ui/Components.kt`) — label/value only, no click support:
```kotlin
@Composable
fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = AppTypography.bodyLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.5f)))
    }
}
```

**Raw NAL extraction discards offsets** — `extractAvcCRawParameterSets` (`H264ParameterSetExtraction.kt`):
```kotlin
spsList.add(reader.readBytes(pos + 2, spsLength))   // bytes start at pos+2; that position is never kept
pos += 2 + spsLength
```
and `extractHvcCRawParameterSets` (`HevcParameterSetExtraction.kt`):
```kotlin
pos += 2
if (pos + nalLength > payloadEnd) break
val nalBytes = reader.readBytes(pos, nalLength)   // bytes start at pos; likewise discarded
```
Both `AvcCRawParameterSets`/`HvcCRawParameterSets` hold plain `List<ByteArray>` today. Neither `AvcCBoxDecoder`/`HvcCBoxDecoder` (box-tree display, counts only) nor the parsed `H264Sps`/`H264Pps`/`HevcVps`/`HevcSps`/`HevcPps` data classes carry per-entry byte offsets anywhere.

## Components

### 1. `RawNal.kt` (new, shared) — pairs raw NAL bytes with their file offset

```kotlin
package com.multiviewer.parser

// A raw NAL's bytes plus the absolute file offset where those bytes begin (immediately after the
// NAL's length-prefix, inside its containing avcC/hvcC box) -- lets callers map a parsed
// SPS/PPS/VPS back to its exact on-disk location, e.g. for hex-viewer navigation.
data class RawNal(val bytes: ByteArray, val offset: Long)
```

### 2. `H264ParameterSetExtraction.kt` / `HevcParameterSetExtraction.kt` (modified)

`AvcCRawParameterSets`/`HvcCRawParameterSets`'s list fields change from `List<ByteArray>` to `List<RawNal>`; `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets` wrap each `readBytes(...)` result with the offset already available at that point in their existing `pos` walk (`pos + 2` for avcC's SPS/PPS, `pos` for hvcC's VPS/SPS/PPS, per the Current State section above — no new offset-tracking logic needed, just retaining a value already computed). `resolveActivePicParameterSetId`/`resolveActiveHevcPicParameterSetId`/`resolveActiveParameterSets`/`resolveActiveHevcParameterSets` are unaffected (they never consumed the raw byte lists).

### 3. `TabState` additions (`AppState.kt`)

```kotlin
var avcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
var avcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
var hevcVpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
var hevcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
var hevcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
// Set when a Parameter Sets row is clicked (see ImageInspectorUI.kt); cleared automatically
// whenever a different frame is selected (see DetailPropertiesTabContent's new LaunchedEffect),
// so the hex viewer naturally reverts to highlighting the newly-selected frame's own bytes.
var parameterSetHighlightRange: LongRange? by mutableStateOf(null)
```
Maps are keyed by each parameter set's own id (`seqParameterSetId`/`picParameterSetId`/`vpsId`/`spsId`/`ppsId`) — the same id already used to look them up in `resolveActiveParameterSets`/`resolveActiveHevcParameterSets`, so the UI can go from "which SPS is showing" straight to "where are its bytes" with a single map lookup.

### 4. `VideoInspectorUI.kt` (modified) — populate the offset maps alongside the existing parsed lists

Both existing `LaunchedEffect(tab.root)` blocks (avcC and hvcC) compute parsed-object/`RawNal` pairs once, then derive both the existing `avcSpsList`/`avcPpsList` (etc.) AND the new offset maps from the same pairs — no double-parsing:
```kotlin
val parsedSps = raw.spsList.mapNotNull { nal -> com.multiviewer.parser.parseH264Sps(nal.bytes)?.let { it to nal } }
val parsedPps = raw.ppsList.mapNotNull { nal -> com.multiviewer.parser.parseH264Pps(nal.bytes)?.let { it to nal } }
tab.avcSpsList = parsedSps.map { it.first }
tab.avcPpsList = parsedPps.map { it.first }
tab.avcSpsOffsets = parsedSps.associate { (sps, nal) -> sps.seqParameterSetId to (nal.offset until nal.offset + nal.bytes.size) }
tab.avcPpsOffsets = parsedPps.associate { (pps, nal) -> pps.picParameterSetId to (nal.offset until nal.offset + nal.bytes.size) }
```
(HEVC's `LaunchedEffect` follows the identical pattern for `hevcVpsList`/`hevcSpsList`/`hevcPpsList`/`hevcVpsOffsets`/`hevcSpsOffsets`/`hevcPpsOffsets`.)

### 5. `Components.kt` — `PropertyRow` gains an optional `onClick`

```kotlin
@Composable
fun PropertyRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = AppTypography.bodyLarge,
                color = if (onClick != null) AppColors.NeonBlue else Color.Unspecified,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.5f)))
    }
}
```
`onClick` defaults to `null` (every existing call site across the app is source-compatible, unchanged). When non-null, the value text renders in `AppColors.NeonBlue` (the same accent already used for the section headers) as the click affordance, and the row becomes clickable.

### 6. `ImageInspectorUI.kt` (modified) — split rows, wire clicks, auto-clear on frame change

The existing combined rows are replaced with per-id rows whose `onClick` is only non-null when an offset is known:
```kotlin
// H.264 section
PropertyRow(
    "SPS ID", sps.seqParameterSetId.toString(),
    onClick = tab.avcSpsOffsets[sps.seqParameterSetId]?.let { range -> { tab.parameterSetHighlightRange = range } },
)
PropertyRow(
    "PPS ID", pps.picParameterSetId.toString(),
    onClick = tab.avcPpsOffsets[pps.picParameterSetId]?.let { range -> { tab.parameterSetHighlightRange = range } },
)
```
```kotlin
// HEVC section
PropertyRow(
    "VPS ID", vps?.vpsId?.toString() ?: "-",
    onClick = vps?.let { tab.hevcVpsOffsets[it.vpsId] }?.let { range -> { tab.parameterSetHighlightRange = range } },
)
PropertyRow(
    "SPS ID", sps.spsId.toString(),
    onClick = tab.hevcSpsOffsets[sps.spsId]?.let { range -> { tab.parameterSetHighlightRange = range } },
)
PropertyRow(
    "PPS ID", pps.ppsId.toString(),
    onClick = tab.hevcPpsOffsets[pps.ppsId]?.let { range -> { tab.parameterSetHighlightRange = range } },
)
```
`DetailPropertiesTabContent` gains one new effect, scoped to the whole composable (not either codec section specifically, since either could have set the highlight):
```kotlin
LaunchedEffect(tab.selectedFrame) { tab.parameterSetHighlightRange = null }
```
This re-fires every time `selectedFrame`'s identity changes (a new frame chosen via any path), resetting the highlight before that frame's own `produceState`-resolved parameter sets even finish loading -- consistent with this codebase's established "reset synchronously on the new key, then repopulate" idiom (see `resolvedH264Params`/`resolvedHevcParams`'s own `value = null` first statement).

### 7. `Main.kt` (modified) — `parameterSetHighlightRange` becomes the top-priority highlight/scroll source

```kotlin
val hexHighlightRange = currentTab.parameterSetHighlightRange
    ?: currentTab.tileHighlightRange
    ?: activeField?.let { it.offset until (it.offset + it.length) }
    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
    ?: currentTab.selectedFrame?.let { frame ->
        frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
    }
```
```kotlin
LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame, currentTab.parameterSetHighlightRange) {
    val paramRange = currentTab.parameterSetHighlightRange
    val tileRange = currentTab.tileHighlightRange
    val field = activeField
    val frameOffset = currentTab.selectedFrame?.byteOffset
    when {
        paramRange != null -> hexListState.scrollToItem((paramRange.first / BYTES_PER_ROW).toInt())
        tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
        field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
        frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
        else -> currentTab.selected?.let { hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt()) }
    }
}
```
`parameterSetHighlightRange` is placed first in both chains (ahead of even `tileHighlightRange`) since it's the most specific, most recently-expressed user intent; in practice it never coexists with a HEIC/HEIF tile selection (parameter sets only exist for video tabs), so the exact relative ordering between the two doesn't materially matter, but placing the newest/most-specific source first matches this chain's existing convention.

## Error handling

Same convention as the rest of this feature area: a `null` offset lookup (parameter set not found in the map -- shouldn't happen for a resolved `H264Sps`/`H264Pps`/`HevcSps`/`HevcPps` that came from the same parse pass, but always possible for a best-effort `HevcVps`) simply yields `onClick = null`, i.e. a non-interactive row, rather than a broken click target. `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets` keep their existing try/catch-return-null contract; wrapping bytes in `RawNal` adds no new failure mode.

## Testing

- `H264ParameterSetExtractionTest`/`HevcParameterSetExtractionTest`: extend the existing synthetic-fixture tests (structure only, matching this codebase's established `fileOf`-based box-decoder test convention) to also assert each returned `RawNal.offset` matches the exact byte position of that entry's NAL bytes within the synthetic payload -- straightforward since the fixture bytes' layout is already hand-constructed and known.
- `PropertyRow`'s new `onClick` parameter and the `ImageInspectorUI.kt`/`Main.kt` wiring are UI-only changes with no new automated tests, matching this codebase's established convention for UI-integration work (verified via manual app testing instead, same as every prior UI-integration task this session).
- Manual verification: open a real H.264 or HEVC file, select a frame, click each of "SPS ID"/"PPS ID" (and "VPS ID" for HEVC, when resolved) and confirm the hex viewer scrolls to and highlights that parameter set's actual bytes (distinct from, and typically far from, the currently-selected frame's own highlighted mdat bytes) -- then select a different frame and confirm the highlight reverts to that frame's own bytes.

## Out of scope (deferred)

- The reverse direction (hex viewer selection → "this is SPS #0" label).
- New `BoxNode`/`BoxField` tree entries for individual SPS/PPS/VPS (would duplicate what the Detail Properties panel already shows, and was an explicit original scope decision in the box-detail-parsing feature).
- Any change to which fields the H.264/HEVC Parameter Sets sections display -- this feature only changes row layout (combined → split) and adds click behavior, not content.
