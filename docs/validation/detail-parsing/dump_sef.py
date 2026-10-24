"""Independent little-endian SEF directory dump for existing device fixtures."""
from pathlib import Path
import struct
import sys
for filename in sys.argv[1:]:
    b = Path(filename).read_bytes()
    end = b.rfind(b'SEFT') + 4
    if end < 8: continue
    size = struct.unpack_from('<I', b, end-8)[0]
    header = end-8-size
    print(filename, 'SEFH', header, 'end', end, 'size', size, 'header_hex', b[header:header+12].hex())
    count = struct.unpack_from('<I', b, header+8)[0]
    for i in range(min(count, 4096)):
        pos = header+12+i*12
        reserved, marker, offset, length = struct.unpack_from('<HHII', b, pos)
        block = header-offset
        name_size = struct.unpack_from('<I', b, block+4)[0]
        print(i, 'directory', pos, b[pos:pos+12].hex(), 'block', block, 'size', length,
              'header', b[block:block+8].hex(), 'name', b[block+8:block+8+name_size])
