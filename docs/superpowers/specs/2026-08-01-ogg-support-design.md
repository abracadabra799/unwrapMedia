# OGG/Opus Playback and Structural Parsing Support Design

## Goal

Add OGG (Vorbis) and Opus to the set of supported audio formats: playable via the existing `FfmpegAudioPlayer` (ffmpeg already decodes both natively -- no new playback code needed, only extension recognition), and structurally parseable in the tree/summary/warnings views like every other supported audio container (WAV, MP3, FLAC). Second of the three planned audio-format additions (FLAC done, OGG/Opus now, then AIFF), each with its own spec/plan per the user's explicit choice.

## Background

`AUDIO_EXTENSIONS` (`AppState.kt`) currently lists `m4a`/`mp3`/`wav`/`flac`. `.opus` files are themselves Ogg containers (same `"OggS"` magic and page format as `.ogg`), just holding an Opus stream instead of Vorbis -- both extensions route through the same parser.

OGG's container shape is fundamentally different from every format this app already parses, including FLAC: FLAC splits cleanly into "metadata blocks, then one block of raw frame bytes," but OGG has no such split -- the *entire* file, header packets and audio data alike, is uniformly framed into fixed-shape "pages" (capture pattern `"OggS"` + fixed header fields + a segment table + payload). A new walker is required, following the same pattern established for WebM (`EbmlWalker.kt`) and FLAC (`FlacWalker.kt`): reuse `BoxNode`/`BoxField` unchanged so the tree view, `collectWarnings`, and CLI `dump`/`check` work automatically.

## Design

### A. New walker: `OggWalker.kt`

`fun parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode>`.

Each Ogg page has a 27-byte fixed header (`"OggS"` capture pattern, version, header_type flags byte [continued/bos/eos bits], 8-byte granule_position, 4-byte serial_number, 4-byte page_sequence_number, 4-byte checksum, 1-byte segment_count), followed by a `segment_count`-byte segment table (each byte is one segment's length; the sum is the page's payload size), followed by the payload itself.

The walker scans pages sequentially. Content-sniffing the first bytes of each page's payload (not relying on the bos/eos flags, which are simpler and more robust to check directly) determines how it's represented:

- Payload starts with `0x01` + `"vorbis"` -> **`OggVorbisIdentificationHeader`** node: decodes `version`(u32 LE)/`channels`(u8)/`sample_rate`(u32 LE)/`bitrate_maximum`/`bitrate_nominal`/`bitrate_minimum`(s32 LE each)/`blocksize_0`/`blocksize_1` (low/high nibble of one byte, each treated as a power-of-2 exponent and expanded to its actual value, e.g. exponent 8 -> 256).
- Payload starts with `0x03` + `"vorbis"` -> **`OggVorbisComment`** node: `vendor_length`(u32 LE)+`vendor_string`+`comment_count`(u32 LE)+per-comment `length`(u32 LE)+`"KEY=VALUE"` -- structurally identical to FLAC's VORBIS_COMMENT decode (same little-endian quirk, same split-on-first-`=` field naming), reimplemented locally in `OggWalker.kt` rather than shared with `FlacWalker.kt` (two call sites don't justify cross-file extraction, matching this project's established practice).
- Payload starts with `0x05` + `"vorbis"` -> **`OggVorbisSetupHeader`** node: not decoded (codebook data, complex and not useful to expose field-by-field) -- byte-count summary only, same convention as JPEG's SOS scan data.
- Payload starts with `"OpusHead"` (8-byte magic) -> **`OggOpusIdentificationHeader`** node: decodes `version`(u8)/`channel_count`(u8)/`pre_skip`(u16 LE)/`input_sample_rate`(u32 LE)/`output_gain`(s16 LE)/`channel_mapping_family`(u8).
- Payload starts with `"OpusTags"` (8-byte magic) -> **`OggOpusTags`** node: identical vendor+comment structure to `OggVorbisComment`.
- Any other page (the overwhelming majority for a real audio file -- typically hundreds to thousands of raw audio-data pages) is **not** turned into its own tree node. Instead its byte count is accumulated into a running total, flushed as a single **`OggPages`** summary node (`"N page(s), M byte(s)"`) whenever a recognized header-type page is encountered next, and again after the scan loop ends. The summary node also carries a `final_granule_position` field: the `granule_position` of the last page seen with the eos (end-of-stream) flag set, used by `MediaSummaryBuilder` to compute duration.

Every node type this walker emits is prefixed with `"Ogg"`, so category detection can check `root.children.any { it.type.startsWith("Ogg") }` without having to enumerate every specific type name (avoids the fragility of a per-name allowlist that's easy to forget a case in).

Page-header bounds checking (truncated header, segment table extending past EOF, declared payload extending past EOF) follows the same warn-and-clamp convention already used by `EbmlWalker.kt`/`FlacWalker.kt`. If a page's capture pattern doesn't read `"OggS"` where the previous page's declared size says the next one should start, parsing stops there (any pending `OggPages` accumulation is flushed first) and the remaining bytes become a single `"?"` warning node -- the same fallback convention as `FlacWalker.kt`'s too-short-header case.

**Duration**: Vorbis's granule position is a sample count at the stream's own `sample_rate` (from its identification header), so `durationSeconds = final_granule_position / sample_rate`. Opus is different -- its granule position is *always* counted in 48kHz units regardless of the original source's sample rate (a real Opus-in-Ogg spec quirk, RFC 7845), so `durationSeconds = (final_granule_position - pre_skip) / 48000`. Getting this distinction wrong would silently produce a wrong duration for every Opus file, so `MediaSummaryBuilder`'s duration logic must branch on which identification header node is present.

### B. Wiring

- `ParseFile.kt`: add `isOggMagic(reader)` (checks `reader.readFourCC(0) == "OggS"`) to the magic-byte dispatch chain, routing to `parseOggPages(reader, 0, reader.length)`.
- `AppState.kt`: add `"ogg"` and `"opus"` to `AUDIO_EXTENSIONS` (both route through the same magic-byte detection and parser -- a `.opus` file is just an Ogg container carrying an Opus stream instead of Vorbis).
- `MediaSummaryBuilder.kt`: `detectCategory` gets an `Ogg`-prefix check (see above). `buildStandaloneAudioSummary` gets a fourth branch, calling a new `buildOggSummary(root, fileSizeBytes)`: finds whichever identification header node is present (`OggVorbisIdentificationHeader` or `OggOpusIdentificationHeader`) to determine `Format` ("Vorbis" or "Opus"), `Channel(s)`, and `Sampling Rate` (from the Vorbis header's own field, or the fixed `48000 Hz` for Opus, since Opus always outputs at 48kHz regardless of its informational `input_sample_rate` field); `Bit Rate` is shown only for Vorbis (from `bitrate_nominal`, when present and positive -- Opus's header carries no bitrate field, since Opus is commonly VBR without a declared nominal rate). `Duration`/`Overall Bit Rate` in General use the granule-position formula above, reading `final_granule_position` off the `OggPages` summary node.

## Non-Goals

- Reassembling packets that span multiple pages (the "packet continued on next page" case) -- only a single page's fragment of such a packet is ever available to decode, which may produce a truncation warning rather than full data. Accepted limitation of page-level (not packet-level) parsing.
- Decoding the Vorbis Setup Header's codebook data -- represented as one opaque byte-count node.
- Opus's channel mapping table (`channel_mapping_family != 0`, used for >2-channel Opus streams) -- only the `channel_mapping_family` field itself is shown.
- Per-stream grouping/demuxing for multiplexed or chained Ogg files (multiple logical bitstreams distinguished by `serial_number`, e.g. Ogg Theora video, or concatenated tracks) -- pages are parsed in raw physical file order with no grouping by serial number. Rare for a standalone audio file, and this app's tree view already shows exactly what's physically in the file rather than a logically reconstructed view.
- Per-page tree nodes for the bulk of a file's audio-data pages -- grouped into one `OggPages` summary node instead (avoids a tree with hundreds to thousands of near-identical nodes for a single track).
- AIFF support -- separate spec/plan, not part of this one.

## Testing

- `OggWalker.kt`: unit tests with hand-built byte arrays (matching `FlacWalkerTest.kt`'s/`EbmlWalkerTest.kt`'s style) covering: a Vorbis identification header page decoded correctly, a Vorbis comment page's little-endian vendor+comment parsing, a Vorbis setup header page falling back to a byte-count-only summary, an Opus identification header page decoded correctly, an Opus tags page, a run of generic (undecoded) pages correctly accumulating into a single `OggPages` summary node with the right count/byte total, the `final_granule_position` field capturing the eos-flagged page's granule position, a truncated/too-short page header producing a warning, and a capture-pattern mismatch (desync) stopping the scan and flushing any pending accumulation first.
- `ParseFile.kt`/`AppState.kt` wiring: real-file tests using actual ffmpeg-generated `.ogg` (Vorbis) and `.opus` (Opus) fixtures, confirming `parseFile` routes both to `parseOggPages` and both extensions open as `MediaType.AUDIO`.
- `buildOggSummary`: unit tests with hand-built `BoxNode` trees (matching `MediaSummaryBuilderTest.kt`'s/`FlacMediaSummaryBuilderTest.kt`'s style) for both the Vorbis and Opus cases, asserting Duration is computed with the correct formula for each (confirming the 48kHz-fixed Opus case specifically, since that's the easiest place to get this feature subtly wrong), plus Sampling Rate/Channel(s)/Bit Rate field values.
- Manual verification: open real `.ogg` and `.opus` files, confirm the tree (identification header, comment tags, single `OggPages` summary, no per-page node explosion), the Detail Properties summary (correct format/duration/sampling rate), and playback (via `FfmpegAudioPlayer`, including its waveform/spectrogram) all work end-to-end for both.
