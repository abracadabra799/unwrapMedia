#!/bin/sh
# Regenerates app/src/test/resources/format-sniff/ (content-based format detection fixtures).
# Tools: ffmpeg (libx264, aac, libmp3lame, wmav2, libsvtav1), macOS sips (HEIC).
# Verified headers (xxd) at generation time, 2026-10-20:
#   a.mov  ftyp major 'qt  '                a.mp4  ftyp major 'isom'
#   a.m4a  ftyp major 'M4A '                a.heic ftyp major 'heic', compat 'mif1 MiPr miaf MiHB'
#   a.avif ftyp major 'avif', compat 'avif mif1 miaf MA1B'
#   a.aac  FF F1 (ADTS)   a_noid3.mp3 FF FB (MPEG-1 L3, no ID3)   a.mp3 'ID3'   a.wma ASF GUID 30 26 B2 75
set -e
OUT=${1:-app/src/test/resources/format-sniff}
mkdir -p "$OUT" && cd "$OUT"
ffmpeg -v error -y -f lavfi -i testsrc=s=64x64:d=0.2 -c:v libx264 a.mov
ffmpeg -v error -y -f lavfi -i testsrc=s=64x64:d=0.2 -c:v libx264 a.mp4
ffmpeg -v error -y -f lavfi -i sine=d=0.3 -c:a aac a.m4a
ffmpeg -v error -y -f lavfi -i sine=d=0.3 -c:a aac -f adts a.aac
ffmpeg -v error -y -f lavfi -i sine=d=0.3 -c:a libmp3lame -id3v2_version 0 -write_xing 0 a_noid3.mp3
ffmpeg -v error -y -f lavfi -i sine=d=0.3 -c:a libmp3lame a.mp3
ffmpeg -v error -y -f lavfi -i sine=d=0.3 -c:a wmav2 a.wma
ffmpeg -v error -y -f lavfi -i testsrc=s=64x64:d=0.2 -frames:v 1 a.png
ffmpeg -v error -y -i a.png -q:v 5 a.jpg
sips -s format heic a.png --out a.heic >/dev/null
ffmpeg -v error -y -i a.png -c:v libsvtav1 -frames:v 1 a.avif
