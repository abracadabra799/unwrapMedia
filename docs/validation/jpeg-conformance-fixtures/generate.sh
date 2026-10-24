#!/usr/bin/env bash
# Regenerates the JPEG conformance fixtures used by JpegFixtureTest. Default encoder settings (AGENTS.md rule 2);
# -fflags +bitexact keeps the ffmpeg output byte-stable.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../../app/src/test/resources/jpeg-conformance}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.jpg"
ff -f lavfi -i testsrc2=s=16x12 -frames:v 1 -fflags +bitexact "$out/ffmpeg-thumb.jpg"
ff -f lavfi -i testsrc2=s=32x24 -frames:v 1 -fflags +bitexact "$out/ffmpeg-second.jpg"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 "$tmp/src.ppm"
ff -f lavfi -i testsrc2=s=32x24 -frames:v 1 "$tmp/thumb.ppm"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 "$tmp/src.png"
cjpeg -outfile "$out/cjpeg-default.jpg" "$tmp/src.ppm"
cjpeg -progressive -outfile "$out/cjpeg-progressive.jpg" "$tmp/src.ppm"
cjpeg -restart 1 -outfile "$out/cjpeg-restart.jpg" "$tmp/src.ppm"
cjpeg -restart 1B -outfile "$out/cjpeg-restart-thumb.jpg" "$tmp/thumb.ppm"
if command -v sips >/dev/null 2>&1; then
  sips -s format jpeg "$tmp/src.png" --out "$out/sips.jpg" >/dev/null
else
  echo "sips not found (macOS only): keeping the committed sips.jpg" >&2
fi
python3 -I "$here/build.py" "$out"
ls -l "$out"
