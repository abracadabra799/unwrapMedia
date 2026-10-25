# TIFF/DNG Additional Tag Fixtures

Generated using Python 3 with Pillow 11.3.0.
Verifies DNG version, CFA pattern and layout, illuminant codes, color reference, profile embed policy, and OpcodeList raw tags in both Little-Endian (`II`) and Big-Endian (`MM`) byte orders.

## Files
- `app/src/test/resources/tiff-dng/le.tif`: Little-endian TIFF with DNG and CFA tags
- `app/src/test/resources/tiff-dng/be.tif`: Big-endian TIFF with DNG and CFA tags
- `app/src/test/resources/tiff-dng/default.tif`: Default RGB TIFF image

## Reproduction
```bash
python3 docs/validation/tiff-dng-fixtures/generate.py
python3 docs/validation/tiff-dng-fixtures/dump.py
```
Verified raw byte dumps are saved in `verified-bytes.txt`.
