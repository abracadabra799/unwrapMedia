#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

def box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload

def full_box(box_type: bytes, version: int, flags: int, payload: bytes) -> bytes:
    vf = (version << 24) | (flags & 0xFFFFFF)
    return struct.pack(">I", len(payload) + 12) + box_type + struct.pack(">I", vf) + payload

def descriptor(tag: int, payload: bytes) -> bytes:
    # Tag-Length-Value encoding with 1-byte length if < 128
    if len(payload) < 128:
        return bytes([tag, len(payload)]) + payload
    else:
        # Multi-byte length
        length_bytes = []
        val = len(payload)
        while val > 0:
            length_bytes.append(val & 0x7F)
            val >>= 7
        length_bytes.reverse()
        for i in range(len(length_bytes) - 1):
            length_bytes[i] |= 0x80
        return bytes([tag]) + bytes(length_bytes) + payload

def build_esds(aot: int = 2, freq_idx: int = 4, channel_config: int = 2) -> bytes:
    # AudioSpecificConfig: 5 bits AOT, 4 bits freq_idx, 4 bits channel_config, 3 bits flags
    b0 = ((aot & 0x1F) << 3) | ((freq_idx >> 1) & 0x07)
    b1 = ((freq_idx & 0x01) << 7) | ((channel_config & 0x0F) << 3)
    asc_payload = bytes([b0, b1])

    # DecoderSpecificInfo (tag 0x05)
    dec_spec_info = descriptor(0x05, asc_payload)

    # DecoderConfigDescriptor (tag 0x04)
    # objectTypeIndication: 0x40 (Audio ISO/IEC 14496-3)
    # streamType: (0x05 << 2) | 1 = 0x15 (AudioStream, reserved=1)
    # bufferSizeDB: 3 bytes (e.g. 0x000300)
    # maxBitrate: 4 bytes (e.g. 128000)
    # avgBitrate: 4 bytes (e.g. 128000)
    dcd_payload = bytes([0x40, 0x15]) + struct.pack(">I", 0x000300)[1:] + struct.pack(">II", 128000, 128000) + dec_spec_info
    dec_config_desc = descriptor(0x04, dcd_payload)

    # ES_Descriptor (tag 0x03)
    # ES_ID: 2 bytes (1)
    # flags: 1 byte (0)
    esd_payload = struct.pack(">H", 1) + bytes([0]) + dec_config_desc
    es_desc = descriptor(0x03, esd_payload)

    return full_box(b"esds", 0, 0, es_desc)

def sample_cpe(ics_reserved_bit: int = 0) -> bytes:
    # CPE: ID_CPE (001), tag 0 (0000), common_window 1 (1) -> 0x21
    b0 = 0x21
    # ics_info: ics_reserved_bit (1 bit), window_sequence 0 (2 bits), window_shape 0 (1 bit), max_sfb 40 (6 bits)
    # b1: (reserved << 7) | (0 << 5) | (0 << 4) | (1010)
    b1 = ((ics_reserved_bit & 1) << 7) | 0x0A
    # payload padded to 32 bytes, ending with ID_END (111b = 7)
    return bytes([b0, b1]) + bytes(29) + bytes([0xE0])

def sample_adts() -> bytes:
    # Corrupted: leaks ADTS syncword 0xFFF
    return bytes([0xFF, 0xF1, 0x50, 0x80]) + bytes(28)

def build_m4a(samples: list[bytes], esds_box: bytes, sample_rate: int = 44100, channels: int = 2) -> bytes:
    sample_count = len(samples)

    # AudioSampleEntry fixed 28-byte header:
    # 6 bytes reserved, data_ref_idx (2B), 8 bytes reserved (2 u32),
    # channelcount (2B), samplesize (2B), pre_defined (2B), reserved (2B), samplerate (4B, 16.16 fp)
    entry_prefix = bytes(6) + struct.pack(">H", 1) + bytes(8) + struct.pack(">HHHH", channels, 16, 0, 0) + struct.pack(">I", sample_rate << 16)
    mp4a_entry = box(b"mp4a", entry_prefix + esds_box)

    stsd = full_box(b"stsd", 0, 0, struct.pack(">I", 1) + mp4a_entry)
    stts = full_box(b"stts", 0, 0, struct.pack(">II", 1, sample_count) + struct.pack(">I", 1024))
    stsz = full_box(b"stsz", 0, 0, struct.pack(">II", 0, sample_count) + struct.pack(f">{sample_count}I", *[len(s) for s in samples]))
    stsc = full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))

    ftyp = box(b"ftyp", b"M4A " + struct.pack(">I", 0) + b"M4A isommp42")
    duration = 1024 * sample_count
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
    out_dir = Path("app/src/test/resources/aac-conformance")
    out_dir.mkdir(parents=True, exist_ok=True)

    default_samples = [sample_cpe(0) for _ in range(8)]
    default_esds = build_esds(aot=2, freq_idx=4, channel_config=2)

    # 1. default-mp4a.m4a (valid stereo AAC LC 44100Hz)
    (out_dir / "default-mp4a.m4a").write_bytes(build_m4a(default_samples, default_esds))

    # 2. bad-adts-leaked.m4a (sample 0 leaked ADTS syncword 0xFFF)
    bad_adts_samples = [sample_adts()] + [sample_cpe(0) for _ in range(7)]
    (out_dir / "bad-adts-leaked.m4a").write_bytes(build_m4a(bad_adts_samples, default_esds))

    # 3. bad-asc-sampling.m4a (samplingFrequencyIndex = 13, reserved)
    bad_asc_esds = build_esds(aot=2, freq_idx=13, channel_config=2)
    (out_dir / "bad-asc-sampling.m4a").write_bytes(build_m4a(default_samples, bad_asc_esds))

    # 4. bad-ics-reserved.m4a (sample 0 has ics_reserved_bit = 1)
    bad_ics_samples = [sample_cpe(1)] + [sample_cpe(0) for _ in range(7)]
    (out_dir / "bad-ics-reserved.m4a").write_bytes(build_m4a(bad_ics_samples, default_esds))

    # 5. malformed-esds.m4a (esds box truncated)
    malformed_esds = box(b"esds", bytes([0, 0, 0, 0, 0x03, 0x04, 0x01])) # truncated descriptor
    (out_dir / "malformed-esds.m4a").write_bytes(build_m4a(default_samples, malformed_esds))

    # 6. truncated-sample.m4a (stsz declares size larger than mdat)
    truncated_m4a = bytearray(build_m4a(default_samples, default_esds))
    # Truncate file by removing 20 bytes from mdat
    (out_dir / "truncated-sample.m4a").write_bytes(bytes(truncated_m4a[:-20]))

    # 7. many-adts-failures.m4a (101 samples with ADTS leak)
    many_samples = [sample_adts() for _ in range(101)]
    (out_dir / "many-adts-failures.m4a").write_bytes(build_m4a(many_samples, default_esds))

    print(f"Generated 7 AAC fixtures in {out_dir}")

if __name__ == "__main__":
    main()
