#!/usr/bin/env bash
# Regenerates the PNG / WebP / GIF conformance fixtures used by Png/Webp/GifFixtureTest. Default encoder settings
# (AGENTS.md rule 2); -fflags +bitexact keeps the ffmpeg output byte-stable. ffmpeg 8.1.2 has no WebP encoder here, so
# the default-settings WebP fixture is libwebp's cwebp. macOS sips writes PNG and GIF (not WebP).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../../app/src/test/resources/png-webp-gif-conformance}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt pal8 "$out/ffmpeg-pal8.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt gray16be "$out/ffmpeg-gray16.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt rgba "$out/ffmpeg-rgba.png"
ff -f lavfi -i testsrc2=s=64x48:r=10 -frames:v 3 -fflags +bitexact -f apng "$out/ffmpeg.apng"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.gif"
ff -f lavfi -i testsrc2=s=64x48:r=10 -frames:v 3 -fflags +bitexact "$out/ffmpeg-anim.gif"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -pix_fmt rgba "$tmp/rgba.png"
ff -f lavfi -i "testsrc2=s=64x48,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(X,32),255,128)'" -frames:v 1 "$tmp/alpha.png"
cwebp -quiet "$tmp/rgba.png" -o "$out/cwebp-default.webp"
cwebp -quiet -lossless "$tmp/rgba.png" -o "$out/cwebp-lossless.webp"
cwebp -quiet "$tmp/alpha.png" -o "$out/cwebp-alpha.webp"
img2webp -loop 0 -d 100 "$out/ffmpeg-default.png" -d 100 "$tmp/alpha.png" -o "$out/img2webp-anim.webp" >/dev/null 2>&1
gif2webp -quiet "$out/ffmpeg-anim.gif" -o "$out/gif2webp-anim.webp"
if command -v sips >/dev/null 2>&1; then
  sips -s format png "$tmp/rgba.png" --out "$out/sips.png" >/dev/null
  sips -s format gif "$tmp/rgba.png" --out "$out/sips.gif" >/dev/null
else
  echo "sips not found (macOS only): keeping the committed sips.png / sips.gif" >&2
fi
python3 -I "$here/build.py" "$out"
ls -l "$out"
