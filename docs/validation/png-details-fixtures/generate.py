"""Pillow11.3-generated PNG/APNG, with explicit W3C test metadata."""
from pathlib import Path
from PIL import Image,PngImagePlugin
import struct,io
root=Path(__file__).resolve().parents[3];out=root/'app/src/test/resources/png-details';out.mkdir(parents=True,exist_ok=True)
p=Image.new('P',(2,2));p.putpalette([255,0,0,0,255,0,0,0,255,255,255,255]);p.putdata([0,1,2,3])
info=PngImagePlugin.PngInfo();info.add(b'sBIT',bytes([5,6,5]));info.add(b'bKGD',b'\x02');info.add(b'hIST',struct.pack('>4H',10,20,30,40))
info.add(b'sPLT',b'Wide\0\x10'+struct.pack('>5H',65535,0,0,32768,100)+struct.pack('>5H',0,65535,0,65535,50))
p.save(out/'palette.png',bits=2,transparency=bytes([0,64,128,255]),pnginfo=info)
for mode,name,transparent,background,bits in [('L','gray.png',7,struct.pack('>H',17),bytes([4])),('RGB','rgb.png',(1,2,3),struct.pack('>3H',10,20,30),bytes([5,6,5]))]:
 im=Image.new(mode,(2,2));info=PngImagePlugin.PngInfo();info.add(b'bKGD',background);info.add(b'sBIT',bits)
 im.save(out/name,transparency=transparent,pnginfo=info)
a=Image.new('RGBA',(10,8),'red');b=Image.new('RGBA',(10,8),'blue')
a.save(out/'animation.png',save_all=True,append_images=[b],duration=[100,250],loop=3,disposal=[0,1],blend=[0,1])
# Public HDR chunks not emitted by Pillow's PngInfo whitelist are written with
# Pillow's own putchunk writer (CRC included), before IDAT. These are deliberate
# metadata values for layout testing, not measured luminance of this small image.
im=Image.new('RGB',(2,2),'red');bio=io.BytesIO();im.save(bio,format='PNG');data=bio.getvalue()
extra=io.BytesIO()
PngImagePlugin.putchunk(extra,b'cICP',bytes([9,16,0,1]))
PngImagePlugin.putchunk(extra,b'mDCV',struct.pack('>8H2I',34000,16000,13250,34500,7500,3000,15635,16450,10_000_000,50))
PngImagePlugin.putchunk(extra,b'cLLI',struct.pack('>2I',10_000_000,4_000_000))
(out/'hdr-metadata.png').write_bytes(data[:33]+extra.getvalue()+data[33:])
for file in out.glob('*.png'):
 with Image.open(file) as im: im.load()
