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
