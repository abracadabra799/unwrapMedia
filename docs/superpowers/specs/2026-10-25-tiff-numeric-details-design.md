# TIFF numeric detailed values

Continue the approved Detailed Properties audit. TIFF SBYTE (6), FLOAT (11), DOUBLE (12) currently fall through to hex; DNG NoiseProfile is affected. Decode typed values in file byte order, retaining original byte spans. Known tags may be parsed numerically; unknown tag semantics remain UNINTERPRETED even with a readable number. UNDEFINED and unknown types stay raw. Non-finite IEEE values are displayed literally without claiming semantic validity.

Limit these newly decoded arrays to 256 values; show a bilingual omission marker and UNINTERPRETED state for incomplete display. Keep count as Long until bounded. Out-of-file payload stays UNPARSABLE. No broader TIFF conformance, BigTIFF, DNG opcode or private MakerNote expansion in this unit.

Evidence: Pillow 11.3.0 files and independent struct dump under docs/validation/tiff-numeric-fixtures. SBYTE inline offset126 length4; FLOAT offset182 length12; DOUBLE offset194 length16; private inline FLOAT offset174 length4 in both endian files.
