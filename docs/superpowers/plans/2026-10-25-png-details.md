# PNG details implementation plan

**Goal:** Decode missing W3C PNG3 metadata fields using bounded source-aware decoders.
**Architecture:** PngWalker retains walking; PngExtraChunkDecoder receives validated body range and preceding color/palette context. Existing field rendering and hex linkage are reused.
**Spec:** ../specs/2026-10-25-png-details-design.md
**Execution:** Inline TDD, then independent whole-branch review and full suite.

## Task1: Animation and HDR
- [x] Real Pillow APNG controls and PNG fixtures/dump before code: acTL33 payload frames2/plays3; fcTL53 seq0/10x8/delay100:1000/dispose0/blend0; fcTL126 seq1/delay250:1000/dispose1/blend1; fdAT164 seq2. HDR cICP33 =09100001; mDCV49 R(34000,16000),G(13250,34500),B(7500,3000),W(15635,16450),max10000000,min50; cLLI85=(10000000,4000000).
- [x] Failing tests: exact values/spans/units; denominator0 and unknown luminance0; fixed-width truncation/overlength cannot read CRC; reserved enums and compressed payload states.
- [x] Implement decoder and dispatch; focused tests green.

## Task2: Color-context metadata
- [x] Failing tests: indexed transparency0/64/128/255, gray7/RGB1,2,3; indexed background2/gray17/RGB10,20,30; sBIT5,6,5; hIST10,20,30,40; sPLT Wide16bit two entries, frequency100/50; full PLTE entries.
- [x] Implement context passed from valid IHDR/PLTE, exact contextual lengths, missing-context uninterpreted, invalid palette counts, sPLT name/depth/entry bounds and4096 cap.
- [x] Focused PNG/integrity tests green.

## Task3: Review/integration
- [x] Final independent review, regression tests for important findings, full :app:test. Record counts/provenance and update broad plan. Fast-forward local main once integrated with any concurrent changes. No automatic second remote push.

Review correction: indexed tRNS/bKGD numeric values are still readable without PLTE, but palette association is unknown. Missing/malformed PLTE now retains values with UNINTERPRETED node status and a bilingual warning. Four regression cases failed before the fix; independent reviewer confirmed the correction.

Final validation: full :app:test {'tests': 2819, 'failures': 0, 'errors': 0, 'skipped': 1}. All newly added behavior tests were observed red before implementation; independent whole-branch review has no remaining important findings.
