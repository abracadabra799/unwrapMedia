"""Independent struct-only TIFF IFD dump, no Pillow/parser normalization."""
from pathlib import Path
import struct
for path in sorted(Path('app/src/test/resources/tiff-numeric').glob('*.tif')):
    data=path.read_bytes(); order='<' if data[:2]==b'II' else '>'
    off=struct.unpack_from(order+'I',data,4)[0]
    print(path.name, 'bytes=',len(data), 'order=',data[:2])
    for i in range(struct.unpack_from(order+'H',data,off)[0]):
        pos=off+2+i*12
        tag,kind,count=struct.unpack_from(order+'HHI',data,pos)
        if tag not in (340,341,50706,51041,65000): continue
        fmt={1:'B',6:'b',11:'f',12:'d'}[kind]; size=struct.calcsize(fmt)*count
        start=pos+8 if size<=4 else struct.unpack_from(order+'I',data,pos+8)[0]
        print(tag,kind,count,'offset=',start,'length=',size,'raw=',data[start:start+size].hex(),'values=',struct.unpack_from(order+str(count)+fmt,data,start))
