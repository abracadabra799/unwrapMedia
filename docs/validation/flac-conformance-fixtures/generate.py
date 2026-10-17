#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

def box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload

def full_box(box_type: bytes, version: int, flags: int, payload: bytes) -> bytes:
    vf = (version << 24) | (flags & 0xFFFFFF)
    return struct.pack(">I", len(payload) + 12) + box_type + struct.pack(">I", vf) + payload

def crc8(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc

def crc16(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= (b << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x8005) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc

def build_dfla(
    version: int = 0,
    flags: int = 0,
    streaminfo_len: int = 34,
    min_blocksize: int = 4608,
    max_blocksize: int = 4608,
    min_framesize: int = 32,
    max_framesize: int = 1024,
    sample_rate: int = 44100,
    channels: int = 1,
    bits_per_sample: int = 16,
    total_samples: int = 4608 * 8,
) -> bytes:
    # 4-byte metadata block header: is_last (1 bit) = 1, block_type (7 bits) = 0, length (24 bits)
    hdr = bytes([0x80 | 0]) + struct.pack(">I", streaminfo_len)[1:]

    # 34-byte STREAMINFO:
    # min_blocksize (2B), max_blocksize (2B), min_framesize (3B), max_framesize (3B)
    b_sizes = struct.pack(">HH", min_blocksize, max_blocksize)
    f_sizes = struct.pack(">I", min_framesize)[1:] + struct.pack(">I", max_framesize)[1:]

    # sample_rate (20b), channels-1 (3b), bits_per_sample-1 (5b), total_samples (36b)
    ch_val = (channels - 1) & 0x07
    bps_val = (bits_per_sample - 1) & 0x1F
    q_val = ((sample_rate & 0xFFFFF) << 44) | (ch_val << 41) | (bps_val << 36) | (total_samples & 0xFFFFFFFFF)
    audio_info = struct.pack(">Q", q_val)

    # 16-byte MD5 signature
    md5 = bytes(16)

    streaminfo_payload = b_sizes + f_sizes + audio_info + md5
    if len(streaminfo_payload) > streaminfo_len:
        streaminfo_payload = streaminfo_payload[:streaminfo_len]
    elif len(streaminfo_payload) < streaminfo_len:
        streaminfo_payload = streaminfo_payload + bytes(streaminfo_len - len(streaminfo_payload))

    return full_box(b"dfLa", version, flags, hdr + streaminfo_payload)

def build_flac_frame(
    frame_index: int = 0,
    corrupt_sync: bool = False,
    corrupt_crc8: bool = False,
    corrupt_crc16: bool = False,
) -> bytes:
    # 14-bit syncword: 0x3FFE -> 0xFF 0xF8 (with bit 14=0, bit 15=0)
    b0 = 0xFF
    b1 = 0x78 if corrupt_sync else 0xF8
    # blocksize_code = 4 (4608), samplerate_code = 9 (44.1kHz)
    b2 = (4 << 4) | 9
    # channel_assignment = 0 (mono), bits_per_sample_code = 4 (16-bit), reserved = 0
    b3 = (0 << 4) | (4 << 1) | 0
    # UTF-8 encoded frame number (0..127 is 1 byte)
    frame_num_byte = frame_index & 0x7F

    hdr_bytes = bytes([b0, b1, b2, b3, frame_num_byte])
    c8 = crc8(hdr_bytes)
    if corrupt_crc8:
        c8 ^= 0xFF

    # 24 bytes audio payload (e.g. constant subframe + padding)
    subframe = bytes([0x00, 0x00, 0x00]) + bytes(21)
    frame_body = hdr_bytes + bytes([c8]) + subframe

    c16 = crc16(frame_body)
    if corrupt_crc16:
        c16 ^= 0xFFFF

    return frame_body + struct.pack(">H", c16)

def build_mp4(samples: list[bytes], dfla_box: bytes, sample_rate: int = 44100, channels: int = 1, sample_bits: int = 16) -> bytes:
    sample_count = len(samples)

    # AudioSampleEntry fixed 28-byte header:
    # 6 bytes reserved, data_ref_idx (2B), 8 bytes reserved (2 u32),
    # channelcount (2B), samplesize (2B), pre_defined (2B), reserved (2B), samplerate (4B, 16.16 fp)
    entry_prefix = bytes(6) + struct.pack(">H", 1) + bytes(8) + struct.pack(">HHHH", channels, sample_bits, 0, 0) + struct.pack(">I", sample_rate << 16)
    flac_entry = box(b"fLaC", entry_prefix + dfla_box)

    stsd = full_box(b"stsd", 0, 0, struct.pack(">I", 1) + flac_entry)
    stts = full_box(b"stts", 0, 0, struct.pack(">II", 1, sample_count) + struct.pack(">I", 4608))
    stsz = full_box(b"stsz", 0, 0, struct.pack(">II", 0, sample_count) + struct.pack(f">{sample_count}I", *[len(s) for s in samples]))
    stsc = full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))

    ftyp = box(b"ftyp", b"mp42" + struct.pack(">I", 0) + b"isommp42")
    duration = 4608 * sample_count
    mvhd = full_box(b"mvhd", 0, 0, bytes(8) + struct.pack(">II", sample_rate, duration) + bytes(80))
    tkhd = full_box(b"tkhd", 0, 1, bytes(8) + struct.pack(">I", 1) + bytes(4) + struct.pack(">I", duration) + bytes(36) + struct.pack(">H", 0x0100) + bytes(18) + bytes(8))
    mdhd = full_box(b"mdhd", 0, 0, bytes(8) + struct.pack(">II", sample_rate, duration) + bytes(4))
    hdlr = full_box(b"hdlr", 0, 0, struct.pack(">I", 0) + b"soun" + bytes(12) + b"SoundHandler\0")
    smhd = full_box(b"smhd", 0, 0, bytes(4))
    dref = full_box(b"dref", 0, 0, struct.pack(">I", 1) + full_box(b"url ", 0, 1, b""))
    dinf = box(b"dinf", dref)

    stco_dummy = full_box(b"stco", 0, 0, struct.pack(">I", sample_count) + bytes(sample_count * 4))
    stbl_dummy = box(b"stbl", stsd + stts + stsz + stsc + stco_dummy)
    minf_dummy = box(b"minf", smhd + dinf + stbl_dummy)
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
    minf = box(b"minf", smhd + dinf + stbl)
    mdia = box(b"mdia", mdhd + hdlr + minf)
    trak = box(b"trak", tkhd + mdia)
    moov = box(b"moov", mvhd + trak)

    mdat_payload = b"".join(samples)
    mdat = box(b"mdat", mdat_payload)
    return ftyp + moov + mdat

def main():
    root = Path(__file__).resolve().parent.parent.parent.parent
    out_dir = root / "app" / "src" / "test" / "resources" / "flac-conformance"
    out_dir.mkdir(parents=True, exist_ok=True)

    default_samples = [build_flac_frame(i) for i in range(8)]
    default_dfla = build_dfla(version=0, flags=0, streaminfo_len=34, channels=1, bits_per_sample=16, sample_rate=44100)

    # 1. default-flac.mp4 (valid mono FLAC 44100Hz 16-bit)
    (out_dir / "default-flac.mp4").write_bytes(build_mp4(default_samples, default_dfla, channels=1, sample_bits=16))

    # 2. bad-dfla-version.mp4 (dfLa version = 1, invalid for ISO-BMFF)
    bad_ver_dfla = build_dfla(version=1)
    (out_dir / "bad-dfla-version.mp4").write_bytes(build_mp4(default_samples, bad_ver_dfla, channels=1))

    # 3. bad-streaminfo-size.mp4 (STREAMINFO declared size = 32, must be exactly 34)
    bad_size_dfla = build_dfla(streaminfo_len=32)
    (out_dir / "bad-streaminfo-size.mp4").write_bytes(build_mp4(default_samples, bad_size_dfla, channels=1))

    # 4. bad-config-match.mp4 (sample entry declares 2 channels, but STREAMINFO declares 1)
    (out_dir / "bad-config-match.mp4").write_bytes(build_mp4(default_samples, default_dfla, channels=2))

    # 5. bad-frame-sync.mp4 (sample 0 syncword corrupted)
    bad_sync_samples = [build_flac_frame(0, corrupt_sync=True)] + [build_flac_frame(i) for i in range(1, 8)]
    (out_dir / "bad-frame-sync.mp4").write_bytes(build_mp4(bad_sync_samples, default_dfla, channels=1))

    # 6. bad-frame-crc8.mp4 (sample 0 frame header CRC-8 corrupted)
    bad_crc8_samples = [build_flac_frame(0, corrupt_crc8=True)] + [build_flac_frame(i) for i in range(1, 8)]
    (out_dir / "bad-frame-crc8.mp4").write_bytes(build_mp4(bad_crc8_samples, default_dfla, channels=1))

    # 7. bad-frame-crc16.mp4 (sample 0 frame CRC-16 corrupted)
    bad_crc16_samples = [build_flac_frame(0, corrupt_crc16=True)] + [build_flac_frame(i) for i in range(1, 8)]
    (out_dir / "bad-frame-crc16.mp4").write_bytes(build_mp4(bad_crc16_samples, default_dfla, channels=1))

    # 8. truncated-sample.mp4 (sample data truncated in mdat)
    truncated_mp4 = bytearray(build_mp4(default_samples, default_dfla, channels=1))
    (out_dir / "truncated-sample.mp4").write_bytes(bytes(truncated_mp4[:-20]))

    # 9. many-sync-failures.mp4 (101 samples with corrupted syncword)
    many_samples = [build_flac_frame(i, corrupt_sync=True) for i in range(101)]
    (out_dir / "many-sync-failures.mp4").write_bytes(build_mp4(many_samples, default_dfla, channels=1))

    print(f"Generated 9 FLAC fixtures in {out_dir}")

if __name__ == "__main__":
    main()
