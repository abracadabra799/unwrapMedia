"""Pillow 11.3.0; metadata-layout fixtures, not camera-generated DNGs."""
from pathlib import Path
from PIL import Image, TiffImagePlugin
out = Path('app/src/test/resources/tiff-numeric')
for endian, mode in [('le', 'I;16'), ('be', 'I;16B')]:
    info = TiffImagePlugin.ImageFileDirectory_v2(prefix=b'II' if endian == 'le' else b'MM')
    for tag, kind, values in [(340,6,(-128,-1,0,127)), (341,11,(-1.5,0.25,65536.0)),
                              (50706,1,bytes((1,4,0,0))), (51041,12,(0.125,-0.000001)),
                              (65000,11,(1.25,))]:
        info.tagtype[tag] = kind
        info[tag] = values
    Image.new(mode, (2,2), 1).save(out / f'{endian}.tif', tiffinfo=info, compression='raw')
Image.new('RGB',(2,2),(10,20,30)).save(out / 'default.tif')
