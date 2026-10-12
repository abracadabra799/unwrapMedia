"""Inserts a crafted HDR10+ (ST 2094-40) prefix SEI before the first slice of every HEVC access unit.

Usage: python3 -I inject_hdr10plus.py in.hevc out.hevc
Frames 12-14 (decode order) get no HDR10+ SEI; frame 17 gets an abrupt MaxSCL jump. The
bitstream layout was cross-checked field by field with ffprobe 8.1.2 -show_frames.
"""
import re
import sys


class BitWriter:
    def __init__(self):
        self.bits = []

    def u(self, n, v):
        for i in range(n - 1, -1, -1):
            self.bits.append((v >> i) & 1)

    def to_bytes(self):
        b = self.bits + [0] * ((-len(self.bits)) % 8)
        return bytes(int(''.join(map(str, b[i:i + 8])), 2) for i in range(0, len(b), 8))


def t35(maxscl, avg, targeted):
    w = BitWriter()
    w.u(8, 0xB5); w.u(16, 0x003C); w.u(16, 0x0001); w.u(8, 4)  # country, provider, oriented, app id
    w.u(8, 1)          # application_version
    w.u(2, 1)          # num_windows
    w.u(27, targeted)  # targeted_system_display_maximum_luminance
    w.u(1, 0)          # targeted_system_display_actual_peak_luminance_flag
    for v in maxscl:
        w.u(17, v)
    w.u(17, avg)
    pcts = [(1, 0), (5, 10), (10, 50), (25, 200), (50, avg), (75, 800), (90, 1500), (95, 2500), (99, maxscl[1])]
    w.u(4, len(pcts))
    for p, v in pcts:
        w.u(7, p); w.u(17, v)
    w.u(10, 0)         # fraction_bright_pixels
    w.u(1, 0)          # mastering_display_actual_peak_luminance_flag
    w.u(1, 1)          # tone_mapping_flag
    w.u(12, 1000); w.u(12, 1200); w.u(4, 3)
    for a in (300, 600, 900):
        w.u(10, a)
    w.u(1, 0)          # color_saturation_mapping_flag
    return w.to_bytes()


def add_emulation_prevention(rbsp):
    out = bytearray(); zeros = 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3); zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def sei_nal(payload):
    body = bytearray([4])  # payloadType 4 = user_data_registered_itu_t_t35
    n = len(payload)
    while n >= 255:
        body.append(255); n -= 255
    body.append(n)
    body += payload
    body.append(0x80)  # rbsp_trailing_bits
    return b'\x00\x00\x00\x01' + bytes([0x4E, 0x01]) + add_emulation_prevention(bytes(body))


def main(src, dst):
    data = open(src, 'rb').read()
    starts = [m.start() for m in re.finditer(b'\x00\x00\x01', data)]
    nals = []
    for i, s in enumerate(starts):
        b = s - 1 if s > 0 and data[s - 1] == 0 else s
        if i + 1 < len(starts):
            n = starts[i + 1]
            e = n - 1 if data[n - 1] == 0 else n
        else:
            e = len(data)
        nals.append(data[b:e])
    out = bytearray(); frame = 0
    for nal in nals:
        h = nal.index(b'\x00\x00\x01') + 3
        nal_type = (nal[h] >> 1) & 0x3F
        if nal_type < 32 and (nal[h + 2] & 0x80):  # VCL with first_slice_segment_in_pic_flag
            if frame not in (12, 13, 14):
                base = 9000 if frame == 17 else 4000 + frame * 20
                out += sei_nal(t35([base, base + 500, base - 300], base // 4, 1000))
            frame += 1
        out += nal
    open(dst, 'wb').write(out)
    print('frames', frame)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
