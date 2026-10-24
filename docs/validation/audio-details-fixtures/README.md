# Audio metadata fixture provenance

All generated audio is a synthetic 440Hz sine at 48kHz; no personal data.

- `generate_flac.py` generates native FLAC and MP4 FLAC with ffmpeg. It adds a standard SEEKTABLE explicitly, deriving each sample number, stream-relative frame offset and block sample count from actual ffprobe frame records and checking the source sync bytes. The seektable is authored by this script, not emitted by ffmpeg. `verified-flac-bytes.txt` was independently read before parser implementation.
- `default-adts.aac` is generated with `ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=48000 -t 0.1 -c:a aac -f adts app/src/test/resources/detail-parsing/default-adts.aac`. Its first seven bytes are `fff14c401b7ffc`: frequency index3, mono, length219, fullness2047, zero additional raw data blocks. Encoder changes may change lengths; tests pin the checked-in file's fields.
- CRC/copyright/reserved-frequency and malformed record cases are clearly synthetic unit-test headers, not claims of encoder output.

Regenerate FLAC from repository root with `python3 docs/validation/audio-details-fixtures/generate_flac.py`.
