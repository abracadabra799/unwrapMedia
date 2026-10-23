#!/usr/bin/env python3
"""Derives corrupted ISO-BMFF fixtures from an ffmpeg-default MP4 (run with `python3 -I`).

Usage: corrupt.py <v.mp4> <out-dir>

Boxes are located by walking the bytes (not by fixed offsets). Every edit that changes a box's length fixes the
size field of every enclosing box (moov > trak > mdia > minf > stbl ...). The source MP4 must have moov after mdat
(ffmpeg's default without +faststart), so growing moov never shifts sample data and chunk offsets stay valid.
Each variant targets exactly one rule of IsoBmffConformanceChecker (see IsoBmffFixtureTest).
"""
import os
import struct
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"dinf", b"edts", b"udta"}


class Box:
    def __init__(self, typ, offset, size, header, parents):
        self.type, self.offset, self.size, self.header, self.parents = typ, offset, size, header, parents
        self.children = []

    @property
    def payload(self):
        return self.offset + self.header

    @property
    def end(self):
        return self.offset + self.size


def walk(data, start, end, parents):
    boxes, pos = [], start
    while pos + 8 <= end:
        size, typ = struct.unpack(">I4s", data[pos:pos + 8])
        if size < 8:
            raise SystemExit("unsupported box size %d at 0x%X (fixtures must use plain 32-bit sizes)" % (size, pos))
        box = Box(typ, pos, size, 8, parents)
        if typ in CONTAINERS:
            box.children = walk(data, box.payload, box.end, parents + [box])
        boxes.append(box)
        pos += size
    if pos != end:
        raise SystemExit("box walk did not end at 0x%X (stopped at 0x%X)" % (end, pos))
    return boxes


def find_or_none(boxes, *path):
    for b in boxes:
        if b.type == path[0]:
            if len(path) == 1:
                return b
            hit = find_or_none(b.children, *path[1:])
            if hit is not None:
                return hit
    return None


def find(boxes, *path):
    """First box matching the type path (depth-first), e.g. find(top, b'moov', b'trak', b'tkhd')."""
    hit = find_or_none(boxes, *path)
    if hit is None:
        raise SystemExit("box path not found: %s" % b"/".join(path).decode())
    return hit


def put32(data, at, value):
    data[at:at + 4] = struct.pack(">I", value & 0xFFFFFFFF)


def get32(data, at):
    return struct.unpack(">I", data[at:at + 4])[0]


def insert(data, at, blob, enclosing):
    """Inserts blob at offset `at` and grows every enclosing box (outermost first is fine: offsets precede `at`)."""
    data[at:at] = blob
    for b in enclosing:
        put32(data, b.offset, get32(data, b.offset) + len(blob))


def chain(box):
    return box.parents + [box]


def main():
    src, out = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        base = f.read()
    top = walk(base, 0, len(base), [])
    types = [b.type for b in top]
    if types.index(b"moov") < types.index(b"mdat"):
        raise SystemExit("source must have moov after mdat (no +faststart)")

    def variant(name, edit):
        data = bytearray(base)
        edit(data, walk(bytes(data), 0, len(data), []))
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)

    def no_moov(data, t):
        m = find(t, b"moov")
        data[m.offset + 4:m.offset + 8] = b"free"

    def dup_mvhd(data, t):
        mvhd = find(t, b"moov", b"mvhd")
        insert(data, mvhd.end, bytes(data[mvhd.offset:mvhd.end]), mvhd.parents)

    def track_id_0(data, t):
        tkhd = find(t, b"moov", b"trak", b"tkhd")
        version = data[tkhd.payload]
        put32(data, tkhd.payload + (20 if version == 1 else 12), 0)

    def stts_mismatch(data, t):
        stts = find(t, b"moov", b"trak", b"mdia", b"minf", b"stbl", b"stts")
        if get32(data, stts.payload + 4) < 1:
            raise SystemExit("stts has no entries")
        at = stts.payload + 8  # first entry's sample_count
        put32(data, at, get32(data, at) - 1)

    def stsc_gap(data, t):
        stsc = find(t, b"moov", b"trak", b"mdia", b"minf", b"stbl", b"stsc")
        at = stsc.payload + 8  # first entry's first_chunk (must be 1)
        if get32(data, at) != 1:
            raise SystemExit("stsc first_chunk is not 1")
        put32(data, at, 2)

    def stco_past_eof(data, t):
        stco = find(t, b"moov", b"trak", b"mdia", b"minf", b"stbl", b"stco")
        put32(data, stco.payload + 8, len(data) + 0x1000)  # first chunk offset beyond end of file

    def vmhd_flags_0(data, t):
        vmhd = find(t, b"moov", b"trak", b"mdia", b"minf", b"vmhd")
        data[vmhd.payload + 1:vmhd.payload + 4] = b"\x00\x00\x00"

    def size0_nested(data, t):
        moov = find(t, b"moov")
        insert(data, moov.end, struct.pack(">I4s", 0, b"free"), chain(moov))

    def trailing_garbage(data, t):
        data.extend(b"\x0E" * 20)

    variant("no-moov.mp4", no_moov)
    variant("dup-mvhd.mp4", dup_mvhd)
    variant("track-id-0.mp4", track_id_0)
    variant("stts-mismatch.mp4", stts_mismatch)
    variant("stsc-gap.mp4", stsc_gap)
    variant("stco-past-eof.mp4", stco_past_eof)
    variant("vmhd-flags-0.mp4", vmhd_flags_0)
    variant("size0-nested.mp4", size0_nested)
    variant("trailing-garbage.mp4", trailing_garbage)


if __name__ == "__main__":
    main()
