#!/bin/sh
# Regenerates app/src/test/resources/video-frame/. Verified layouts: see README.md.
set -e
OUT="$(cd "$(dirname "$0")/../../.." && pwd)/app/src/test/resources/video-frame"
mkdir -p "$OUT"
SRC="testsrc2=size=160x90:rate=30:duration=2"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx264 -bf 2 -g 30 "$OUT/default.mp4"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx264 -bf 2 -g 30 -output_ts_offset 10 "$OUT/offset.ts"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx265 -x265-params log-level=error:bframes=3 "$OUT/hevc.mp4"
ffmpeg -v error -y -f lavfi -i "$SRC" -vf "setpts='PTS+if(gt(N,20),0.2/TB,0)'" -fps_mode vfr -c:v libx264 -bf 2 "$OUT/vfr.mkv"
ffmpeg -v error -y -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -x265-params log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc "$OUT/hdr10.mp4"
for f in default.mp4 offset.ts hevc.mp4 vfr.mkv hdr10.mp4; do
  echo "$f start_time=$(ffprobe -v error -show_entries format=start_time -of csv=p=0 "$OUT/$f")"
done
