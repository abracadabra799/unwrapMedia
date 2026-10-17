#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

def box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload

def full_box(box_type: bytes, version: int, flags: int, payload: bytes) -> bytes:
    vf = (version << 24) | (flags & 0xFFFFFF)
    return struct.pack(">I", len(payload) + 12) + box_type + struct.pack(">I", vf) + payload

def build_mp4(samples: list[bytes], apvc_payload: bytes) -> bytes:
    sample_count = len(samples)
    apvc_box = box(b"apvC", apvc_payload)

    entry_prefix = bytes(6) + struct.pack(">H", 1) + bytes(16) + struct.pack(">HH", 3840, 2160)
    entry_prefix += bytes.fromhex("00480000 00480000 00000000 0001 0000000000000000000000000000000000000000000000000000000000000000 0018 ffff".replace(" ", ""))
    apv1_entry = box(b"apv1", entry_prefix + apvc_box)

    stsd = full_box(b"stsd", 0, 0, struct.pack(">I", 1) + apv1_entry)
    stts = full_box(b"stts", 0, 0, struct.pack(">II", 1, sample_count) + struct.pack(">I", 1000))
    stsz = full_box(b"stsz", 0, 0, struct.pack(">II", 0, sample_count) + struct.pack(f">{sample_count}I", *[len(s) for s in samples]))
    stsc = full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))

    ftyp = box(b"ftyp", b"isom" + struct.pack(">I", 512) + b"isomiso2mp41")
    mvhd = full_box(b"mvhd", 0, 0, bytes(8) + struct.pack(">II", 1000, 1000 * sample_count) + bytes(80))
    tkhd = full_box(b"tkhd", 0, 1, bytes(8) + struct.pack(">I", 1) + bytes(4) + struct.pack(">I", 1000 * sample_count) + bytes(48) + struct.pack(">II", 3840 << 16, 2160 << 16))
    mdhd = full_box(b"mdhd", 0, 0, bytes(8) + struct.pack(">II", 1000, 1000 * sample_count) + bytes(4))
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
    for s in samples:
        chunk_offsets.append(cur)
        cur += len(s)

    stco = full_box(b"stco", 0, 0, struct.pack(">I", sample_count) + struct.pack(f">{sample_count}I", *chunk_offsets))
    stbl = box(b"stbl", stsd + stts + stsz + stsc + stco)
    minf = box(b"minf", vmhd + dinf + stbl)
    mdia = box(b"mdia", mdhd + hdlr + minf)
    trak = box(b"trak", tkhd + mdia)
    moov = box(b"moov", mvhd + trak)

    mdat_payload = b"".join(samples)
    mdat = box(b"mdat", mdat_payload)
    return ftyp + moov + mdat

def main():
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out_dir.mkdir(parents=True, exist_ok=True)

    # Real APV payload & Access Unit
    # Access unit prefix:
    # 00095f7c: 4-byte length
    # 61507631: 'aPv1'
    # 00095f26: pbu_size
    # 01000100: pbu_type=1 (primary), group_id=1, reserved_zero=0
    # 217b4000 0f000008 70220000 00000040 00020000 00000ab9 00140000 000006b3 00000216 000001dc 33333300 9ddd9073: frame_header
    frame_header = bytes.fromhex("217b40000f00000870220000000000400002000000000ab900140000000006b300000216000001dc333333009ddd9073")
    pbu_header = bytes.fromhex("01000100")
    pbu_payload = pbu_header + frame_header
    pbu = struct.pack(">I", len(pbu_payload)) + pbu_payload
    au_body = b"aPv1" + pbu
    au_sample = struct.pack(">I", len(au_body)) + au_body

    apvc_payload = bytes.fromhex("000000000101010101217b0200000f00000008702200")

    # 1. default-apv1.mp4 (8 samples)
    default_bytes = build_mp4([au_sample] * 8, apvc_payload)
    (out_dir / "default-apv1.mp4").write_bytes(default_bytes)

    # 2. bad-signature.mp4: first sample byte 4..7 altered
    bad_sig_sample = bytearray(au_sample)
    bad_sig_sample[4:8] = b"XXXX"
    (out_dir / "bad-signature.mp4").write_bytes(build_mp4([bytes(bad_sig_sample)] + [au_sample] * 7, apvc_payload))

    # 3. bad-pbu-reserved.mp4: PBU header byte 3 (reserved_zero_8bits, index 15 in AU) set to 0xFF
    bad_pbu_res_sample = bytearray(au_sample)
    bad_pbu_res_sample[15] = 0xFF
    (out_dir / "bad-pbu-reserved.mp4").write_bytes(build_mp4([bytes(bad_pbu_res_sample)] + [au_sample] * 7, apvc_payload))

    # 4. bad-band-reserved.mp4: byte 18 in AU: band_idc (bits 7..5) set to 7, reserved_zero_5bits (bits 4..0) set to 0x1F
    # In original frame_header: byte 2 is 0x40 -> band_idc = 2, reserved = 0
    # In AU: byte 16 is profile (0x21), byte 17 is level (0x7b), byte 18 is band_idc | reserved
    bad_band_sample = bytearray(au_sample)
    bad_band_sample[18] = 0xFF # band_idc=7, reserved=31
    (out_dir / "bad-band-reserved.mp4").write_bytes(build_mp4([bytes(bad_band_sample)] + [au_sample] * 7, apvc_payload))

    # 5. malformed-apvc.mp4: apvC truncated to 10 bytes
    (out_dir / "malformed-apvc.mp4").write_bytes(build_mp4([au_sample] * 8, apvc_payload[:10]))

    # 6. truncated-sample.mp4: stsz sample 0 size set to 6 bytes (less than AU header + signature + PBU)
    trunc_bytes = bytearray(default_bytes)
    stsz_pos = trunc_bytes.find(b"stsz")
    sample_sizes_start = stsz_pos + 16
    struct.pack_into(">I", trunc_bytes, sample_sizes_start, 6) # truncate sample 0 to 6 bytes
    (out_dir / "truncated-sample.mp4").write_bytes(trunc_bytes)

    # 7. many-signature-failures.mp4: 101 samples with bad signature for occurrence counting
    bad_101_samples = [bytes(bad_sig_sample)] * 101
    (out_dir / "many-signature-failures.mp4").write_bytes(build_mp4(bad_101_samples, apvc_payload))

    print(f"Generated APV fixtures in {out_dir}")

if __name__ == "__main__":
    main()
