# PNG detailed properties extension

Continue the approved parsing audit with W3C PNG3-defined chunks missing from Detailed Properties: acTL/fcTL/fdAT, cICP/mDCV/cLLI, tRNS/bKGD/sBIT/hIST/sPLT. Keep numeric raw fields and exact source ranges; expose HDR units accurately (mDCV xy /50000, luminance and cLLI /10000). cLLI zero means unknown, not zero luminance. APNG delay denominator0 means100; compressed frame bytes remain uninterpreted. This adds metadata inspection, not playback or conformance rules.

Use a valid preceding IHDR for contextual color layouts and PLTE for palette-count checks; ambiguous/missing context is uninterpreted. Explicit short/overlong fixed payloads are unparsable; CRC bytes are outside the metadata payload. Reserved operation values retain raw numbers and uninterpreted status. Up to256 PLTE/tRNS/hIST entries and4096 suggested-palette entries; remaining bytes visibly uninterpreted. Show all valid PLTE entries rather than the old eight-color preview. IEND has a parsed zero-length meaning. No new runtime dependencies.

Actual Pillow11.3 PNG/APNG fixtures plus deliberately signalled HDR metadata using Pillow's chunk writer are generated and independently dumped before parser implementation. Evidence/provenance in docs/validation/png-details-fixtures. Text/ICC decompression and source-status audit is a separate subsequent unit.
