"""Builds the Exif/MPF fixture and the byte-patched variants for JpegFixtureTest.

Usage: python3 -I build.py <out dir>   (needs ffmpeg-default.jpg, ffmpeg-thumb.jpg, ffmpeg-second.jpg, cjpeg-restart.jpg,
cjpeg-restart-thumb.jpg in <out dir>)

ffmpeg writes no Exif and exiftool is not installed, so the Exif APP1 and MPF APP2 are written here from CIPA DC-008-2026
(Exif 3.1; ExifVersion "0300" kept, every M and R tag of the compressed-image column) and DC-007-2026 (Baseline MP file: Baseline MP Primary Image
+ one Large Thumbnail class 1). All image data comes from ffmpeg / cjpeg output unchanged.
"""
import struct
import sys
from pathlib import Path

out = Path(sys.argv[1])


def segments(b):
    """(marker, offset, total length incl. marker) of every marker segment before SOS, then the rest."""
    assert b[:2] == b'\xff\xd8'
    pos, segs = 2, []
    while True:
        m = b[pos + 1]
        if m == 0xDA:
            return segs, pos
        n = struct.unpack('>H', b[pos + 2:pos + 4])[0]
        segs.append((m, pos, 2 + n))
        pos += 2 + n


def strip_app_com(b):
    """SOI + every non-APPn/non-COM segment + scan..EOI (a JPEG stream without APPn/COM)."""
    segs, sos = segments(b)
    body = b''.join(b[o:o + n] for m, o, n in segs if not (0xE0 <= m <= 0xEF or m == 0xFE))
    return b'\xff\xd8' + body + b[sos:]


def seg(marker, payload):
    return bytes([0xFF, marker]) + struct.pack('>H', len(payload) + 2) + payload


# ---- little-endian TIFF writer -------------------------------------------------------------------------------
SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 129: 1}


def ascii_(s, nul=True):
    return (2, s.encode('ascii') + (b'\0' if nul else b''))


def short(*v): return (3, b''.join(struct.pack('<H', x) for x in v))
def long_(*v): return (4, b''.join(struct.pack('<I', x) for x in v))
def rational(n, d): return (5, struct.pack('<II', n, d))
def undef(b): return (7, b)


def ifd_bytes(entries, start, next_off, value_start):
    """entries: list of (tag, (type, data)) already in the wanted order. Returns (ifd, values)."""
    ifd = struct.pack('<H', len(entries))
    values = b''
    for tag, (typ, data) in entries:
        count = len(data) // SIZES[typ]
        if len(data) <= 4:
            field = data.ljust(4, b'\0')
        else:
            field = struct.pack('<I', value_start + len(values))
            values += data + (b'\0' if len(data) % 2 else b'')
        ifd += struct.pack('<HHI', tag, typ, count) + field
    ifd += struct.pack('<I', next_off)
    return ifd, values


def exif_app1(thumb, o):
    """Exif APP1 payload ('Exif\\0\\0' + TIFF). o: dict of variant switches."""
    ifd0 = [(0x010E, ascii_('unwrapMedia fixture')), (0x010F, ascii_('unwrapMedia', nul=not o.get('make_no_nul'))),
            (0x0110, ascii_('fixture')), (0x0112, short(1)), (0x011A, rational(72, 1)), (0x011B, rational(72, 1)),
            (0x0128, short(2)), (0x0132, ascii_('2026:10:24 12:00:00')), (0x0213, short(1)), (0x8769, long_(0))]
    if o.get('image_width'):
        ifd0.insert(0, (0x0100, short(64)))
    if o.get('unsorted'):
        ifd0[1], ifd0[2] = ifd0[2], ifd0[1]
    exif = [(0x829A, rational(1, 100)), (0x9000, undef(b'0300')), (0x9101, undef(b'\x01\x02\x03\x00')), (0x9209, short(0)),
            (0xA001, short(1)), (0xA002, short(64)), (0xA003, short(48)), (0xA402, short(0)), (0xA403, short(0)),
            (0xA406, short(0)), (0xA460, short(1))]
    if o.get('no_colorspace'):
        exif = [e for e in exif if e[0] != 0xA001]
    ifd1 = [(0x0103, short(6)), (0x0201, long_(0)), (0x0202, long_(len(thumb)))]
    # layout: header(8) | IFD0 | values0 | ExifIFD | valuesE | IFD1 | values1 | thumbnail
    def size(entries):
        ifd, vals = ifd_bytes(entries, 0, 0, 0)
        return len(ifd), len(vals)
    i0, v0 = size(ifd0)
    ie, ve = size(exif)
    i1, v1 = size(ifd1)
    off0 = 8
    offe = off0 + i0 + v0
    off1 = offe + ie + ve
    offt = off1 + i1 + v1
    ifd0 = [(t, long_(offe)) if t == 0x8769 else (t, v) for t, v in ifd0]
    ifd1 = [(t, long_(offt)) if t == 0x0201 else (t, v) for t, v in ifd1]
    next0 = off0 if o.get('ifd_loop') else off1
    b0, vals0 = ifd_bytes(ifd0, off0, next0, off0 + i0)
    be, valse = ifd_bytes(exif, offe, 0, offe + ie)
    b1, vals1 = ifd_bytes(ifd1, off1, 0, off1 + i1)
    tiff = b'II*\0' + struct.pack('<I', off0) + b0 + vals0 + be + valse + b1 + vals1 + thumb
    assert len(tiff) == offt + len(thumb)
    return b'Exif\0\0' + tiff


def mpf_app2(primary_size, second_rel, second_size, o):
    entries = struct.pack('<IIIHH', 0xA0030000, primary_size + o.get('size_delta', 0), 0, 2, 0)
    entries += struct.pack('<IIIHH', 0x40000000 | o.get('type2', 0x010001), second_size, second_rel, 0, 0)
    ifd_len = 2 + 3 * 12 + 4
    ifd = struct.pack('<H', 3)
    ifd += struct.pack('<HHI', 0xB000, 7, 4) + b'0100'
    ifd += struct.pack('<HHII', 0xB001, 4, 1, 2)
    ifd += struct.pack('<HHII', 0xB002, 7, 32, 8 + ifd_len)
    ifd += struct.pack('<I', 0)
    return b'MPF\0' + b'II*\0' + struct.pack('<I', 8) + ifd + entries


def build(o):
    primary = strip_app_com((out / 'ffmpeg-default.jpg').read_bytes())
    thumb_src = 'cjpeg-restart-thumb.jpg' if o.get('thumb_rst') else 'ffmpeg-thumb.jpg'
    thumb = strip_app_com((out / thumb_src).read_bytes())
    second = strip_app_com((out / 'ffmpeg-second.jpg').read_bytes())
    app1 = seg(0xE1, exif_app1(thumb, o))
    app2_probe = seg(0xE2, mpf_app2(0, 0, 0, o))
    primary_size = len(primary) + len(app1) + len(app2_probe)
    base = 2 + len(app1) + 8  # offset of the MP Endian field
    app2 = seg(0xE2, mpf_app2(primary_size, primary_size - base, len(second), o))
    assert len(app2) == len(app2_probe)
    return primary[:2] + app1 + app2 + primary[2:] + second


def patch(src, name, edits):
    b = bytearray((out / src).read_bytes())
    for off, data in edits:
        b[off:off + len(data)] = data
    (out / name).write_bytes(bytes(b))


def find_marker(b, marker, nth=0):
    """Offset of the nth marker segment 0xFF<marker> before SOS (or SOS itself)."""
    segs, sos = segments(b)
    hits = [o for m, o, n in segs if m == marker] + ([sos] if marker == 0xDA else [])
    return hits[nth]


(out / 'exif-mpf.jpg').write_bytes(build({}))
for name, o in {'exif-unsorted.jpg': {'unsorted': 1}, 'exif-no-colorspace.jpg': {'no_colorspace': 1},
                'exif-imagewidth.jpg': {'image_width': 1}, 'exif-ifd-loop.jpg': {'ifd_loop': 1},
                'exif-ascii-nonull.jpg': {'make_no_nul': 1}, 'exif-thumb-rst.jpg': {'thumb_rst': 1},
                'mpf-type0.jpg': {'type2': 0x000000}, 'mpf-size.jpg': {'size_delta': 14494}}.items():
    (out / name).write_bytes(build(o))

ff = (out / 'ffmpeg-default.jpg').read_bytes()
(out / 'no-eoi.jpg').write_bytes(ff[:-2])
(out / 'trailing-garbage.jpg').write_bytes(ff + bytes(16))
dqt = find_marker(ff, 0xDB)
patch('ffmpeg-default.jpg', 'no-dqt.jpg', [(dqt + 1, b'\xfe')])                 # DQT -> COM
sof = find_marker(ff, 0xC0)
patch('ffmpeg-default.jpg', 'sof-h0.jpg', [(sof + 11, bytes([ff[sof + 11] & 0x0F]))])  # component 1: H 1 -> 0
sos = find_marker(ff, 0xDA)
ns = ff[sos + 4]
patch('ffmpeg-default.jpg', 'scan-se.jpg', [(sos + 5 + 2 * ns + 1, b'\x3e')])  # Se 63 -> 62
app0 = find_marker(ff, 0xE0)
com = find_marker(ff, 0xFE)
a0 = ff[app0:app0 + 2 + struct.unpack('>H', ff[app0 + 2:app0 + 4])[0]]
c0 = ff[com:com + 2 + struct.unpack('>H', ff[com + 2:com + 4])[0]]
assert app0 == 2 and com == 2 + len(a0)
(out / 'jfif-not-first.jpg').write_bytes(ff[:2] + c0 + a0 + ff[2 + len(a0) + len(c0):])  # COM before JFIF APP0
patch('ffmpeg-default.jpg', 'jfif-density0.jpg', [(app0 + 12, b'\x00\x00')])     # Hdensity 1 -> 0

cr = (out / 'cjpeg-restart.jpg').read_bytes()
dri = find_marker(cr, 0xDD)
patch('cjpeg-restart.jpg', 'dri-zero.jpg', [(dri + 4, b'\x00\x00')])            # Ri 4 -> 0
rst0 = cr.index(b'\xff\xd0', find_marker(cr, 0xDA))
patch('cjpeg-restart.jpg', 'rst-order.jpg', [(rst0 + 1, b'\xd1')])               # first RST0 -> RST1
