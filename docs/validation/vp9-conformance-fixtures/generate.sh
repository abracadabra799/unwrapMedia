#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/vp9-conformance"
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x64:rate=4:duration=2' \
  -frames:v 8 -c:v libvpx-vp9 -pix_fmt yuv420p \
  "$out/default-vp09.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x64:rate=30:duration=4' \
  -frames:v 101 -c:v libvpx-vp9 -pix_fmt yuv420p \
  "$out/stress-source.mp4"
python3 "$here/mutate.py" "$out"
rm -f "$out/stress-source.mp4"
