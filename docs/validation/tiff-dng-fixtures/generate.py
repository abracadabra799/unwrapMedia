"""Pillow 11.3.0; generate verified TIFF/DNG metadata fixtures in LE and BE order."""
from pathlib import Path
from PIL import Image, TiffImagePlugin

out = Path('app/src/test/resources/tiff-dng')
out.mkdir(parents=True, exist_ok=True)

for endian, mode in [('le', 'I;16'), ('be', 'I;16B')]:
    info = TiffImagePlugin.ImageFileDirectory_v2(prefix=b'II' if endian == 'le' else b'MM')
    tags = [
        (50706, 1, bytes((1, 4, 0, 0))),       # DNGVersion: 1.4.0.0
        (50707, 1, bytes((1, 1, 0, 0))),       # DNGBackwardVersion: 1.1.0.0
        (33421, 3, (2, 2)),                     # CFARepeatPatternDim: 2 x 2
        (33422, 1, bytes((0, 1, 1, 2))),       # CFAPattern: Red, Green, Green, Blue
        (50710, 1, bytes((0, 1, 2))),          # CFAPlaneColor: Red, Green, Blue
        (50711, 3, 1),                          # CFALayout: Rectangular
        (50778, 3, 17),                         # CalibrationIlluminant1: Standard light A
        (50779, 3, 21),                         # CalibrationIlluminant2: D65
        (50879, 3, 0),                          # ColorimetricReference: sRGB
        (50941, 4, 0),                          # ProfileEmbedPolicy: allowCopying
        (51008, 7, bytes((0, 0, 0, 1))),       # OpcodeList1: 4-byte raw payload
    ]
    for tag, kind, values in tags:
        info.tagtype[tag] = kind
        info[tag] = values
    Image.new(mode, (2, 2), 1).save(out / f'{endian}.tif', tiffinfo=info, compression='raw')

# Default-settings fixture
Image.new('RGB', (2, 2), (10, 20, 30)).save(out / 'default.tif')
print("Successfully generated le.tif, be.tif, and default.tif in", out)
