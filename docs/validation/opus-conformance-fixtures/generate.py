#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

def box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload

def full_box(box_type: bytes, version: int, flags: int, payload: bytes) -> bytes:
    vf = (version << 24) | (flags & 0xFFFFFF)
    return struct.pack(">I", len(payload) + 12) + box_type + struct.pack(">I", vf) + payload

def build_dops(
    version: int = 0,
    channel_count: int = 1,
    pre_skip: int = 312,
    sample_rate: int = 48000,
    gain: int = 0,
    mapping_family: int = 0,
    stream_count: int = 1,
    coupled_count: int = 0,
    channel_mapping: list[int] = None,
) -> bytes:
    payload = struct.pack(">BBHIhB", version, channel_count, pre_skip, sample_rate, gain, mapping_family)
    if mapping_family != 0:
        payload += struct.pack(">BB", stream_count, coupled_count)
        if channel_mapping is None:
            channel_mapping = list(range(channel_count))
        payload += bytes(channel_mapping)
    return box(b"dOps", payload)

def sample_celt_mono(config: int = 31) -> bytes:
    # config 31: CELT-only FB 20ms, s=0 (mono), c=0 (1 frame) -> 0xF8
    b0 = ((config & 0x1F) << 3) | 0x00
    return bytes([b0]) + bytes(31)

def sample_celt_stereo(config: int = 31) -> bytes:
    # config 31: CELT-only FB 20ms, s=1 (stereo), c=0 (1 frame) -> 0xFC
    b0 = ((config & 0x1F) << 3) | 0x04
    return bytes([b0]) + bytes(31)

def sample_code3(config: int = 31, v: int = 0, p: int = 0, m: int = 2) -> bytes:
    # config 31, s=0, c=3 (Code 3 packet) -> 0xFB
    b0 = ((config & 0x1F) << 3) | 0x03
    # byte 1: v (bit 7), p (bit 6), M (bits 5..0)
    b1 = ((v & 1) << 7) | ((p & 1) << 6) | (m & 0x3F)
    return bytes([b0, b1]) + bytes(30)

def build_mp4(samples: list[bytes], dops_box: bytes, sample_rate: int = 48000, channels: int = 1) -> bytes:
    sample_count = len(samples)

    # AudioSampleEntry fixed 28-byte header:
    # 6 bytes reserved, data_ref_idx (2B), 8 bytes reserved (2 u32),
    # channelcount (2B), samplesize (2B), pre_defined (2B), reserved (2B), samplerate (4B, 16.16 fp)
    entry_prefix = bytes(6) + struct.pack(">H", 1) + bytes(8) + struct.pack(">HHHH", channels, 16, 0, 0) + struct.pack(">I", sample_rate << 16)
    opus_entry = box(b"Opus", entry_prefix + dops_box)

    stsd = full_box(b"stsd", 0, 0, struct.pack(">I", 1) + opus_entry)
    stts = full_box(b"stts", 0, 0, struct.pack(">II", 1, sample_count) + struct.pack(">I", 960))
    stsz = full_box(b"stsz", 0, 0, struct.pack(">II", 0, sample_count) + struct.pack(f">{sample_count}I", *[len(s) for s in samples]))
    stsc = full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))

    ftyp = box(b"ftyp", b"mp42" + struct.pack(">I", 0) + b"isommp42")
    duration = 960 * sample_count
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
    out_dir = root / "app" / "src" / "test" / "resources" / "opus-conformance"
    out_dir.mkdir(parents=True, exist_ok=True)

    default_samples = [sample_celt_mono(31) for _ in range(8)]
    default_dops = build_dops(version=0, channel_count=1, pre_skip=312, sample_rate=48000, gain=0, mapping_family=0)

    # 1. default-opus.mp4 (valid mono Opus 48000Hz)
    (out_dir / "default-opus.mp4").write_bytes(build_mp4(default_samples, default_dops, channels=1))

    # 2. bad-dops-version.mp4 (dOps version = 1, invalid for ISO-BMFF)
    bad_ver_dops = build_dops(version=1, channel_count=1)
    (out_dir / "bad-dops-version.mp4").write_bytes(build_mp4(default_samples, bad_ver_dops, channels=1))

    # 3. bad-channel-count.mp4 (dOps OutputChannelCount = 0)
    bad_ch_dops = build_dops(version=0, channel_count=0)
    (out_dir / "bad-channel-count.mp4").write_bytes(build_mp4(default_samples, bad_ch_dops, channels=0))

    # 4. bad-config-match.mp4 (sample entry declares 2 channels, but dOps declares 1)
    (out_dir / "bad-config-match.mp4").write_bytes(build_mp4(default_samples, default_dops, channels=2))

    # 5. bad-mono-stereo-flag.mp4 (sample 0 has stereo flag s=1 in mono track)
    bad_mono_samples = [sample_celt_stereo(31)] + [sample_celt_mono(31) for _ in range(7)]
    (out_dir / "bad-mono-stereo-flag.mp4").write_bytes(build_mp4(bad_mono_samples, default_dops, channels=1))

    # 6. bad-code3-zero-frames.mp4 (sample 0 is Code 3 packet with M=0 forbidden frames)
    bad_code3_samples = [sample_code3(31, v=0, p=0, m=0)] + [sample_celt_mono(31) for _ in range(7)]
    (out_dir / "bad-code3-zero-frames.mp4").write_bytes(build_mp4(bad_code3_samples, default_dops, channels=1))

    # 7. truncated-sample.mp4 (sample data truncated in mdat)
    truncated_mp4 = bytearray(build_mp4(default_samples, default_dops, channels=1))
    (out_dir / "truncated-sample.mp4").write_bytes(bytes(truncated_mp4[:-20]))

    # 8. many-toc-failures.mp4 (101 samples with stereo flag in mono track)
    many_samples = [sample_celt_stereo(31) for _ in range(101)]
    (out_dir / "many-toc-failures.mp4").write_bytes(build_mp4(many_samples, default_dops, channels=1))

    print(f"Generated 8 Opus fixtures in {out_dir}")

if __name__ == "__main__":
    main()
