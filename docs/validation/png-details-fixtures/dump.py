"""Independent PNG byte walker using only Python standard library."""
from pathlib import Path
import struct,zlib
root=Path(__file__).resolve().parents[3]
for file in sorted((root/'app/src/test/resources/png-details').glob('*.png')):
 data=file.read_bytes();pos=8;print(file.name,len(data))
 while pos<len(data):
  size,kind=struct.unpack('>I4s',data[pos:pos+8]);payload=data[pos+8:pos+8+size];crc=struct.unpack('>I',data[pos+8+size:pos+12+size])[0]
  assert crc==zlib.crc32(kind+payload)
  print(kind.decode(), 'offset',pos,'length',size,'payload',payload.hex() if kind not in [b'IDAT',b'fdAT'] else payload[:4].hex()+'...')
  pos+=12+size
 assert pos==len(data)
