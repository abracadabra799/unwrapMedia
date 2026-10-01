package com.multiviewer.parser

import java.io.ByteArrayOutputStream

/**
 * Builds a minimal but structurally realistic HEIC byte sequence -- ftyp + meta(hdlr, pitm, iinf[+infe],
 * iloc[+item entries], optionally one for XMP) + mdat(primary item bytes) -- for testing
 * repointHeicXmpItem/createHeicXmpItem against something closer to a real file than
 * MotionPhotoBuilderTest's original bare [ftyp][mdat] fixture (which has no meta/iinf/iloc at all).
 *
 * Lives in the test source set (not main) rather than as `internal` production code: this project's
 * Gradle test-source-set wiring compiles all of app/src/test/kotlin as one Kotlin compilation, so a
 * non-private top-level declaration here is already importable from any other test file in the module
 * regardless of package (the existing `buildSefTrailer` duplication between SefIntegrityAnalyzerTest.kt
 * and MotionPhotoIntegrityAnalyzerTest.kt is because those copies are each declared `private`, which is
 * file-scoped, not because cross-file/cross-package test sharing doesn't work -- it does). Since this
 * fixture and its known consumers (HeicMetaFixtureTest, MotionPhotoBuilderTest) all live in the same
 * `com.multiviewer.parser` test package anyway, keeping it here avoids exposing test-only byte-construction
 * code from the shipped app's main source set.
 */
object HeicMetaFixture {
    data class Result(
        val heicBytes: ByteArray,
        val xmpItemId: Long,
        val xmpIlocEntryOffset: Long,
        val xmpExtentCount: Int,
        val primaryItemOffset: Long,
        val primaryItemLength: Long,
        val primaryItemBytes: ByteArray,
        val existingItemCount: Int,
        /** Absolute file offset of the PRIMARY item's own fixed-width iloc entry (its item_ID field). */
        val primaryIlocEntryOffset: Long,
        /** The construction_method written into the primary item's iloc entry. */
        val primaryConstructionMethod: Int,
        /** The base_offset written into the primary item's iloc entry (0 unless requested via `primaryBaseOffset`). */
        val primaryBaseOffset: Long,
        /** Absolute file offset of the single `mdat` box's own start (its 4-byte size field). */
        val mdatBoxOffset: Long,
        /** Total size of the single `mdat` box as built, header included. */
        val mdatBoxSize: Long,
        /** Absolute file offset of the `meta` box's own start. */
        val metaBoxOffset: Long,
        /** Absolute file offset of the trailing item's raw bytes (only meaningful when `extraItemAfterMdat`). */
        val extraItemOffset: Long,
        /** The trailing item's raw bytes as written (only meaningful when `extraItemAfterMdat`). */
        val extraItemBytes: ByteArray,
    )

    const val PRIMARY_ITEM_ID = 1L
    const val XMP_ITEM_ID = 2L
    const val EXTRA_ITEM_ID = 3L

    /**
     * @param primaryConstructionMethod construction_method to write into the PRIMARY item's iloc
     *   entry. Defaults to 0 (absolute offset -- what every real encoder emits and what all existing
     *   callers assume). Set to 1 to exercise createHeicXmpItem's idat-relative exemption in the
     *   offset-shift-correction pass: such an entry's offset must NOT be shifted when meta grows.
     *   Note the primary bytes are always physically written into mdat regardless; only the entry's
     *   own construction_method field changes, exactly as with xmpConstructionMethod.
     * @param ilocBeforeIinf Emit `iloc` BEFORE `iinf` inside meta. ISOBMFF imposes no ordering on
     *   meta's children and real encoders do emit iloc first, so box surgery that appends to both
     *   must work either way. Defaults to false (iinf first) to leave existing callers unaffected.
     * @param primaryBaseOffset Non-zero exercises encoders that set iloc's shared base_offset field
     *   (e.g. to mdat's own start) and keep each entry's own extent_offset small/relative to it,
     *   rather than encoding the full absolute position directly in extent_offset -- both are valid
     *   ISOBMFF shapes. When non-zero, iloc's base_offset_size becomes 4 and the primary item's
     *   extent_offset is written as (primaryItemOffset - primaryBaseOffset) so the two fields still
     *   resolve to the real absolute position; the XMP item's own base_offset stays 0 regardless
     *   (it has its own dedicated construction_method/extent-count test coverage already).
     * @param mdatBeforeMeta Emit `mdat` BEFORE `meta` in the assembled top-level box sequence
     *   (`ftyp, mdat, meta` instead of `ftyp, meta, mdat`). This is the same spirit as
     *   [ilocBeforeIinf], one level up: ISOBMFF fixes no order between these two top-level siblings,
     *   and real Apple-encoded HEIC commonly puts `mdat` first for streaming-friendly layout, while
     *   this fixture's historical default happens to put `meta` first. Box surgery that grows both
     *   boxes (createHeicXmpItem) or grows `mdat` under a later `meta` (repointHeicXmpItem) must be
     *   correct either way. Defaults to false (meta first) to leave existing callers unaffected.
     * @param extraItemAfterMdat Registers a THIRD item (EXTRA_ITEM_ID) whose data is a small raw
     *   blob appended at the very end of the whole file -- after ftyp, meta, AND mdat regardless of
     *   `mdatBeforeMeta`, since this offset is `ftyp.size + metaBoxSize + mdatSize` either way
     *   (addition is commutative in the physical ordering). This is the one absolute-offset item
     *   this fixture can produce whose position is genuinely past `mdat`'s end, needed to exercise
     *   repointHeicXmpItem's offset-shift-correction pass with an entry that actually crosses the
     *   cutoff -- every other item's data lives inside `mdat` itself, so growing `mdat` never moves
     *   them and the shift pass is a no-op for them. Defaults to false; existing callers unaffected.
     */
    fun build(
        xmpText: String?,
        xmpConstructionMethod: Int = 0,
        xmpExtentCount: Int = 1,
        primaryConstructionMethod: Int = 0,
        ilocBeforeIinf: Boolean = false,
        primaryBaseOffset: Long = 0L,
        mdatBeforeMeta: Boolean = false,
        extraItemAfterMdat: Boolean = false,
    ): Result {
        val primaryItemBytes = ByteArray(16) { (it + 1).toByte() }

        val ftyp = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x6d, 0x69, 0x66, 0x31, // mif1
            0x00, 0x00, 0x00, 0x00,
            0x6d, 0x69, 0x66, 0x31,
            0x68, 0x65, 0x69, 0x63, // heic
        )

        // mdat holds: [primary item bytes][xmp bytes]. The XMP item's iloc entry can still be flagged
        // construction_method=1 (xmpConstructionMethod param) to exercise repointHeicXmpItem's
        // "convert idat-relative to absolute" path -- these bytes are always placed here regardless;
        // only the iloc entry's own construction_method field changes, not where the bytes live.
        val xmpBytes = xmpText?.toByteArray(Charsets.UTF_8)
        val mdatPayload = primaryItemBytes + (xmpBytes ?: ByteArray(0))
        val mdatSize = 8 + mdatPayload.size

        // Item offsets are absolute file offsets; mdat's payload starts right after ftyp + meta +
        // mdat's own 8-byte header. We need meta's total size before we can compute this, so build
        // meta first with placeholder offsets, then patch once meta's real size is known -- simpler:
        // compute meta's bytes first (offsets inside iloc reference the eventual mdat payload start,
        // which we can compute analytically since ftyp/meta sizes are deterministic given a fixed
        // item count).

        val itemCount = 1 /* primary */ + (if (xmpText != null) 1 else 0) + (if (extraItemAfterMdat) 1 else 0)
        val baseOffsetSize = if (primaryBaseOffset != 0L) 4 else 0
        val extraItemBytes = ByteArray(8) { (it + 100).toByte() } // arbitrary, distinct from primary/xmp bytes
        // iloc payload layout (version=1): version(1)+flags(3)+offset/length sizes(1)+base/index sizes(1)+
        // item_count(2) = 8 bytes header, then one entry per item. Each entry (offsetSize=4,lengthSize=4,
        // baseOffsetSize as above,indexSize=0): item_ID(2)+construction_method(2)+data_reference_index(2)+
        // base_offset(baseOffsetSize)+extent_count(2)+extentCount*(offset(4)+length(4)) -- i.e.
        // 8+baseOffsetSize fixed bytes + extentCount*8. base_offset_size is a single field in iloc's own
        // header, so it applies uniformly to every entry, not just the one that actually uses a non-zero
        // value. The primary item always has exactly 1 extent (16 bytes); the XMP item (if present) has
        // xmpExtentCount extents, which is NOT necessarily 1 -- must be sized per-item, not by a single
        // shared per-entry constant, or a multi-extent XMP item's entry is undersized and the loloc box's
        // declared size no longer matches what's actually written (silent corruption of everything after it).
        val primaryEntrySize = 8 + baseOffsetSize + 1 * 8 // 16 + baseOffsetSize
        val xmpEntrySize = if (xmpText != null) 8 + baseOffsetSize + xmpExtentCount * 8 else 0
        val extraEntrySize = if (extraItemAfterMdat) 8 + baseOffsetSize + 1 * 8 else 0 // same shape as primary: 1 extent
        val ilocPayloadSize = 8 + primaryEntrySize + xmpEntrySize + extraEntrySize
        val ilocBoxSize = 8 + ilocPayloadSize
        val iinfEntrySize = { contentTypeLen: Int -> 8 + 4 + 2 + 2 + 4 + 1 + contentTypeLen + 1 } // infe box: header(8)+FullBox(4)+item_ID(2,v2)+protidx(2)+type(4)+name NUL(1)+content_type+NUL
        val primaryInfeSize = 8 + 4 + 2 + 2 + 4 + 1 // item_type="hvc1" or similar, no content_type needed (not mime) -- name empty
        val xmpInfeSize = if (xmpText != null) iinfEntrySize("application/rdf+xml".length) else 0
        val extraInfeSize = if (extraItemAfterMdat) primaryInfeSize else 0 // same shape as primary -- no content_type needed
        val iinfPayloadSize = 6 + primaryInfeSize + xmpInfeSize + extraInfeSize // FullBox(4)+entry_count(2) = 6
        val iinfBoxSize = 8 + iinfPayloadSize

        val hdlrBox = buildHdlrBox()
        val pitmBox = buildPitmBox(PRIMARY_ITEM_ID)

        val metaPayloadSize = 4 /* FullBox */ + hdlrBox.size + pitmBox.size + iinfBoxSize + ilocBoxSize
        val metaBoxSize = 8 + metaPayloadSize
        // Always past BOTH meta and mdat regardless of mdatBeforeMeta -- addition is commutative in
        // the physical ordering, so this is the one position in this fixture guaranteed to sit after
        // mdat's end no matter which of meta/mdat comes first.
        val extraItemOffset = (ftyp.size + metaBoxSize + mdatSize).toLong()

        // Top-level layout is ftyp + (meta, mdat) in whichever order mdatBeforeMeta selects. Both
        // boxes' sizes are already known analytically at this point, so each one's absolute start is
        // a straight sum either way.
        val metaOffset = if (mdatBeforeMeta) ftyp.size + mdatSize else ftyp.size
        val mdatOffset = if (mdatBeforeMeta) ftyp.size else ftyp.size + metaBoxSize
        val mdatPayloadOffset = mdatOffset + 8
        val primaryItemOffset = mdatPayloadOffset.toLong()
        val xmpItemOffset = if (xmpText != null) primaryItemOffset + primaryItemBytes.size else -1L

        // Now build iinf with real content_type/name.
        val iinfOut = ByteArrayOutputStream()
        run {
            val payload = ByteArrayOutputStream()
            payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox version=0, flags=0
            writeU16(payload, itemCount)
            payload.write(buildInfeBox(PRIMARY_ITEM_ID, "hvc1", null))
            if (xmpText != null) payload.write(buildInfeBox(XMP_ITEM_ID, "mime", "application/rdf+xml"))
            if (extraItemAfterMdat) payload.write(buildInfeBox(EXTRA_ITEM_ID, "hvc1", null))
            val payloadBytes = payload.toByteArray()
            writeU32(iinfOut, 8 + payloadBytes.size)
            iinfOut.write("iinf".toByteArray(Charsets.US_ASCII))
            iinfOut.write(payloadBytes)
        }
        val iinfBytes = iinfOut.toByteArray()

        val ilocOut = ByteArrayOutputStream()
        val xmpIlocEntryOffsetHolder = LongArray(1)
        val primaryIlocEntryOffsetHolder = LongArray(1)
        run {
            val payload = ByteArrayOutputStream()
            payload.write(1) // version = 1 (supports construction_method)
            payload.write(byteArrayOf(0, 0, 0)) // flags
            payload.write(0x44) // offset_size=4, length_size=4
            payload.write((baseOffsetSize shl 4) or 0x00) // base_offset_size as computed above, index_size=0
            writeU16(payload, itemCount)

            // `extents` is one (offset, length) pair per extent -- for a real multi-extent item these
            // must describe distinct, contiguous, non-overlapping sub-ranges that concatenate back to
            // the item's full bytes (matching how a real HEIC reader reassembles a split item), not the
            // same range repeated extentCount times. `baseOffset` is written once per entry (base_offset
            // is per-entry even though its WIDTH, base_offset_size, is a single iloc-wide field) --
            // each extent's own `offset` in `extents` must already be relative to this entry's
            // `baseOffset` (i.e. the caller has done `absolute - baseOffset`), matching how a real
            // decoder resolves an item's final position as `baseOffset + extent.offset`.
            fun writeEntry(itemId: Long, constructionMethod: Int, baseOffset: Long, extents: List<Pair<Long, Long>>) {
                writeU16(payload, itemId.toInt())
                writeU16(payload, constructionMethod)
                writeU16(payload, 0) // data_reference_index
                if (baseOffsetSize > 0) writeU32(payload, baseOffset.toInt())
                writeU16(payload, extents.size)
                for ((offset, length) in extents) {
                    writeU32(payload, offset.toInt())
                    writeU32(payload, length.toInt())
                }
            }
            // Same relative-to-iloc's-box-start convention as the XMP entry below; converted to an
            // absolute file offset once ilocBytes' final size is known.
            primaryIlocEntryOffsetHolder[0] = (8 /* iloc box header */ + payload.size()).toLong()
            writeEntry(
                PRIMARY_ITEM_ID, primaryConstructionMethod, primaryBaseOffset,
                listOf((primaryItemOffset - primaryBaseOffset) to primaryItemBytes.size.toLong()),
            )
            if (xmpText != null) {
                // Record this entry's byte offset relative to iloc's own BOX start (i.e. including
                // iloc's own 8-byte size+fourcc header, which isn't part of `payload` here) -- fixed
                // up to an absolute file offset below, once ilocBytes' final size is known.
                xmpIlocEntryOffsetHolder[0] = (8 /* iloc box header */ + payload.size()).toLong()
                // Split xmpBytes into xmpExtentCount contiguous, sequential chunks -- the last chunk
                // absorbs any remainder so the chunks' lengths always sum to xmpBytes.size exactly.
                val totalLen = xmpBytes!!.size
                val baseChunkLen = totalLen / xmpExtentCount
                var chunkStart = xmpItemOffset
                val xmpExtents = (0 until xmpExtentCount).map { e ->
                    val chunkLen = if (e == xmpExtentCount - 1) (totalLen - baseChunkLen * (xmpExtentCount - 1)) else baseChunkLen
                    val extent = chunkStart to chunkLen.toLong()
                    chunkStart += chunkLen
                    extent
                }
                writeEntry(XMP_ITEM_ID, xmpConstructionMethod, 0L, xmpExtents)
            }
            if (extraItemAfterMdat) {
                writeEntry(EXTRA_ITEM_ID, 0, 0L, listOf(extraItemOffset to extraItemBytes.size.toLong()))
            }
            val payloadBytes = payload.toByteArray()
            writeU32(ilocOut, 8 + payloadBytes.size)
            ilocOut.write("iloc".toByteArray(Charsets.US_ASCII))
            ilocOut.write(payloadBytes)
        }
        val ilocBytes = ilocOut.toByteArray()

        val metaOut = ByteArrayOutputStream()
        writeU32(metaOut, 8 + 4 + hdlrBox.size + pitmBox.size + iinfBytes.size + ilocBytes.size)
        metaOut.write("meta".toByteArray(Charsets.US_ASCII))
        metaOut.write(byteArrayOf(0, 0, 0, 0)) // FullBox version/flags
        metaOut.write(hdlrBox)
        metaOut.write(pitmBox)
        if (ilocBeforeIinf) {
            metaOut.write(ilocBytes)
            metaOut.write(iinfBytes)
        } else {
            metaOut.write(iinfBytes)
            metaOut.write(ilocBytes)
        }
        val metaBytes = metaOut.toByteArray()
        check(metaBytes.size == metaBoxSize) { "Fixture internal size mismatch: computed $metaBoxSize, built ${metaBytes.size}" }

        val mdatOut = ByteArrayOutputStream()
        writeU32(mdatOut, mdatSize)
        mdatOut.write("mdat".toByteArray(Charsets.US_ASCII))
        mdatOut.write(mdatPayload)
        val mdatBytes = mdatOut.toByteArray()
        check(mdatBytes.size == mdatSize) { "Fixture internal size mismatch: computed $mdatSize, built ${mdatBytes.size}" }

        val baseBytes = if (mdatBeforeMeta) ftyp + mdatBytes + metaBytes else ftyp + metaBytes + mdatBytes
        val allBytes = if (extraItemAfterMdat) baseBytes + extraItemBytes else baseBytes

        // meta's children are laid out as: FullBox(4), hdlr, pitm, then iinf/iloc in whichever order
        // ilocBeforeIinf selects -- so iloc's box start is meta's payload start plus everything
        // written ahead of it.
        val ilocAbsoluteStart = (
            metaOffset + 12 /* meta size+type+FullBox */ + hdlrBox.size + pitmBox.size +
                (if (ilocBeforeIinf) 0 else iinfBytes.size)
            ).toLong()

        return Result(
            heicBytes = allBytes,
            xmpItemId = XMP_ITEM_ID,
            // xmpIlocEntryOffsetHolder[0] is already relative to iloc's own box start (see the
            // comment where it's recorded above); adding ilocAbsoluteStart converts it to an
            // absolute file offset. No further adjustment.
            xmpIlocEntryOffset = ilocAbsoluteStart + xmpIlocEntryOffsetHolder[0],
            xmpExtentCount = xmpExtentCount,
            primaryItemOffset = primaryItemOffset,
            primaryItemLength = primaryItemBytes.size.toLong(),
            primaryItemBytes = primaryItemBytes,
            existingItemCount = itemCount,
            primaryIlocEntryOffset = ilocAbsoluteStart + primaryIlocEntryOffsetHolder[0],
            primaryConstructionMethod = primaryConstructionMethod,
            primaryBaseOffset = primaryBaseOffset,
            mdatBoxOffset = mdatOffset.toLong(),
            mdatBoxSize = mdatSize.toLong(),
            metaBoxOffset = metaOffset.toLong(),
            extraItemOffset = extraItemOffset,
            extraItemBytes = extraItemBytes,
        )
    }

    private fun buildHdlrBox(): ByteArray {
        val out = ByteArrayOutputStream()
        // Empty, NUL-terminated handler name (the "name" field of hdlr is a null-terminated UTF-8
        // string per ISOBMFF; an empty name is valid and this fixture doesn't need a real one).
        val handlerName = "\u0000"
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox
        payload.write(byteArrayOf(0, 0, 0, 0)) // pre_defined
        payload.write("pict".toByteArray(Charsets.US_ASCII)) // handler_type
        payload.write(ByteArray(12)) // reserved
        payload.write(handlerName.toByteArray(Charsets.US_ASCII))
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("hdlr".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun buildPitmBox(primaryItemId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox version=0
        writeU16(payload, primaryItemId.toInt())
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("pitm".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun buildInfeBox(itemId: Long, itemType: String, contentType: String?): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(2, 0, 0, 0)) // FullBox version=2, flags=0
        writeU16(payload, itemId.toInt())
        writeU16(payload, 0) // item_protection_index
        payload.write(itemType.toByteArray(Charsets.US_ASCII))
        payload.write(0) // empty item_name, NUL-terminated
        if (contentType != null) {
            payload.write(contentType.toByteArray(Charsets.US_ASCII))
            payload.write(0)
        }
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("infe".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
