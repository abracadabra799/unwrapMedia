#!/usr/bin/env bash
# Fixtures for Content Check false positives found by a 2026-10-22 corrupted-sample sweep:
#  - lame-info.mp3: default libmp3lame output. Its LAME "Info" tag counts the audio frames *excluding*
#    the tag frame itself (ffprobe -count_packets agrees), so the structure check must not WARN.
#  - aac-only.mp4: audio-only MP4 (ftyp isom, opens as VIDEO by extension); has no video stream to decode.
#  - avc-aac-bad-video.mp4: H.264+AAC with forbidden_zero_bit set in the first video NAL. ffprobe's
#    stream probing decodes the video and logs an h264 error; that error must not mark the *audio* bad.
#  - avc-aac.mp4: the same file uncorrupted (control).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/content-check-false-positives"
mkdir -p "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
ff -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=0.5' -c:a libmp3lame -b:a 64k "$out/lame-info.mp3"
ff -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=0.5' -c:a aac "$out/aac-only.mp4"
ff -f lavfi -i 'testsrc2=size=64x64:rate=8:duration=1' -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=1' \
  -pix_fmt yuv420p -c:v libx264 -c:a aac -shortest "$out/avc-aac.mp4"
python3 -I "$here/set_forbidden_bit.py" "$out/avc-aac.mp4" "$out/avc-aac-bad-video.mp4"
