#!/usr/bin/env bash
# Regenerates ffmpeg-default isom MP4 / M4A files with a chapter track (minf holds gmhd, not nmhd).
# Used by IsoBmffStructureRulesTest: expected no FAIL; WARN only for bmff.mediaheader.match (ISO profile + gmhd) and
# bmff.mdhd.language (ffmpeg writes language 0 on the text chapter track).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/isobmff-conformance"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf ';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=A\n' > "$tmp/ch.txt"
for ext in mp4 m4a; do
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'sine=duration=1:sample_rate=8000' -i "$tmp/ch.txt" \
    -map 0 -map_chapters 1 -ac 1 -c:a aac -b:a 16k -fflags +bitexact "$out/chapters.$ext"
done
