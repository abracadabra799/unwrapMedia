# Spec citation sources (codec + PNG catalogs)

Checked 2026-10-24/25 (branch `feat/codec-citations`). A rule is `verified=true` only when its sentence (and, for PDFs,
its physical page) was copied from the original listed here. `#page=N` is the 1-based physical PDF page.

## PDFs (downloaded, sha256 of the file checked)

| Document | Edition | URL used in citations | sha256 |
|---|---|---|---|
| ITU-T H.264 | V16, 06/2026, 854 pp | https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.264-202606-I!!PDF-E&type=items | 028cf2cfefd6083ced98d3acb0b586d9328e1631c13d6f21057ad54c569b1b79 |
| ITU-T H.265 | V11, 01/2026, 734 pp | https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.265-202601-I!!PDF-E&type=items | 78b093480e178a2b99d8cb7499934240f359c50c6d09568f8be651bc1151d4d2 |
| ITU-T H.266 | V4, 01/2026, 544 pp | https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.266-202601-I!!PDF-E&type=items | ec728a475ffbf741f530bc9bca2422092dc7cbc60ebac5f72807c202c8740f25 |
| VP9 Bitstream & Decoding Process Spec | v0.7, 2017-02-22 draft, 174 pp | https://storage.googleapis.com/downloads.webmproject.org/docs/vp9/vp9-bitstream-specification-v0.7-20170222-draft.pdf | 92a5bc772d11572c2f119680998ee202e249d075469515fe43bbb7febb51b963 |

## HTML / text sources (anchors confirmed present in the downloaded page)

| Document | Edition | URL | sha256 of downloaded copy |
|---|---|---|---|
| AV1 Bitstream & Decoding Process Spec | HTML, last modified 2023-05-25 (the published PDF is a stub, so anchors are used) | https://aomediacodec.github.io/av1-spec/ | acf58a3fdb82a85e25aa7fe19b8fe2a5ccc02884d0e852f2fc9b05ba7aa49609 |
| AV1 Codec ISO Media File Format Binding | v1.3.0, 2024-04-03 | https://aomediacodec.github.io/av1-isobmff/ | 20ed4fbbad5f0846de8da7635d67ab5ea6b9b9967dd2e0fbf5f05c26ebdd0f98 |
| VP Codec ISO Media File Format Binding | v1.0, 2017-03-31 | https://www.webmproject.org/vp9/mp4/ | 0742cd448ad9b99ad590447be89c305ff886cbb29c6229a56ca5bd20a700dd5b |
| RFC 9924 (APV) | Feb 2026 | https://www.rfc-editor.org/rfc/rfc9924.html | (not hashed; anchors checked) |
| openapv APV ISOBMFF binding | commit 114280b9202a7eccbf0933f62360f65fabd9c0d8, 2025-07-18 | https://github.com/openapv/openapv/blob/114280b9202a7eccbf0933f62360f65fabd9c0d8/readme/apv_isobmff.md | (markdown, not hashed) |
| RFC 6716 (Opus) | Sept 2012 | https://www.rfc-editor.org/rfc/rfc6716.html | c82a8f3977bf8320ed47c2f3b6376d32ba2b85600054d090c6f3e683f25ee245 |
| Encapsulation of Opus in ISOBMFF | v0.6.8 (incomplete), 2016-04-28; page has no anchors, cited at the page root with the section number in the text | https://opus-codec.org/docs/opus_in_isobmff.html | 8abc41e0ca01d947408902e8bbe9bab4f8a07254ee2d77a171471971109b1cf0 |
| RFC 9639 (FLAC) | Dec 2024 | https://www.rfc-editor.org/rfc/rfc9639.html | d99e71319d66dba55074dc69e70920e5592977145927d42c3e3624c5607c2982 |
| Encapsulation of FLAC in ISOBMFF (`doc/isoflac.txt`) | v0.0.4, xiph/flac commit 78d85dd4e4e8044d0f6989182cd1a22a461dce37 (2022-06-14); line links pinned to that commit | https://github.com/xiph/flac/blob/78d85dd4e4e8044d0f6989182cd1a22a461dce37/doc/isoflac.txt | 7d404928125747a46e048d1d056ed94a332b584eef55a4652fb4fa145c6dfc2e |
| W3C PNG Third Edition | Recommendation 2025-06-24 | https://www.w3.org/TR/png-3/ | a3ac82bb9eb8664b93961a2a9ff6def3d70e3a9f63d92721143c1f391d04edec |

## Paid ISO documents (not checked against the original; store page only, `verified=false`, no page)

| Document | Used for | Store page |
|---|---|---|
| ISO/IEC 14496-15:2024 (Edition 7) | avc/hevc/vvc NAL framing, vvcC, vvc sample framing | https://www.iso.org/standard/89118.html |
| ISO/IEC 14496-3:2019 (Edition 5) | aac.config.asc/match, aac.frame.*, aac.sample.framing | https://www.iso.org/standard/76383.html |
| ISO/IEC 14496-1:2010 | aac.config.esds | https://www.iso.org/standard/55688.html |

Notes: iso.org returns 403 to automated requests, so the 14496-15 edition was identified from search listings (iso.org
result, IEC webstore, BSI: published 2024-10-31) rather than by fetching the page. ISO/IEC 14496-1 is the 2010 edition
page (an Ed.5 is signalled but unconfirmed). `vvc.nal.reserved_zero_bit` stays FAIL: the clause says
`nuh_reserved_zero_bit` "shall be equal to 0" (decoders ignore NAL units with 1, which would justify WARN, but the
bitstream requirement is FAIL-level).
