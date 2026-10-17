#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/hevc-conformance"
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x64:rate=4:duration=2' \
  -frames:v 8 -pix_fmt yuv420p -c:v libx265 -x265-params 'pools=1:frame-threads=1:log-level=error' \
  "$out/default-hev1.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$out/default-hev1.mp4" -c copy -tag:v hvc1 "$out/explicit-hvc1.mp4"
python3 "$here/mutate.py" "$out"
