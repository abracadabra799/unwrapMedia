"""Single-field negative fixtures derived from ffmpeg's real default AVC MP4."""
from pathlib import Path
import sys

out = Path(sys.argv[1])
original = (out / "default-avc1.mp4").read_bytes()
assert original[48:52] == bytes.fromhex("000002ad")
assert original[52] == 0x06
assert original[737:741] == bytes.fromhex("000005c9")
assert original[741] == 0x65

def save(name, offset, replacement):
    data = bytearray(original)
    data[offset:offset + len(replacement)] = replacement
    (out / name).write_bytes(data)

# 1. forbidden_zero_bit set to 1 on first NAL
save("forbidden-bit.mp4", 52, bytes([original[52] | 0x80]))

# 2. nal_ref_idc set to 0 on IDR slice (type 5 at offset 741)
save("idr-zero-ref.mp4", 741, bytes([original[741] & 0x9F]))

# 3. Oversize NAL extent
save("oversize-nal.mp4", 48, bytes.fromhex("7fffffff"))

# 4. Zero-length NAL
save("zero-length-nal.mp4", 48, bytes(4))

# 5. Malformed avcC (configurationVersion 1 -> 0)
avcc_idx = original.find(b"avcC")
assert original[avcc_idx + 4] == 1
save("malformed-avcc.mp4", avcc_idx + 4, bytes([0]))

# 6. Truncated prefix: stsz sample size set to 2 bytes (incomplete 4-byte prefix)
stsz_idx = original.find(b"stsz")
assert int.from_bytes(original[stsz_idx + 8:stsz_idx + 12], "big") == 0
assert original[stsz_idx + 16:stsz_idx + 20] == bytes.fromhex("0000087e")
save("truncated-prefix.mp4", stsz_idx + 16, (2).to_bytes(4, "big"))

# 7. Stress-only fixture: 101 malformed headers in sample 1
# Sample 1 size is 2174 bytes.
# 101 malformed NALs: 4 bytes length (00 00 00 01) + 1 byte header (0x86) = 5 bytes * 101 = 505 bytes.
many = bytearray(original)
first_sample = bytes.fromhex("0000000186") * 101
remaining = 2174 - len(first_sample)
assert remaining >= 6
# Remaining payload as a filler NAL (type 12 = filler data, nal_ref_idc=0 -> header 0x0c)
first_sample += (remaining - 4).to_bytes(4, "big") + bytes([0x0c]) + bytes(remaining - 5)
assert len(first_sample) == 2174
many[48:48 + 2174] = first_sample
(out / "many-forbidden-headers.mp4").write_bytes(many)
