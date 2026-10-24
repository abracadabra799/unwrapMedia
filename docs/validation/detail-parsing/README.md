# Detail parser fixture provenance

`app/src/test/resources/detail-parsing/default-avc-aac.mp4` is generated locally from ffmpeg lavfi signals; contains no personal data. Default codec tags retained.

Regenerate from repository root:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=64x64:rate=5 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 0.4 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart app/src/test/resources/detail-parsing/default-avc-aac.mp4
python3 docs/validation/detail-parsing/dump_boxes.py app/src/test/resources/detail-parsing/default-avc-aac.mp4 app/src/test/resources/hdr/av1_hdr.mp4
```

`verified-bytes.txt` records the independent dump before application parser implementation. The existing AV1 HDR fixture supplies fiel and sdtp. Bitrates may vary across encoder versions; tests assert the checked-in file's raw fields.

Fragment fixture (lavfi, default AVC tag):
```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=64x64:rate=5 -t 0.4 -c:v libx264 -pix_fmt yuv420p -movflags +empty_moov+frag_keyframe+default_base_moof app/src/test/resources/detail-parsing/fragmented-avc.mp4
python3 docs/validation/detail-parsing/dump_boxes.py app/src/test/resources/detail-parsing/fragmented-avc.mp4
```

HEIF/SEF follow-up (2026-10-24): existing device fixtures under c2pa/ retain their original provenance. Regenerate heif-bytes.txt with `python3 docs/validation/detail-parsing/dump_boxes.py app/src/test/resources/c2pa/s26-motion-photo.heic`; regenerate sef-bytes.txt with `python3 docs/validation/detail-parsing/dump_sef.py app/src/test/resources/c2pa/s26-motion-photo.heic app/src/test/resources/c2pa/s26-camera.jpg`. The scripts are independent of application parsers.
