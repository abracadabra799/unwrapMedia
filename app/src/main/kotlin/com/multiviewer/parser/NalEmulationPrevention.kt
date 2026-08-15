package com.multiviewer.parser

// H.264 NAL payloads are not raw RBSP -- encoders insert an "emulation_prevention_three_byte"
// (0x03) after any 00 00 (00|01|02|03) sequence so the payload never accidentally contains a
// byte pattern that looks like a start code. A conformant bit-level parser MUST strip these
// before Exp-Golomb/fixed-width decoding, or any field whose bit position falls after a stripped
// byte gets silently misread -- confirmed against a real SPS from an x264-encoded file: without
// this stripping, its VUI time_scale field decodes to 16777217 (nonsensical); with it, 50 (a
// sane value for 25fps content encoded with the common time_scale = 2 * frame_rate convention).
// Used by parseH264Sps/parseH264Pps and the per-frame slice-header-prefix walk alike -- anywhere
// raw NAL bytes are about to be bit-parsed.
fun removeEmulationPreventionBytes(nalBytes: ByteArray): ByteArray {
    val out = ArrayList<Byte>(nalBytes.size)
    var zeroCount = 0
    for (b in nalBytes) {
        if (zeroCount >= 2 && b == 0x03.toByte()) {
            zeroCount = 0
            continue
        }
        out.add(b)
        zeroCount = if (b == 0x00.toByte()) zeroCount + 1 else 0
    }
    return out.toByteArray()
}
