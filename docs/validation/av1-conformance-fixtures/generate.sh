#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/av1-conformance"
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x64:rate=4:duration=2' \
  -frames:v 8 -pix_fmt yuv420p -c:v libsvtav1 \
  "$out/default-av01.mp4"
python3 "$here/mutate.py" "$out"
