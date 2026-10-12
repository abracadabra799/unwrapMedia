#!/usr/bin/env bash
# Regenerates app/src/test/resources/hdr/*.mp4. Needs ffmpeg (libx265, libx264, libsvtav1).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../../app/src/test/resources/hdr"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
MD='G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50)'
SRC='testsrc2=size=320x240:rate=30'

# 1. HEVC + in-band MDCV/CLL SEI + crafted HDR10+ (no B-frames, keyint 10, frames 12-14 missing HDR10+)
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 30 -pix_fmt yuv420p10le -c:v libx265 \
  -x265-params "keyint=10:min-keyint=10:bframes=0:master-display=$MD:max-cll=1000,400:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:info=1:log-level=error" \
  -f hevc "$WORK/hdr.hevc"
python3 -I "$HERE/inject_hdr10plus.py" "$WORK/hdr.hevc" "$WORK/hdr10p.hevc"
ffmpeg -hide_banner -loglevel error -y -r 30 -i "$WORK/hdr10p.hevc" -c copy -tag:v hvc1 "$OUT/hevc_hdr10plus.mp4"
ffmpeg -hide_banner -loglevel error -y -r 30 -i "$WORK/hdr10p.hevc" -c copy "$OUT/hevc_hev1.mp4"

# 2. HEVC with container mdcv/clli boxes and B-frames (re-encode so libx265 exports coded side data)
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 30 -pix_fmt yuv420p10le -c:v libx265 \
  -x265-params "keyint=10:min-keyint=10:bframes=2:master-display=$MD:max-cll=1000,400:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:log-level=error" \
  -tag:v hvc1 "$WORK/hevc_hdr.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$WORK/hevc_hdr.mp4" -pix_fmt yuv420p10le -c:v libx265 -x265-params "keyint=10:log-level=error" \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1 "$OUT/hevc_box.mp4"

# 3. H.264 with MDCV/CLL SEI
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 10 -pix_fmt yuv420p10le -c:v libx264 \
  -x264-params "keyint=5:bframes=0:mastering-display=$MD:cll=1000,400" "$OUT/h264_hdr.mp4"

# 4. AV1 with HDR_MDCV/HDR_CLL metadata OBUs
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 10 -pix_fmt yuv420p10le -c:v libsvtav1 \
  -svtav1-params "keyint=5:mastering-display=G(0.265,0.690)B(0.150,0.060)R(0.680,0.320)WP(0.3127,0.3290)L(1000,0.005):content-light=1000,400" \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc "$OUT/av1_hdr.mp4" 2>/dev/null
ls -l "$OUT"
