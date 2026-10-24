# MP4 dependency and group details

Continue the approved detail-parsing program with sdtp and sgpd. Use verified real bytes in docs/validation/detail-parsing/verified-bytes.txt. SDTP samples are one byte each, four two-bit fields (is_leading, sample_depends_on, sample_is_depended_on, sample_has_redundancy). Present every sample in the existing paginated table; do not allocate one BoxNode per video sample.

Extend TableData with an explicit row encoding, default unsigned for compatibility. Add a table-aware reader used by the UI; export non-default encoding in JSON so consumers can decode source bytes. SGPD roll/prol entries contain signed 16-bit roll_distance. Fixed two-byte entries use a lazy signed table. Variable or other known-length descriptions become bounded child nodes with raw/source range and known fields; unknown v0 group layout must be explicitly unparsed. Unknown versions/truncation warn. No heavy dependencies, no automatic push/version bump.
