package com.multiviewer.parser

// A raw NAL's bytes plus the absolute file offset where those bytes begin (immediately after the
// NAL's length-prefix, inside its containing avcC/hvcC box) -- lets callers map a parsed
// SPS/PPS/VPS back to its exact on-disk location, e.g. for hex-viewer navigation.
data class RawNal(val bytes: ByteArray, val offset: Long)
