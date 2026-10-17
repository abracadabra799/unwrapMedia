"""Single-field negative fixtures derived from ffmpeg's real default AV1 MP4."""
from pathlib import Path
import sys

out = Path(sys.argv[1])
original = (out / "default-av01.mp4").read_bytes()

# Verify expected layout of default-av01.mp4
assert original[48] == 0x0a, f"Expected 0x0a at 48, got {original[48]:#x}"
assert original[49] == 0x0b, f"Expected 0x0b at 49, got {original[49]:#x}"
assert original[61] == 0x32, f"Expected 0x32 at 61, got {original[61]:#x}"
assert original[62:64] == bytes.fromhex("ae07"), f"Expected ae07 at 62..64, got {original[62:64].hex()}"

av1c_idx = original.find(b"av1C")
assert av1c_idx == 4310, f"Expected av1C at 4310, got {av1c_idx}"
assert original[av1c_idx + 4] == 0x81, f"Expected 0x81 at av1C payload, got {original[av1c_idx + 4]:#x}"

def save(name, offset, replacement):
    data = bytearray(original)
    data[offset:offset + len(replacement)] = replacement
    (out / name).write_bytes(data)

# 1. forbidden_bit set to 1 on first OBU (offset 48: 0x0a | 0x80 = 0x8a)
save("forbidden-bit.mp4", 48, bytes([original[48] | 0x80]))

# 2. reserved_bit set to 1 on first OBU (offset 48: 0x0a | 0x01 = 0x0b)
save("reserved-bit.mp4", 48, bytes([original[48] | 0x01]))

# 3. Truncated OBU: OBU 1 (offset 61) leb128 size set to 16383 (0xff 0x7f), exceeding sample bounds
save("truncated-obu.mp4", 62, bytes.fromhex("ff7f"))

# 4. Malformed av1C: marker and version corrupted (0x81 -> 0x00)
save("malformed-av1c.mp4", av1c_idx + 4, bytes([0x00]))

# 5. Extension header reserved bits set to non-zero:
# Modify OBU 0: header byte 0 = 0x0e (type 1, ext=1, has_size=1),
# header byte 1 (extension) = 0x07 (reserved 3 bits = 7),
# leb128 size = 10 (0x0a) + 10 bytes payload = 13 bytes total
ext_obu = bytes([0x0e, 0x07, 0x0a]) + original[50:60]
assert len(ext_obu) == 13
save("extension-reserved-bit.mp4", 48, ext_obu)

# 6. Stress fixture: 101 malformed OBUs in sample 0 (offset 48, size 958)
# Each malformed OBU: 0x8a (forbidden bit 1, type 1, size=1) + 0x01 (leb128=1) + 0x00 = 3 bytes
# 101 * 3 = 303 bytes
many = bytearray(original)
malformed_obus = bytes.fromhex("8a0100") * 101
remaining = 958 - len(malformed_obus)  # 655 bytes
# Remaining bytes as a valid OBU_PADDING (type 15):
# header 0x7a (1 byte) + leb128 652 (0x8c 0x05, 2 bytes) + 652 zero bytes = 655 bytes
padding_obu = bytes([0x7a, 0x8c, 0x05]) + bytes(remaining - 3)
assert len(malformed_obus) + len(padding_obu) == 958
many[48:48 + 958] = malformed_obus + padding_obu
(out / "many-forbidden-headers.mp4").write_bytes(many)
