#!/usr/bin/env python3
"""Derives corrupted HEIF / AVIF fixtures from the sips HEIC and the ffmpeg AVIF (run with `python3 -I`).

Usage: corrupt.py <sips.heic> <still.avif> <out-dir>

Boxes are located by walking the bytes (never fixed offsets). Every edit that changes a box's length fixes the size
field of every enclosing box (meta > iprp > ipco/ipma) and, because both sources keep `meta` before `mdat`, shifts
every file-relative (construction_method 0) `iloc` offset that lies after the edit, so the coded data stays
addressed. Each variant targets exactly one rule of HeifConformanceChecker (see HeifFixtureTest):

  no-ispe.heic        ipma association of the primary item to `ispe` removed     -> heif.props.ispe FAIL
  hidden-primary.heic primary item's infe flags |= 1 (hidden)                    -> heif.items.primary FAIL
  ipma-index.heic     one association index (clli) set to ipco count + 1        -> heif.ipma.refs FAIL
  iloc-past-eof.heic  primary item's first extent offset set past the file end  -> heif.iloc.extents FAIL
  no-miaf.avif        compatible brand `miaf` replaced by `xxxx`                -> avif.ftyp.miaf FAIL
  no-av1c.avif        ipma association of the primary item to `av1C` removed    -> heif.props.codec_config FAIL
  pasp-2-1.avif       `pasp` 2:1 appended to ipco and associated with the primary -> avif.props.pasp FAIL
"""
import os
import struct
import sys

# Container boxes and the bytes between their header and their first child (FullBox version/flags for meta).
CONTAINERS = {b"meta": 4, b"iprp": 0, b"ipco": 0, b"dinf": 0}


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
        header = 8
        if size == 1:  # 64-bit largesize (sips writes mdat this way)
            size, header = struct.unpack(">Q", data[pos + 8:pos + 16])[0], 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise SystemExit("bad box size %d at 0x%X" % (size, pos))
        box = Box(typ, pos, size, header, parents)
        if typ in CONTAINERS:
            box.children = walk(data, box.payload + CONTAINERS[typ], box.end, parents + [box])
        boxes.append(box)
        pos += size
    if pos != end:
        raise SystemExit("box walk did not end at 0x%X (stopped at 0x%X)" % (end, pos))
    return boxes


def find(boxes, *path):
    for b in boxes:
        if b.type == path[0]:
            if len(path) == 1:
                return b
            hit = find(b.children, *path[1:])
            if hit is not None:
                return hit
    raise SystemExit("box path not found: %s" % b"/".join(path).decode())


def put32(data, at, value):
    data[at:at + 4] = struct.pack(">I", value & 0xFFFFFFFF)


def get32(data, at):
    return struct.unpack(">I", data[at:at + 4])[0]


def getn(data, at, n):
    return int.from_bytes(data[at:at + n], "big") if n else 0


def putn(data, at, n, value):
    data[at:at + n] = value.to_bytes(n, "big")


def tree(data):
    return walk(data, 0, len(data), [])


def primary_id(data, top):
    pitm = find(top, b"meta", b"pitm")
    v = data[pitm.payload]
    return struct.unpack(">H", data[pitm.payload + 4:pitm.payload + 6])[0] if v == 0 else get32(data, pitm.payload + 4)


def iloc_fields(data, top):
    """Yields (item_id, method, base_at, base_size, [(offset_at, offset_size)]) for every iloc entry."""
    iloc = find(top, b"meta", b"iloc")
    p = iloc.payload
    v = data[p]
    p += 4
    off_size, len_size = data[p] >> 4, data[p] & 15
    base_size, index_size = data[p + 1] >> 4, (data[p + 1] & 15) if v in (1, 2) else 0
    p += 2
    count = struct.unpack(">H", data[p:p + 2])[0] if v < 2 else get32(data, p)
    p += 2 if v < 2 else 4
    out = []
    for _ in range(count):
        item = struct.unpack(">H", data[p:p + 2])[0] if v < 2 else get32(data, p)
        p += 2 if v < 2 else 4
        method = 0
        if v in (1, 2):
            method = data[p + 1] & 15
            p += 2
        p += 2  # data_reference_index
        base_at = p
        p += base_size
        extents = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        offsets = []
        for _ in range(extents):
            p += index_size
            offsets.append((p, off_size))
            p += off_size + len_size
        out.append((item, method, base_at, base_size, offsets))
    return out


def shift_iloc(data, top, at, delta):
    """Shifts every method-0 location at or after file offset `at` by `delta` (tree read before the edit)."""
    for _, method, base_at, base_size, offsets in iloc_fields(data, top):
        if method != 0:
            continue
        base = getn(data, base_at, base_size)
        if base_size and base >= at:
            putn(data, base_at, base_size, base + delta)
            continue
        for o_at, o_size in offsets:
            if base + getn(data, o_at, o_size) >= at:
                putn(data, o_at, o_size, getn(data, o_at, o_size) + delta)


def resize(data, top, at, removed, blob, enclosing):
    """Replaces `removed` bytes at `at` with `blob`, growing/shrinking the enclosing boxes and shifting iloc."""
    delta = len(blob) - removed
    shift_iloc(data, top, at + removed, delta)  # iloc lies before mdat; its own offsets don't move
    for b in enclosing:
        if b.header != 8:
            raise SystemExit("enclosing box %s has a largesize header" % b.type.decode())
        put32(data, b.offset, get32(data, b.offset) + delta)
    data[at:at + removed] = blob


def ipco_types(data, top):
    return [b.type for b in find(top, b"meta", b"iprp", b"ipco").children]


def ipma_entries(data, top):
    """[(item_id, [(assoc_at, assoc_size, essential, index)])] of the first ipma."""
    ipma = find(top, b"meta", b"iprp", b"ipma")
    p = ipma.payload
    v, flags = data[p], getn(data, p + 1, 3)
    p += 4
    count = get32(data, p)
    p += 4
    out = []
    for _ in range(count):
        item = struct.unpack(">H", data[p:p + 2])[0] if v < 1 else get32(data, p)
        p += 2 if v < 1 else 4
        n = data[p]
        count_at = p
        p += 1
        assocs = []
        for _ in range(n):
            if flags & 1:
                raw, size = struct.unpack(">H", data[p:p + 2])[0], 2
                assocs.append((p, size, raw >> 15, raw & 0x7FFF))
            else:
                raw, size = data[p], 1
                assocs.append((p, size, raw >> 7, raw & 0x7F))
            p += size
        out.append((item, count_at, assocs))
    return ipma, out


def drop_association(data, prop_type):
    top = tree(data)
    types = ipco_types(data, top)
    index = types.index(prop_type) + 1
    pid = primary_id(data, top)
    ipma, entries = ipma_entries(data, top)
    item, count_at, assocs = next(e for e in entries if e[0] == pid)
    at, size, _, _ = next(a for a in assocs if a[3] == index)
    data[count_at] -= 1
    resize(data, top, at, size, b"", [ipma] + ipma.parents)


def set_association_index(data, prop_type, new_index):
    top = tree(data)
    index = ipco_types(data, top).index(prop_type) + 1
    pid = primary_id(data, top)
    _, entries = ipma_entries(data, top)
    _, _, assocs = next(e for e in entries if e[0] == pid)
    at, size, essential, _ = next(a for a in assocs if a[3] == index)
    if size == 1:
        data[at] = (essential << 7) | new_index
    else:
        data[at:at + 2] = struct.pack(">H", (essential << 15) | new_index)


def hide_primary(data):
    top = tree(data)
    pid = primary_id(data, top)
    iinf = find(top, b"meta", b"iinf")
    p = iinf.payload
    p += 4 + (2 if data[p] == 0 else 4)
    while p < iinf.end:
        size, typ = struct.unpack(">I4s", data[p:p + 8])
        v = data[p + 8]
        item = struct.unpack(">H", data[p + 12:p + 14])[0] if v == 2 else get32(data, p + 12)
        if typ == b"infe" and item == pid:
            data[p + 11] |= 1
            return
        p += size
    raise SystemExit("primary infe not found")


def iloc_past_eof(data):
    top = tree(data)
    pid = primary_id(data, top)
    _, method, base_at, base_size, offsets = next(e for e in iloc_fields(data, top) if e[0] == pid)
    if method != 0 or not offsets:
        raise SystemExit("primary item is not a file-addressed item")
    o_at, o_size = offsets[0]
    putn(data, o_at, o_size, len(data) + 100 - getn(data, base_at, base_size))


def rename_brand(data, old, new):
    top = tree(data)
    ftyp = find(top, b"ftyp")
    p = ftyp.payload + 8
    while p < ftyp.end:
        if data[p:p + 4] == old:
            data[p:p + 4] = new
            return
        p += 4
    raise SystemExit("brand %s not found" % old.decode())


def add_pasp(data, h, v):
    top = tree(data)
    ipco = find(top, b"meta", b"iprp", b"ipco")
    pasp = struct.pack(">I4sII", 16, b"pasp", h, v)
    resize(data, top, ipco.end, 0, pasp, [ipco] + ipco.parents)
    top = tree(data)
    index = len(ipco_types(data, top))
    pid = primary_id(data, top)
    ipma, entries = ipma_entries(data, top)
    _, count_at, assocs = next(e for e in entries if e[0] == pid)
    flags = getn(data, ipma.payload + 1, 3)
    blob = struct.pack(">H", index) if flags & 1 else bytes([index])
    end = assocs[-1][0] + assocs[-1][1]
    data[count_at] += 1
    resize(data, top, end, 0, blob, [ipma] + ipma.parents)


def main():
    heic_src, avif_src, out = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(heic_src, "rb") as f:
        heic = f.read()
    with open(avif_src, "rb") as f:
        avif = f.read()

    def variant(src, name, edit):
        data = bytearray(src)
        edit(data)
        tree(data)  # the result must still walk cleanly
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)

    variant(heic, "no-ispe.heic", lambda d: drop_association(d, b"ispe"))
    variant(heic, "hidden-primary.heic", hide_primary)
    variant(heic, "ipma-index.heic", lambda d: set_association_index(d, b"clli", len(ipco_types(d, tree(d))) + 1))
    variant(heic, "iloc-past-eof.heic", iloc_past_eof)
    variant(avif, "no-miaf.avif", lambda d: rename_brand(d, b"miaf", b"xxxx"))
    variant(avif, "no-av1c.avif", lambda d: drop_association(d, b"av1C"))
    variant(avif, "pasp-2-1.avif", lambda d: add_pasp(d, 2, 1))


if __name__ == "__main__":
    main()
