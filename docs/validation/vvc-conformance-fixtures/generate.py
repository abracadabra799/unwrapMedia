#!/usr/bin/env python3
"""
Generates minimal VVC (H.266) conformance test fixtures for unwrapMedia.
ISO/IEC 14496-15 (Carriage of VVC in ISOBMFF) and ITU-T H.266.
"""

import struct
from pathlib import Path

def box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload

def full_box(box_type: bytes, version: int, flags: int, payload: bytes) -> bytes:
    hdr = struct.pack(">I", len(payload) + 12) + box_type + struct.pack(">B", version) + struct.pack(">I", flags)[1:]
    return hdr + payload

def build_vvc_nal(
    nal_type: int,
    payload: bytes = b"",
    temporal_id_plus1: int = 1,
    layer_id: int = 0,
    forbidden_bit: int = 0,
    reserved_bit: int = 0,
) -> bytes:
    b0 = ((forbidden_bit & 1) << 7) | ((reserved_bit & 1) << 6) | (layer_id & 0x3F)
    b1 = ((nal_type & 0x1F) << 3) | (temporal_id_plus1 & 0x07)
    return bytes([b0, b1]) + payload

def build_vvcc(
    version: int = 0,
    flags: int = 0,
    length_size_minus_one: int = 3,
    ptl_present: bool = True,
    width: int = 1920,
    height: int = 1080,
    corrupt_box: bool = False,
) -> bytes:
    if corrupt_box:
        # Invalid truncated or broken version
        return full_box(b"vvcC", 1, 0, bytes(4))

    b0 = (0x1F << 3) | ((length_size_minus_one & 3) << 1) | (1 if ptl_present else 0)
    record = bytearray([b0])

    if ptl_present:
        # ols_idx (9b)=0, num_sublayers (3b)=1, constant_frame_rate (2b)=0, chroma_format_idc (2b)=1 (4:2:0)
        # val16 = (0 << 7) | (1 << 4) | (0 << 2) | 1 = 0x0011
        record.extend(struct.pack(">H", 0x0011))
        # bit_depth_minus8 (3b)=0 (8-bit), reserved (5b)=0x1F -> 0x1F
        record.append(0x1F)

        # VvcPTLRecord(num_sublayers=1):
        # reserved (2b)=0, num_bytes_constraint_info (6b)=1 -> 0x01
        record.append(0x01)
        # general_profile_idc (7b)=1 (Main 10), general_tier_flag (1b)=0 -> (1 << 1) | 0 = 0x02
        record.append(0x02)
        # general_level_idc (8b)=51 (Level 3.1)
        record.append(51)
        # ptl_frame_only_constraint_flag (1b)=1, ptl_multi_layer_enabled_flag (1b)=0, constraint info (6b)=0 -> 0x80
        record.append(0x80)
        # ptl_num_sub_profiles (8b)=0
        record.append(0)

        # max_picture_width (16b), max_picture_height (16b), avg_frame_rate (16b)
        record.extend(struct.pack(">HHH", width, height, 0))

    # num_of_arrays = 0 (no embedded parameter set arrays for minimal fixture)
    record.append(0)

    return full_box(b"vvcC", version, flags, bytes(record))

def build_mp4(
    samples: list[bytes],
    vvcc_box: bytes,
    entry_tag: bytes = b"vvc1",
    width: int = 1920,
    height: int = 1080,
    length_size: int = 4,
) -> bytes:
    sample_count = len(samples)

    # VisualSampleEntry fixed 78-byte prefix (6 reserved, data_ref_idx=1, pre_defined/reserved=16B, width=2B, height=2B, horiz/vert_res=8B, reserved=4B, frame_count=2B, compressor_name=32B, depth=2B, pre_defined=2B)
    entry_prefix = (
        bytes(6)
        + struct.pack(">H", 1)
        + bytes(16)
        + struct.pack(">HH", width, height)
        + struct.pack(">II", 0x00480000, 0x00480000)
        + bytes(4)
        + struct.pack(">H", 1)
        + bytes(32)
        + struct.pack(">h", 0x0018)
        + struct.pack(">h", -1)
    )
    vvc_entry = box(entry_tag, entry_prefix + vvcc_box)

    stsd = full_box(b"stsd", 0, 0, struct.pack(">I", 1) + vvc_entry)
    stts = full_box(b"stts", 0, 0, struct.pack(">II", 1, sample_count) + struct.pack(">I", 1000))

    # Frame payloads with length prefix
    framed_samples = []
    for s in samples:
        framed = struct.pack(f">{length_size}s", struct.pack(">I", len(s))[-length_size:]) + s
        framed_samples.append(framed)

    stsz = full_box(b"stsz", 0, 0, struct.pack(">II", 0, sample_count) + struct.pack(f">{sample_count}I", *[len(s) for s in framed_samples]))
    stsc = full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))

    ftyp = box(b"ftyp", b"mp42" + struct.pack(">I", 0) + b"isommp42")
    duration = 1000 * sample_count
    mvhd = full_box(b"mvhd", 0, 0, bytes(8) + struct.pack(">II", 1000, duration) + bytes(80))
    tkhd = full_box(b"tkhd", 0, 1, bytes(8) + struct.pack(">I", 1) + bytes(4) + struct.pack(">I", duration) + bytes(36) + struct.pack(">H", 0x0100) + bytes(18) + bytes(8))
    mdhd = full_box(b"mdhd", 0, 0, bytes(8) + struct.pack(">II", 1000, duration) + bytes(4))
    hdlr = full_box(b"hdlr", 0, 0, struct.pack(">I", 0) + b"vide" + bytes(12) + b"VideoHandler\0")
    vmhd = full_box(b"vmhd", 0, 1, bytes(8))
    dref = full_box(b"dref", 0, 0, struct.pack(">I", 1) + full_box(b"url ", 0, 1, b""))
    dinf = box(b"dinf", dref)

    stco_dummy = full_box(b"stco", 0, 0, struct.pack(">I", sample_count) + bytes(sample_count * 4))
    stbl_dummy = box(b"stbl", stsd + stts + stsz + stsc + stco_dummy)
    minf_dummy = box(b"minf", vmhd + dinf + stbl_dummy)
    mdia_dummy = box(b"mdia", mdhd + hdlr + minf_dummy)
    trak_dummy = box(b"trak", tkhd + mdia_dummy)
    moov_dummy = box(b"moov", mvhd + trak_dummy)

    mdat_offset = len(ftyp) + len(moov_dummy)
    chunk_offsets = []
    cur = mdat_offset + 8
    for s in framed_samples:
        chunk_offsets.append(cur)
        cur += len(s)

    stco = full_box(b"stco", 0, 0, struct.pack(">I", sample_count) + struct.pack(f">{sample_count}I", *chunk_offsets))
    stbl = box(b"stbl", stsd + stts + stsz + stsc + stco)
    minf = box(b"minf", vmhd + dinf + stbl)
    mdia = box(b"mdia", mdhd + hdlr + minf)
    trak = box(b"trak", tkhd + mdia)
    moov = box(b"moov", mvhd + trak)

    mdat_payload = b"".join(framed_samples)
    mdat = box(b"mdat", mdat_payload)
    return ftyp + moov + mdat

def main():
    root = Path(__file__).resolve().parent.parent.parent.parent
    out_dir = root / "app" / "src" / "test" / "resources" / "vvc-conformance"
    out_dir.mkdir(parents=True, exist_ok=True)

    # NAL types in VVC:
    # 0 = TRAIL_NUT
    # 7 = IDR_W_RADL
    # 8 = IDR_N_LP
    valid_idr = build_vvc_nal(7, payload=bytes(16), temporal_id_plus1=1)
    valid_trail = build_vvc_nal(0, payload=bytes(16), temporal_id_plus1=1)
    default_samples = [valid_idr] + [valid_trail] * 7
    valid_vvcc = build_vvcc()

    # 1. default-vvc.mp4 (valid 8 samples VVC video, vvc1)
    (out_dir / "default-vvc.mp4").write_bytes(build_mp4(default_samples, valid_vvcc))

    # 2. bad-vvcc-box.mp4 (corrupted vvcC FullBox version=1)
    bad_vvcc = build_vvcc(corrupt_box=True)
    (out_dir / "bad-vvcc-box.mp4").write_bytes(build_mp4(default_samples, bad_vvcc))

    # 3. bad-forbidden-bit.mp4 (sample 0 NAL forbidden_zero_bit=1)
    bad_forbidden_nal = build_vvc_nal(7, payload=bytes(16), forbidden_bit=1)
    (out_dir / "bad-forbidden-bit.mp4").write_bytes(build_mp4([bad_forbidden_nal] + [valid_trail] * 7, valid_vvcc))

    # 4. bad-reserved-bit.mp4 (sample 0 NAL nuh_reserved_zero_bit=1)
    bad_reserved_nal = build_vvc_nal(7, payload=bytes(16), reserved_bit=1)
    (out_dir / "bad-reserved-bit.mp4").write_bytes(build_mp4([bad_reserved_nal] + [valid_trail] * 7, valid_vvcc))

    # 5. bad-temporal-id.mp4 (sample 0 NAL nuh_temporal_id_plus1=0, forbidden)
    bad_temporal_nal = build_vvc_nal(7, payload=bytes(16), temporal_id_plus1=0)
    (out_dir / "bad-temporal-id.mp4").write_bytes(build_mp4([bad_temporal_nal] + [valid_trail] * 7, valid_vvcc))

    # 6. truncated-sample.mp4 (last sample truncated in mdat)
    truncated_mp4 = bytearray(build_mp4(default_samples, valid_vvcc))
    (out_dir / "truncated-sample.mp4").write_bytes(bytes(truncated_mp4[:-10]))

    # 7. many-nal-failures.mp4 (101 samples with corrupted forbidden_zero_bit)
    many_samples = [build_vvc_nal(0, payload=bytes(16), forbidden_bit=1) for _ in range(101)]
    (out_dir / "many-nal-failures.mp4").write_bytes(build_mp4(many_samples, valid_vvcc))

    print(f"Generated 7 VVC fixtures in {out_dir}")

if __name__ == "__main__":
    main()
