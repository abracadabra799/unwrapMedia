# PNG text and ICC interpretation

Continue the approved Detailed Properties audit. Decode tEXt/zTXt/iTXt/iCCP through one bounded implementation, preserving existing value names and valid summaries. Show compression flag/method and exact source spans. PNG keyword/profile names follow the 1–79-byte printable Latin-1/spacing rules. UTF-8 and ASCII decode strictly; malformed strings must never be replaced and presented as parsed values. Empty text and valid compressed empty text remain supported. For uncompressed iTXt, ignore the method byte as required by PNG3 while displaying its raw value.

Require complete zlib end-of-stream/checksum and progress; partial, dictionary-requiring and corrupt streams are UNPARSABLE. Unsupported flags/methods and inspection limits are UNINTERPRETED with bounded raw preview. Preserve known prefix fields on failures. Never allocate from an unchecked UInt32 chunk length. Limits: encoded metadata chunk1MiB; decoded text1MiB; decoded ICC4MiB; aggregate decoded metadata8MiB per PNG parse. Limits are display restrictions, not malformed-format findings.

For iCCP, reuse the existing ICC header decoder only after complete bounded decompression. Map each derived header field to the entire actual compressed profile range, with a bilingual source-basis field, because decompressed offsets are not positions in the PNG. Preserve parsed header values even when an ICC size/signature inconsistency or missing mandatory 4-byte tag count is detected, but mark the node UNPARSABLE. Remaining ICC tag data is explicitly uninterpreted; this unit does not add an ICC tag-content engine.

Evidence: real Pillow11.3/lcms-generated text/profile PNG with independent zlib/CRC byte dump before code. No runtime dependency additions or remote push.
