#!/usr/bin/env bash
# Google Motion Photo 1.0 checker fixtures: a real ffmpeg JPEG + a real libx264 MP4 (ffmpeg defaults, avc1),
# combined by make_fixtures.py into a compliant file and two violating variants (see that script).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/google-motion-photo"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x48' -frames:v 1 "$work/still.jpg"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x48:rate=8:duration=1' -pix_fmt yuv420p \
  -c:v libx264 "$work/video.mp4"
python3 -I "$here/make_fixtures.py" "$work/still.jpg" "$work/video.mp4" "$out"
