#!/usr/bin/env python3
"""Independent fixture byte inventory. Does not use application parsers."""
from pathlib import Path
import struct
import sys
CONTAINERS = {'moov', 'trak', 'mdia', 'minf', 'stbl', 'dinf', 'edts', 'udta', 'moof', 'traf', 'mvex', 'mfra', 'iprp', 'ipco'}
def walk(data, start, end, prefix=''):
    pos = start
    while pos + 8 <= end:
        size, tag = struct.unpack_from('>I4s', data, pos)
        tag = tag.decode('latin1')
        header = 8
        if size == 1:
            if pos + 16 > end: return
            size = struct.unpack_from('>Q', data, pos + 8)[0]
            header = 16
        if size == 0: size = end - pos
        if size < header or size > end - pos: return
        path = prefix + '/' + tag
        payload = pos + header
        if tag in {'vmhd', 'smhd', 'btrt', 'fiel', 'esds', 'sdtp', 'sgpd', 'sbgp', 'mfhd', 'trex', 'tfhd', 'tfdt', 'trun', 'pixi', 'auxC', 'infe', 'ctts', 'hvcC', 'avcC'}:
            print(f'{path} offset={pos} payload={payload} size={size} hex={data[payload:pos+size].hex()}')
        skip = 0 if tag in CONTAINERS else {'stsd': 8, 'dref': 8, 'meta': 4, 'iinf': 6 if data[payload] == 0 else 8, 'avc1': 78, 'hvc1': 78, 'hev1': 78, 'av01': 78, 'mp4a': 28}.get(tag)
        if skip is not None: walk(data, payload + skip, pos + size, path)
        pos += size
if __name__ == '__main__':
    for filename in sys.argv[1:]:
        print(filename)
        data = Path(filename).read_bytes()
        walk(data, 0, len(data))
