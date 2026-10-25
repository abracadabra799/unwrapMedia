# PNG text/ICC implementation plan

**Goal:** Complete bounded PNG text decoding and correct compressed ICC source ranges.
**Architecture:** New PngTextChunkDecoder shares bounded header/value parsing and typed inflate result; PngWalker passes a per-parse retention budget and removes old duplicate text/inflate functions. Existing field/UI contracts retained.
**Spec:** ../specs/2026-10-25-png-text-details-design.md
**Execution:** Inline TDD and final independent review.

- [x] Actual Pillow/lcms fixtures and independent dump before code: metadata.png iCCP33 payload41, compressed54..415 len361, decoded588; text434 len18; zTXt477 len35 ->27bytes; plain iTXt542 len16; compressed iTXt593 len25 ->16bytes. Retain default.png and reference.icc. ICC profile timestamps may change on regeneration; tests use checked-in bytes.
- [x] Add failing tests for actual text/ICC values and source ranges; missing zlib trailer, checksum corruption, required dictionary, valid empty streams, reserved method/flag vs ignored uncompressed method; invalid UTF8/ASCII/NUL, keyword length/spacing; encoded/decoded and aggregate budget caps; partial header recovery.
- [x] Observe red. Implement PngTextChunkDecoder.kt and PngInflate.kt. One byte span per raw field; decompressed values use the compressed input span. Failures/limits never expose partial decoded text as parsed.
- [x] Focused PNG tests pass, then independent review with source/cap/state focus. Add regression tests for important findings.
- [x] Full :app:test; integrate latest main if needed; commit/fast-forward local main and update handoff/progress. No remote push in this turn.

Validation: all11 new tests observed red before implementation; focused PNG/integrity tests passed; final full :app:test {'tests': 2865, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent review confirmed the128–131-byte ICC tag-count boundary correction and found no remaining important issues. Valid stream trailing bytes retain decoded text plus an uninterpreted raw remainder.
