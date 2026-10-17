"""Single-field negative fixtures derived from ffmpeg's real default VP9 MP4."""
from pathlib import Path
import sys

out = Path(sys.argv[1])
original = (out / "default-vp09.mp4").read_bytes()

# Verify expected layout of default-vp09.mp4
assert original[44] == 0x82, f"Expected 0x82 at 44, got {original[44]:#x}"
assert original[45:48] == bytes.fromhex("498342"), f"Expected 498342 at 45..48, got {original[45:48].hex()}"

vpcc_idx = original.find(b"vpcC")
assert vpcc_idx == 5106, f"Expected vpcC at 5106, got {vpcc_idx}"
assert original[vpcc_idx + 4] == 0x01, f"Expected version 1 at vpcC, got {original[vpcc_idx + 4]:#x}"

stsz_idx = original.find(b"stsz")
assert stsz_idx == 5244, f"Expected stsz at 5244, got {stsz_idx}"

def save(name, offset, replacement):
    data = bytearray(original)
    data[offset:offset + len(replacement)] = replacement
    (out / name).write_bytes(data)

# 1. bad-marker: frame_marker (bits 7..6) set to 0 (0x82 & 0x3F = 0x02)
save("bad-marker.mp4", 44, bytes([original[44] & 0x3F]))

# 2. bad-sync-code: sync code altered from 0x498342 to 0x008342
save("bad-sync-code.mp4", 45, bytes([0x00]))

# 3. profile3-reserved-bit: profile=3 (bits 5..4 = 11) with reserved_zero=1 (bit 3 = 1) -> 0xB9
save("profile3-reserved-bit.mp4", 44, bytes([0xB9]))

# 4. malformed-vpcc: FullBox version set to 0xFF
save("malformed-vpcc.mp4", vpcc_idx + 4, bytes([0xFF]))

# 5. truncated-frame: sample 0 size in stsz set to 2 bytes
save("truncated-frame.mp4", stsz_idx + 16, (2).to_bytes(4, "big"))

# 6. Stress fixture: 101 malformed markers across 101 samples
stress_src = (out / "stress-source.mp4")
if stress_src.exists():
    stress_bytes = bytearray(stress_src.read_bytes())
    s_stsz = stress_bytes.find(b"stsz")
    sample_cnt = int.from_bytes(stress_bytes[s_stsz + 12:s_stsz + 16], "big")
    assert sample_cnt == 101, f"Expected 101 samples, got {sample_cnt}"
    sample_sizes = [int.from_bytes(stress_bytes[s_stsz + 16 + i * 4:s_stsz + 20 + i * 4], "big") for i in range(sample_cnt)]
    
    s_stco = stress_bytes.find(b"stco")
    co_cnt = int.from_bytes(stress_bytes[s_stco + 8:s_stco + 12], "big")
    chunk_offsets = [int.from_bytes(stress_bytes[s_stco + 12 + i * 4:s_stco + 16 + i * 4], "big") for i in range(co_cnt)]
    
    # Calculate sample offsets
    s_stsc = stress_bytes.find(b"stsc")
    stsc_entries = int.from_bytes(stress_bytes[s_stsc + 8:s_stsc + 12], "big")
    first_chunk = int.from_bytes(stress_bytes[s_stsc + 12:s_stsc + 16], "big")
    samples_per_chunk = int.from_bytes(stress_bytes[s_stsc + 16:s_stsc + 20], "big")
    
    sample_offsets = []
    if co_cnt == 1:
        cur_offset = chunk_offsets[0]
        for sz in sample_sizes:
            sample_offsets.append(cur_offset)
            cur_offset += sz
    else:
        # Generic single-entry stsc fallback
        cur_s = 0
        for c_idx, c_off in enumerate(chunk_offsets):
            cur_offset = c_off
            for _ in range(samples_per_chunk):
                if cur_s >= sample_cnt:
                    break
                sample_offsets.append(cur_offset)
                cur_offset += sample_sizes[cur_s]
                cur_s += 1
    
    assert len(sample_offsets) == 101
    for off in sample_offsets:
        stress_bytes[off] = stress_bytes[off] & 0x3F # Clear marker bits 7..6
    
    (out / "many-marker-failures.mp4").write_bytes(stress_bytes)
