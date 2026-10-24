#!/usr/bin/env bash
# Regenerates the ffmpeg-default ISO-BMFF / QuickTime fixtures used by IsoBmffFixtureTest, then derives the
# corrupted variants with corrupt.py. Default files are made with ffmpeg's default muxer settings (no -tag, no
# -movflags), so they reflect what users actually get (AGENTS.md rule 2). -fflags +bitexact keeps them stable.
#   v.mp4  64x48, 8 frames, libx264 + aac        (isom)
#   v.mov  same, QuickTime muxer                (qt  , wide, mhlr/dhlr)
#   a.m4a  aac 0.5 s                            (M4A )
#   v.3gp  h263 176x144 + aac 8 kHz mono 0.5 s  (3gp4)
#   frag.mp4  as v.mp4, fragmented (-movflags +frag_keyframe+empty_moov): ffmpeg writes next_track_ID 2 with
#             track_IDs 1 and 2, which bmff.mvhd.next_track_id reports as FAIL (decision: strict)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/isobmff-conformance"
mkdir -p "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

video_in=(-f lavfi -i 'testsrc=size=64x48:rate=16' -f lavfi -i 'sine=duration=0.5:sample_rate=8000')
for ext in mp4 mov; do
  ff "${video_in[@]}" -frames:v 8 -c:v libx264 -preset ultrafast -c:a aac -b:a 16k -ac 1 -shortest \
    -fflags +bitexact "$out/v.$ext"
done
ff "${video_in[@]}" -frames:v 8 -c:v libx264 -preset ultrafast -c:a aac -b:a 16k -ac 1 -shortest \
  -movflags +frag_keyframe+empty_moov -fflags +bitexact "$out/frag.mp4"
ff -f lavfi -i 'sine=duration=0.5:sample_rate=8000' -c:a aac -b:a 16k -ac 1 -fflags +bitexact "$out/a.m4a"
ff -f lavfi -i 'testsrc=size=176x144:rate=8' -f lavfi -i 'sine=duration=0.5:sample_rate=8000' \
  -t 0.5 -c:v h263 -c:a aac -b:a 12k -ar 8000 -ac 1 -fflags +bitexact "$out/v.3gp"

python3 -I "$here/corrupt.py" "$out/v.mp4" "$out"
ls -l "$out"
