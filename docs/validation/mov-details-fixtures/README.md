# MOV metadata fixtures

These files contain generated video/audio signals and metadata, with no personal media.
The raw box layouts were independently dumped before implementing the decoders.

- `generate.py`: ffmpeg (Lavf62.12.102) produces keyed.mov and legacy.mov, 64×64 H.264 + AAC, title `검증 MOV`, camera make and custom metadata. Default MOV sound entries are retained. It also produces float-audio.mov: 96 kHz stereo float PCM, 0.02 seconds, with a version-2 lpcm sound description and chan atom.
- `generate_typed.swift`: Apple AVFoundation passthrough export of keyed.mov adds NSNumber metadata (typed.mov). Run from the repository root with `swift -module-cache-path /tmp/unwrap-mov-swift-cache docs/validation/mov-details-fixtures/generate_typed.swift`.
- `raw-video.mov`: ffmpeg 16×16 RGB24 regression fixture generated during review. The ambiguous `raw ` FourCC stays uninterpreted because it also identifies video entries; it is not globally registered as PCM audio.
- `dump.py`: Python standard-library independent box walker. `verified-bytes.txt`, `typed-bytes.txt`, and `float-audio-bytes.txt` retain original byte evidence. Regeneration can change encoder strings/offsets; review byte assertions if tool versions change.

AVFoundation writes requested fixed-width integer metadata using legacy type21/22, including 8-byte integers. Both signed and unsigned 64-bit limits are tested without conversion through Double. Float32/Float64 displays retain their own round-trip precision.

The legacy title atom has bytes `000a55c4` followed by 10 UTF-8 bytes. Apple describes the legacy text length as including the four-byte record header, while this ffmpeg output excludes it. The decoder accepts only a complete unambiguous layout; tests also cover Apple's header-inclusive layout, multiple language records, unsupported Mac encodings and malformed UTF-8. FourCC identifiers preserve all byte values so © atoms reach their decoders.

Primary references (Apple QuickTime File Format):

- [Well-known types](https://developer.apple.com/documentation/quicktime-file-format/well-known_types)
- [Type indicator](https://developer.apple.com/documentation/quicktime-file-format/type_indicator)
- [Locale indicator](https://developer.apple.com/documentation/quicktime-file-format/locale_indicator)
- [Sound description version 1](https://developer.apple.com/documentation/quicktime-file-format/sound_sample_description_version_1)
- [Sound description version 2](https://developer.apple.com/documentation/quicktime-file-format/sound_sample_description_version_2)
- [Audio channel layout atom](https://developer.apple.com/documentation/quicktime-file-format/audio_channel_layout_atom)
- [User data atoms](https://developer.apple.com/documentation/quicktime-file-format/user_data_atoms)

JPEG/PNG/BMP metadata payloads, nested metadata values and unknown type sets remain explicitly uninterpreted; no image decode or private semantic guesses are introduced.
