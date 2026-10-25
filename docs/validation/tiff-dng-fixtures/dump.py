"""Independent struct-only TIFF IFD dump, no Pillow/parser normalization."""
from pathlib import Path
import struct

out_lines = []
for path in sorted(Path('app/src/test/resources/tiff-dng').glob('*.tif')):
    data = path.read_bytes()
    order = '<' if data[:2] == b'II' else '>'
    off = struct.unpack_from(order + 'I', data, 4)[0]
    out_lines.append(f"{path.name} bytes= {len(data)} order= {data[:2]}")
    count_entries = struct.unpack_from(order + 'H', data, off)[0]
    for i in range(count_entries):
        pos = off + 2 + i * 12
        tag, kind, count = struct.unpack_from(order + 'HHI', data, pos)
        if tag not in (33421, 33422, 50706, 50707, 50710, 50711, 50778, 50779, 50879, 50941, 51008):
            continue
        fmt = {1: 'B', 3: 'H', 4: 'I', 7: 'B'}[kind]
        size = struct.calcsize(fmt) * count
        start = pos + 8 if size <= 4 else struct.unpack_from(order + 'I', data, pos + 8)[0]
        raw = data[start:start + size].hex()
        values = struct.unpack_from(order + str(count) + fmt, data, start)
        out_lines.append(f"{tag} kind={kind} count={count} offset={start} length={size} raw={raw} values={values}")

text = "\n".join(out_lines) + "\n"
print(text)
Path('docs/validation/tiff-dng-fixtures/verified-bytes.txt').write_text(text)
