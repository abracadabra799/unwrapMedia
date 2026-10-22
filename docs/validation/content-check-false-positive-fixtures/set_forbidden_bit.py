"""Copy an MP4 and set forbidden_zero_bit in the first NAL of the video track's first sample."""
import struct, sys

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, "rb").read())


def children(start, end):
    p = start
    while p + 8 <= end:
        size, typ = struct.unpack(">I4s", d[p:p + 8])
        yield typ.decode("latin1"), p, size
        p += size


def child(start, end, name):
    return next((p, s) for t, p, s in children(start, end) if t == name)


moov, ms = child(0, len(d), "moov")
for t, p, s in children(moov + 8, moov + ms):
    if t != "trak":
        continue
    mdia, mds = child(p + 8, p + s, "mdia")
    hdlr, _ = child(mdia + 8, mdia + mds, "hdlr")
    if d[hdlr + 16:hdlr + 20] != b"vide":
        continue
    minf, mis = child(mdia + 8, mdia + mds, "minf")
    stbl, sts = child(minf + 8, minf + mis, "stbl")
    stco, _ = child(stbl + 8, stbl + sts, "stco")
    first = struct.unpack(">I", d[stco + 16:stco + 20])[0]
    d[first + 4] |= 0x80  # 4-byte NAL length prefix, then the NAL header
    break
else:
    sys.exit("no video track")
open(dst, "wb").write(bytes(d))
