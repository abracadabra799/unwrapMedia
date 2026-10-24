# Item information details
Continuation of approved byte-parser coverage. Keep existing infe fields and summaries/order; add version, flags, hidden_item (flags bit 0), MIME content_encoding when present, URI item_uri_type. Accept v2/v3 only, reject unknown versions instead of guessing v3 layout. Bound strings to 65536 bytes and box/file boundaries; warn on unterminated or limited strings without treating suffix as another field.

Evidence: independent item-bytes.txt from S26 HEIC and signed.heif. S26 first infe offset3014045 has v2 flags1, item_ID1, hvc1, empty name. MIME at3015242 has item_ID58 and application/rdf+xml with no content_encoding bytes. Optional absent encoding must not fabricate a source field. Layout cross-check: https://raw.githubusercontent.com/strukturag/libheif/master/libheif/box.cc Box_infe::parse (accessed 2026-10-24). Legacy v0/v1 lack a verified device/generated fixture and remain explicitly unsupported.

- [x] Independently dump real fixtures before parser changes.
- [x] Red tests for actual hidden fields, MIME encoding and URI offsets, v3 uint32 IDs, unknown version, bounds/termination.
- [x] Update InfeBoxDecoder only, maintaining existing field order and consumers.
- [ ] Focused/full regression and review; commit and local main merge, no push.

Validation: initial tests failed on omitted properties and unknown-version acceptance; focused and full tests passed. Independent read-only review found no issues.
