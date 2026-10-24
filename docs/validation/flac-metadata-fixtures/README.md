# FLAC metadata byte evidence

Generated silence and solid-color cover, no user media. Run `generate.py` with Python + Mutagen1.47.0 and ffmpeg (Lavf62.12.102); run `dump.py` with stock Python to reproduce the independent dump. The default ffmpeg file is retained alongside CD-DA and non-CD metadata variants. Mutagen writes the metadata; the application parser was not involved in fixture generation or dumps.

Primary reference: [RFC9639 §8.6 Vorbis Comment](https://www.rfc-editor.org/rfc/rfc9639.html#section-8.6), [§8.7 Cuesheet](https://www.rfc-editor.org/rfc/rfc9639.html#section-8.7), [§8.8 Picture](https://www.rfc-editor.org/rfc/rfc9639.html#section-8.8).

The cue header is396 bytes, each track header36 and each index12. Catalog128, lead-in8, flags1, reserved258, count1. Track offset8, number1, ISRC12, flags1, reserved13, index count1. Index offset8, number1, reserved3. Multibyte integers are big-endian except the Vorbis vendor/count/comment lengths, which are little-endian. Sample offsets are not byte offsets. See verified-bytes.txt for literal positions and values.

`metadata.flac` contains a 96-byte real PNG and a URI picture pointing to example.invalid (never fetched). A second non-CD file exercises track type1, non-588 sample positions and lead-out255. Synthetic tests cover malformed/truncated fields and resource caps; they are not claimed to be encoder-produced files.
