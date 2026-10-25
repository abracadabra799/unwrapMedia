"""Builds the byte-patched PNG / WebP / GIF variants for Png/Webp/GifFixtureTest.

Usage: python3 -I build.py <out dir>   (needs the tool outputs written by generate.sh in <out dir>)

Every variant starts from a real tool output and changes the fewest bytes needed to break exactly one rule (PNG chunk
CRCs are recomputed so only the targeted rule fails). Offsets are found by walking the structure, never hard-coded.
"""
import struct
import sys
import zlib
from pathlib import Path

out = Path(sys.argv[1])


def read(name):
    return (out / name).read_bytes()


def write(name, data):
    (out / name).write_bytes(bytes(data))


# ---- PNG ---------------------------------------------------------------------------------------------------------
def png_chunks(b):
    """[(type, data)] of a PNG after the 8-byte signature, up to and including IEND."""
    pos, out_ = 8, []
    while pos < len(b):
        n = struct.unpack('>I', b[pos:pos + 4])[0]
        t = b[pos + 4:pos + 8].decode('latin1')
        out_.append((t, b[pos + 8:pos + 8 + n]))
        pos += 12 + n
        if t == 'IEND':
            break
    return out_


def png_chunk(t, data):
    tb = t.encode('latin1')
    return struct.pack('>I', len(data)) + tb + data + struct.pack('>I', zlib.crc32(tb + data))


def png_build(chunks):
    return b'\x89PNG\r\n\x1a\n' + b''.join(png_chunk(t, d) for t, d in chunks)


def png_variant(src, name, edit):
    chunks = png_chunks(read(src))
    write(name, png_build(edit(chunks)))


def insert_before(chunks, t, new):
    i = next(i for i, c in enumerate(chunks) if c[0] == t)
    return chunks[:i] + new + chunks[i:]


def replace_data(chunks, t, fn, nth=0):
    out_, seen = [], 0
    for ct, d in chunks:
        if ct == t:
            if seen == nth:
                d = fn(bytearray(d))
            seen += 1
        out_.append((ct, bytes(d)))
    return out_


def set_bytes(d, off, data):
    d[off:off + len(data)] = data
    return d


def fdict_flg():
    """FLG with FDICT=1, FLEVEL=2 and a valid FCHECK for CMF 0x78."""
    base = 0x80 | 0x20
    return base + (31 - (0x78 * 256 + base) % 31) % 31


ff = read('ffmpeg-default.png')
b = bytearray(ff)
idat = ff.index(b'IDAT') - 4
b[idat + 8 + 100] ^= 0xFF  # one IDAT data byte, CRC left stale
write('png-crc.png', b)
write('png-truncated.png', ff[:-6])
write('png-trailing.png', ff + bytes(16))
png_variant('ffmpeg-default.png', 'png-ihdr-depth.png', lambda c: replace_data(c, 'IHDR', lambda d: set_bytes(d, 8, b'\x04')))
png_variant('ffmpeg-gray16.png', 'png-plte-gray.png', lambda c: insert_before(c, 'IDAT', [('PLTE', bytes(9))]))
png_variant('ffmpeg-pal8.png', 'png-pal-noplte.png', lambda c: [x for x in c if x[0] != 'PLTE'])
png_variant('ffmpeg-default.png', 'png-order.png', lambda c: [x for x in c if x[0] != 'pHYs'][:-1] + [x for x in c if x[0] == 'pHYs'] + [c[-1]])
png_variant('ffmpeg-default.png', 'png-gama-dup.png', lambda c: insert_before(c, 'IDAT', [('gAMA', struct.pack('>I', 45455))] * 2))
png_variant('ffmpeg-rgba.png', 'png-trns-rgba.png', lambda c: insert_before(c, 'IDAT', [('tRNS', bytes(6))]))
png_variant('ffmpeg-default.png', 'png-srgb-iccp.png',
            lambda c: insert_before(c, 'IDAT', [('sRGB', b'\x00'), ('iCCP', b'ICC Profile\x00\x00' + zlib.compress(b'not a real profile'))]))
png_variant('ffmpeg-default.png', 'png-keyword.png', lambda c: insert_before(c, 'IDAT', [('tEXt', b' Title\x00unwrapMedia')]))
png_variant('ffmpeg-default.png', 'png-itxt.png', lambda c: insert_before(c, 'IDAT', [('iTXt', b'Title\x00\x02\x00\x00\x00unwrapMedia')]))
png_variant('ffmpeg-default.png', 'png-text-ok.png', lambda c: insert_before(c, 'IDAT', [
    ('tEXt', b'Title\x00unwrapMedia'), ('zTXt', b'Comment\x00\x00' + zlib.compress(b'zTXt text')),
    ('iTXt', 'Description\x00\x00\x00ko\x00설명\x00유니코드 텍스트'.encode('utf-8')),
    ('iTXt', b'Software\x00\x01\x00\x00\x00' + zlib.compress(b'compressed iTXt'))]))
png_variant('ffmpeg-default.png', 'png-zlib-fcheck.png', lambda c: replace_data(c, 'IDAT', lambda d: set_bytes(d, 1, bytes([d[1] ^ 0x01]))))
png_variant('ffmpeg-default.png', 'png-fdict.png', lambda c: replace_data(c, 'IDAT', lambda d: set_bytes(d, 1, bytes([fdict_flg()]))))
png_variant('ffmpeg-default.png', 'png-bkgd-bits.png', lambda c: insert_before(c, 'IDAT', [('bKGD', struct.pack('>HHH', 0x0100, 0, 0))]))
png_variant('ffmpeg.apng', 'apng-seq.apng', lambda c: replace_data(c, 'fcTL', lambda d: set_bytes(d, 0, struct.pack('>I', 5)), nth=1))
png_variant('ffmpeg.apng', 'apng-actl.apng', lambda c: replace_data(c, 'acTL', lambda d: set_bytes(d, 0, struct.pack('>I', 2))))
png_variant('ffmpeg.apng', 'apng-region.apng', lambda c: replace_data(c, 'fcTL', lambda d: set_bytes(d, 12, struct.pack('>I', 60)), nth=1))
png_variant('ffmpeg.apng', 'apng-nofdat.apng', lambda c: [x for i, x in enumerate(c) if not (x[0] == 'fdAT' and i == max(j for j, y in enumerate(c) if y[0] == 'fdAT'))])


# ---- WebP --------------------------------------------------------------------------------------------------------
def riff_chunks(b, start, end):
    pos, out_ = start, []
    while pos + 8 <= end:
        t = b[pos:pos + 4]
        n = struct.unpack('<I', b[pos + 4:pos + 8])[0]
        out_.append((t, b[pos + 8:pos + 8 + n]))
        pos += 8 + n + (n & 1)
    return out_


def riff_chunk(t, data):
    return t + struct.pack('<I', len(data)) + data + (b'\x00' if len(data) & 1 else b'')


def webp_build(chunks):
    body = b'WEBP' + b''.join(riff_chunk(t, d) for t, d in chunks)
    return b'RIFF' + struct.pack('<I', len(body)) + body


def webp(name):
    b = read(name)
    return riff_chunks(b, 12, 8 + struct.unpack('<I', b[4:8])[0])


def webp_variant(src, name, edit):
    write(name, webp_build(edit(webp(src))))


def patch_chunk(chunks, t, fn, nth=0):
    return replace_data([(x.decode('latin1'), d) for x, d in chunks], t, fn, nth)


def enc(chunks):
    return [(t.encode('latin1') if isinstance(t, str) else t, d) for t, d in chunks]


dflt = read('cwebp-default.webp')
b = bytearray(dflt)
b[4:8] = struct.pack('<I', struct.unpack('<I', dflt[4:8])[0] + 2)
write('webp-riff-size.webp', b)
write('webp-trailing.webp', dflt + bytes(4))
alpha = read('cwebp-alpha.webp')
alph = alpha.index(b'ALPH')
alph_size = struct.unpack('<I', alpha[alph + 4:alph + 8])[0]
assert alph_size % 2 == 1
b = bytearray(alpha)
b[alph + 8 + alph_size] = 0xFF  # the ALPH pad byte
write('webp-pad.webp', b)
webp_variant('cwebp-default.webp', 'webp-simple-extra.webp', lambda c: c + [(b'EXIF', b'II*\x00\x08\x00\x00\x00\x00\x00\x00\x00\x00\x00')])
webp_variant('cwebp-alpha.webp', 'webp-flags-icc.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x20])))))
webp_variant('cwebp-alpha.webp', 'webp-flags-alpha.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] & ~0x10])))))
webp_variant('cwebp-alpha.webp', 'webp-vp8x-reserved.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x01])))))
webp_variant('cwebp-alpha.webp', 'webp-order.webp', lambda c: [c[0], c[2], c[1]])
webp_variant('cwebp-alpha.webp', 'webp-alph-rsv.webp', lambda c: enc(patch_chunk(c, 'ALPH', lambda d: set_bytes(d, 0, bytes([d[0] | 0xC0])))))
webp_variant('cwebp-lossless.webp', 'webp-vp8l-version.webp', lambda c: enc(patch_chunk(c, 'VP8L', lambda d: set_bytes(d, 4, bytes([d[4] | 0x20])))))
webp_variant('cwebp-default.webp', 'webp-vp8-start.webp', lambda c: enc(patch_chunk(c, 'VP8 ', lambda d: set_bytes(d, 3, b'\x9e'))))
webp_variant('cwebp-alpha.webp', 'webp-canvas.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 4, struct.pack('<I', 62)[:3]))))
webp_variant('img2webp-anim.webp', 'webp-anmf-noflag.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] & ~0x02])))))
webp_variant('cwebp-alpha.webp', 'webp-iccp-dup.webp',
             lambda c: enc(patch_chunk([c[0], (b'ICCP', b'icc1'), (b'ICCP', b'icc2')] + c[1:], 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x20])))))
lossless = webp('cwebp-lossless.webp')
webp_variant('cwebp-alpha.webp', 'webp-alph-vp8l.webp', lambda c: [c[0], c[1], lossless[0]])


def anmf_patch(d, frame_x):
    d = bytearray(d)
    d[0:3] = struct.pack('<I', frame_x)[:3]
    return d


webp_variant('gif2webp-anim.webp', 'webp-anmf-region.webp', lambda c: enc(patch_chunk(c, 'ANMF', lambda d: anmf_patch(d, 30), nth=1)))


# ---- GIF ---------------------------------------------------------------------------------------------------------
def gif_blocks(b):
    """(kind, start, end) of every block after the Logical Screen; kind = 'ext:XX', 'img' or 'trailer'."""
    f = b[10]
    pos = 13 + (3 * 2 ** ((f & 7) + 1) if f & 0x80 else 0)
    out_ = []
    while pos < len(b):
        c = b[pos]
        if c == 0x3B:
            out_.append(('trailer', pos, pos + 1))
            break
        start = pos
        if c == 0x21:
            kind = 'ext:%02X' % b[pos + 1]
            pos += 2
        else:
            pf = b[pos + 9]
            pos += 10 + (3 * 2 ** ((pf & 7) + 1) if pf & 0x80 else 0) + 1
            kind = 'img'
        while b[pos] != 0:
            pos += 1 + b[pos]
        pos += 1
        out_.append((kind, start, pos))
    return out_


def first(blocks, kind, nth=0):
    return [x for x in blocks if x[0] == kind][nth]


g = read('ffmpeg-default.gif')
gb = gif_blocks(g)
gce = first(gb, 'ext:F9')
img = first(gb, 'img')
app = first(gb, 'ext:FF')
write('gif-gce-size.gif', g[:gce[1] + 2] + b'\x05' + g[gce[1] + 3:gce[1] + 7] + b'\x00' + g[gce[1] + 7:])
write('gif-two-gce.gif', g[:gce[2]] + g[gce[1]:gce[2]] + g[gce[2]:])
b = bytearray(g); b[img[1] + 10] = 12; write('gif-lzw.gif', b)                 # LZW Minimum Code Size 8 -> 12
b = bytearray(g); b[img[1] + 12] |= 0x01; write('gif-clear.gif', b)            # first code 256 -> 257
write('gif-no-trailer.gif', g[:-1])
write('gif-trailing.gif', g + bytes(8))
b = bytearray(g); b[gce[1] + 3] |= 0xE0; write('gif-reserved.gif', b)          # GCE reserved 3 bits
b = bytearray(g); b[img[1] + 9] |= 0x03; write('gif-lct-size.gif', b)          # no LCT but size field 3
gct = 3 * 2 ** ((g[10] & 7) + 1)
write('gif-bgindex.gif', g[:10] + bytes([g[10] & 0x7F]) + g[11:13] + g[13 + gct:])  # drop GCT, keep index 31
write('gif-app-size.gif', g[:app[1] + 2] + b'\x0a' + g[app[1] + 3:app[1] + 13] + g[app[1] + 14:])
write('gif-badblock.gif', g[:gb[0][1]] + b'\x00' + g[gb[0][1]:])
a = read('ffmpeg-anim.gif')
ab = gif_blocks(a)
img2 = first(ab, 'img', 1)
b = bytearray(a); b[img2[1] + 1:img2[1] + 3] = struct.pack('<H', 60); write('gif-bounds.gif', b)  # frame 2 left 0 -> 60
s = read('sips.gif')
sb = gif_blocks(s)
sg = first(sb, 'ext:F9')
write('gif-89a-plain.gif', b'GIF89a' + s[6:sg[1]] + s[sg[2]:])
