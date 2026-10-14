package com.multiviewer.ui.ai

/** A clickable reference in an answer: bytes and frames select in the app; URLs are external sources. */
sealed interface AiEvidenceRef {
    data class ByteOffset(val offset: Long) : AiEvidenceRef
    data class Frame(val index: Int) : AiEvidenceRef
    data class Check(val id: String) : AiEvidenceRef
    data class Url(val url: String) : AiEvidenceRef
}

private const val MAX_REFS = 12
private val OFFSET = Regex("\\b0x[0-9A-Fa-f]{1,12}\\b")
// Only "frame N" / "프레임 N" (optionally "#N"): a bare "#2" is usually a list item or issue number.
private val FRAME = Regex("(?i:frame\\s*#?|프레임\\s*#?)(\\d{1,7})\\b")
private val URL = Regex("https?://[^\\s)\\]>\"'<]+")

/**
 * References in [text] that point at something real: offsets inside the file, frames that exist, known check
 * IDs, and http(s) URLs. In order of first appearance, de-duplicated, capped.
 */
fun extractEvidenceRefs(
    text: String,
    fileSize: Long,
    checkIds: Set<String>,
    frameCount: Int?,
    /** Offsets that the sent context/tool output contained or that start a node; others are likely codec values. */
    supportedOffsets: Set<Long>? = null,
): List<AiEvidenceRef> {
    val found = mutableListOf<Pair<Int, AiEvidenceRef>>()
    val urls = URL.findAll(text).map { it.range to it.value.trimEnd('.', ',', ';', ':') }.toList()
    fun insideUrl(i: Int) = urls.any { i in it.first }
    urls.forEach { (range, url) -> found += range.first to AiEvidenceRef.Url(url) }
    OFFSET.findAll(text).forEach { m ->
        val v = m.value.drop(2).toLongOrNull(16)
        if (v != null && v in 0 until fileSize && !insideUrl(m.range.first) && (supportedOffsets == null || v in supportedOffsets)) {
            found += m.range.first to AiEvidenceRef.ByteOffset(v)
        }
    }
    if (frameCount != null) FRAME.findAll(text).forEach { m ->
        val i = m.groupValues[1].toIntOrNull()
        if (i != null && i in 0 until frameCount && !insideUrl(m.range.first)) found += m.range.first to AiEvidenceRef.Frame(i)
    }
    for (id in checkIds) {
        Regex("(?<![\\w.])" + Regex.escape(id) + "(?![\\w])").find(text)?.let { m ->
            if (!insideUrl(m.range.first)) found += m.range.first to AiEvidenceRef.Check(id)
        }
    }
    return found.sortedBy { it.first }.map { it.second }.distinct().take(MAX_REFS)
}
