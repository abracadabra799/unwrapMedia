# Spec citations on every WARN/FAIL — design

Date: 2026-10-24. Status: approved (user: "모든 규격 검사", "원문 PDF를 받아 대조").

## Goal

Every WARN or FAIL from a spec-conformance check shows **which spec text it comes from**: document + edition,
section (§), page (when the document has pages and we verified it), and a **deep link that opens that place when
clicked** — in the Content Check tables, the CLI JSON and the AI prompt.

## Model

```kotlin
// com.multiviewer.parser.conformance
data class SpecCitation(
    val document: String,      // "AVIF v1.2.0 (AOMedia, 2025-10-16)", "ISO/IEC 23008-12:2025", "ITU-T H.265 (V10, 2024-07)"
    val section: String,       // "§7 General constraints", "§8.11.12 Item properties" (clause hint for paid docs)
    val page: Int?,            // printed/PDF page holding the clause; null when the document has no pages or was not verified
    val url: String,           // deep link: HTML "#anchor", PDF "#page=N", paid ISO = store page
    val verified: Boolean,     // true only when the clause text (and page) was checked against the original
)
```
- `ConformanceRule` gains `val citation: SpecCitation? = null` (default keeps existing callers compiling).
- Google Motion Photo `GoogleSpecFinding` gains `val citation: SpecCitation? = null`.
- `ContentFinding` gains `val citation: SpecCitation? = null`, filled by the mappers.
- App-only structural observations (`source` starting with "unwrapMedia structural check") keep `citation = null`
  and the UI labels them "unwrapMedia 자체 관찰 / unwrapMedia structural observation (규격 아님)".

## Sources and link rules

| Document | Link form | Page | verified |
|---|---|---|---|
| AVIF v1.2.0 | `https://aomediacodec.github.io/av1-avif/v1.2.0.html#<id>` (ids verified, e.g. `#file-constraints`, `#av1-item-configuration-property`, `#avif-required-boxes`) | null (HTML) | true |
| Google Motion Photo 1.0 | `https://developer.android.com/media/platform/motion-photo-format#<id>` (`#file-name`, `#camera-metadata`, `#container-element`, `#item_element`, `#itemmime_type_values`, `#itemsemantic_values`, `#isobmff-image-specific-behavior`, `#video-container`, `#video-metadata`) | null | true |
| Apple QuickTime File Format | per-atom documentation page (verify each URL resolves, HTTP 200) | null | false until text checked |
| ITU-T H.264 / H.265 / H.266, AV1, VP9, RFCs, CIPA, W3C | the free PDF/HTML; PDFs link `…pdf#page=N` after downloading the PDF and confirming the clause on that page | verified page | true when checked |
| Paid ISO/IEC (14496-12, 14496-14, 14496-15, 23008-12, 23000-22, 14496-3) | ISO store page of the current edition (verify HTTP 200) | null, shown as "원문 미대조" | false |

Never invent a page number or anchor: an unverified anchor/page is omitted (link to the document root) and
`verified = false`.

## Display

- Content Check finding detail (ContentFindingTable and the motion-photo CheckSection rows): a line
  "출처 / Source: {document} {section}{, p. N}" rendered as a clickable link (pointer cursor, underline); click →
  `java.awt.Desktop.browse(URI)` on a background thread (Windows: never block the UI thread); unsupported desktop →
  copy the URL to the clipboard and show a status message. `verified = false` adds "(원문 미대조 / not checked against
  the original)".
- CLI JSON: findings gain `"citation": {document, section, page, url, verified}` (omitted when null).
- AI prompt: non-PASS rows append "출처: {document} {section} {url}".

## Scope and order

1. HEIF/AVIF (current branch): the citation model + UI/JSON/prompt plumbing + citations for every HEIF/AVIF rule.
2. Retrofit (next branch): Google Motion Photo, ISO-BMFF (ISO store page + QTFF per-atom pages), codec bitstream
   catalogs (download the ITU-T/AOM/IETF documents, verify pages).
3. Every later roadmap format ships with citations from the start.
