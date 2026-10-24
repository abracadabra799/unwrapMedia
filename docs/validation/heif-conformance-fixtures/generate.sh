#!/usr/bin/env bash
# Regenerates the real-tool HEIF / AVIF fixtures used by HeifFixtureTest, then derives the corrupted variants with
# corrupt.py. Default encoder settings only (AGENTS.md rule 2); -fflags +bitexact keeps the ffmpeg output stable.
#   still.avif  64x48 testsrc2, 1 frame, ffmpeg libsvtav1 (avif mif1 miaf MA1B; ispe, pixi, essential av1C, colr)
#   sips.heic   the same frame as PNG, converted by macOS `sips -s format heic` (heic; mif1 MiPr miaf MiHB heic;
#               dinf in meta, colr/clli/ispe/irot/pixi/hvcC, 64-bit largesize mdat)
# `sips` exists only on macOS: elsewhere the committed sips.heic is kept and only its variants are regenerated.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/heif-conformance"
mkdir -p "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -c:v libsvtav1 -fflags +bitexact "$out/still.avif" 2>/dev/null

if command -v sips >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 "$tmp/src.png"
  sips -s format heic "$tmp/src.png" --out "$out/sips.heic" >/dev/null
else
  echo "sips not found (macOS only): keeping the committed sips.heic" >&2
  [ -f "$out/sips.heic" ] || { echo "sips.heic is missing and cannot be regenerated here" >&2; exit 1; }
fi

python3 -I "$here/corrupt.py" "$out/sips.heic" "$out/still.avif" "$out"
ls -l "$out"
