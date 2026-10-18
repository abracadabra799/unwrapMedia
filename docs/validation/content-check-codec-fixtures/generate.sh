#!/usr/bin/env bash
# Video+audio fixtures for the Content Check CODEC stage: real phone/camera MP4s carry both a video
# and an AAC track, and the stage must byte-check both (the video-only fixtures elsewhere hid a bug
# where the AAC check pre-empted the video codec check). ffmpeg defaults only (hev1 / avc1 / mp4a).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/content-check-codecs"
mkdir -p "$out"
src=(-f lavfi -i 'testsrc2=size=64x64:rate=4:duration=2' -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2')
ffmpeg -hide_banner -loglevel error -y "${src[@]}" -frames:v 8 -pix_fmt yuv420p \
  -c:v libx265 -x265-params 'pools=1:frame-threads=1:log-level=error' -c:a aac -shortest "$out/hevc-aac.mp4"
ffmpeg -hide_banner -loglevel error -y "${src[@]}" -frames:v 8 -pix_fmt yuv420p \
  -c:v libx264 -c:a aac -shortest "$out/avc-aac.mp4"
# MPEG-4 Part 2 video has no byte checker: only the audio track can be checked.
ffmpeg -hide_banner -loglevel error -y "${src[@]}" -frames:v 8 -pix_fmt yuv420p \
  -c:v mpeg4 -c:a aac -shortest "$out/mpeg4-aac.mp4"
