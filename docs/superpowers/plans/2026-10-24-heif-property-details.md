# HEIF property details implementation plan

Goal: expose omitted pixi and auxC fields through existing BoxField rendering.
Architecture: shared bounded FullBox property decoder, existing decoder objects delegate; no new dependencies.

- [x] Independently dump existing S26 fixture before code; retain output in heif-bytes.txt.
- [x] Add failing tests: real file registry fields/source offsets; channel count retained on truncated channel array; aux subtype raw hex and correct range; unterminated auxiliary type; unsupported version; oversized declared box.
- [x] Implement HeifPropertyDetailsDecoder, delegate PixiBoxDecoder/AuxCBoxDecoder. Preserve bits_per_channel/aux_type names and summaries. FullBox v0/flags0 only. aux subtype preview max4096 bytes with original length and explicit omitted byte count. Scan type string within bounds and limit display, preserving raw range.
- [ ] Focused test, full regression, review, commit and local main merge. No push.

Ruling: retain valid FullBox/count fields on truncated pixi; previous test expecting no fields was updated to assert that no incomplete bits_per_channel array is presented. This intentionally improves forensic visibility without fabricating channel values. Auxiliary type scanning is capped at 4096 bytes; an over-limit type is labeled aux_type_preview, not a complete aux_type.

Focused tests and full regression passed; independent review found no blocking issues. Added oversized auxiliary type/subtype test covering the suggested preview limits.
