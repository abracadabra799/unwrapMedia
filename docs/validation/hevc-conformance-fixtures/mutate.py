"""Single-field negative fixtures derived from ffmpeg's real default HEVC MP4."""
from pathlib import Path
import sys

out = Path(sys.argv[1])
original = (out / "default-hev1.mp4").read_bytes()
assert original[44:48] == bytes.fromhex("000004f7")
assert original[48:50] == bytes.fromhex("2801")

def save(name, offset, replacement):
    data = bytearray(original)
    data[offset:offset + len(replacement)] = replacement
    (out / name).write_bytes(data)

save("forbidden-bit.mp4", 48, bytes([original[48] | 0x80]))
save("zero-temporal-id.mp4", 49, bytes([original[49] & 0xF8]))
save("oversize-nal.mp4", 44, bytes.fromhex("7fffffff"))
save("zero-length-nal.mp4", 44, bytes(4))
hvc_type = original.index(b"hvcC")
save("malformed-hvcc.mp4", hvc_type + 4, bytes([0]))  # configurationVersion 1 -> 0
stsz_type = original.index(b"stsz")
assert int.from_bytes(original[stsz_type + 8:stsz_type + 12], "big") == 0
save("truncated-prefix.mp4", stsz_type + 16, (2).to_bytes(4, "big"))

# Stress-only fixture: 101 malformed headers in the first indexed sample, followed by one
# bounded filler NAL. Sample size and all later packet offsets stay identical.
many = bytearray(original)
first_sample = bytes.fromhex("00000002a801") * 101
remaining = 1275 - len(first_sample)
assert remaining >= 6
first_sample += (remaining - 4).to_bytes(4, "big") + bytes.fromhex("2801") + bytes(remaining - 6)
assert len(first_sample) == 1275
many[44:44 + 1275] = first_sample
(out / "many-forbidden-headers.mp4").write_bytes(many)
