package com.multiviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.conformance.label
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.SwingUtilities

/** Shown instead of a citation for app-only checks that are not taken from any spec text. */
internal const val STRUCTURAL_OBSERVATION_LABEL = "unwrapMedia 자체 관찰 / unwrapMedia structural observation (규격 아님)"

/** What the "출처 / Source" line of a finding shows: a clickable spec link, or a plain note. */
internal sealed class CitationSourceLine {
    data class Link(val text: String, val url: String) : CitationSourceLine()
    data class Plain(val text: String) : CitationSourceLine()
}

/** Only absolute http(s) links are opened; anything else (or a malformed string) is null, never an exception. */
internal fun parseCitationUri(url: String): URI? = try {
    val uri = URI(url.trim())
    val scheme = uri.scheme?.lowercase()
    if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) uri else null
} catch (_: Exception) {
    null
}

internal fun contentFindingSourceLine(finding: ContentFinding): CitationSourceLine? {
    finding.citation?.let { return CitationSourceLine.Link(it.label(), it.url) }
    if (finding.stage != FindingStage.CONFORMANCE) return null
    val structural = finding.verifiedFacts.any { it.startsWith("Source: unwrapMedia structural") }
    return if (structural) CitationSourceLine.Plain(STRUCTURAL_OBSERVATION_LABEL) else null
}

/**
 * Opens [url] in the system browser on a background thread (Desktop.browse can block, notably on Windows).
 * When browsing is unsupported or fails, the URL is copied to the clipboard and [onFallback] gets a bilingual
 * status message on the Swing/UI thread. Never throws.
 */
fun openCitation(url: String, onFallback: (String) -> Unit = {}) {
    val uri = parseCitationUri(url)
    if (uri == null) {
        SwingUtilities.invokeLater { onFallback("링크를 열 수 없습니다 / Cannot open link: $url") }
        return
    }
    Thread({
        val opened = try {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri); true
            } else false
        } catch (_: Exception) {
            false
        }
        if (!opened) {
            val copied = try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(uri.toString()), null); true
            } catch (_: Exception) {
                false
            }
            val message = if (copied) "브라우저를 열 수 없어 링크를 클립보드에 복사했습니다 / Browser unavailable; link copied to clipboard"
                else "링크를 열 수 없습니다 / Cannot open link: $uri"
            SwingUtilities.invokeLater { onFallback(message) }
        }
    }, "open-spec-citation").apply { isDaemon = true }.start()
}

/** "출처 / Source: …" — a clickable underlined link for a citation, or the structural-observation note. */
@Composable
internal fun CitationSourceText(line: CitationSourceLine, fontSize: TextUnit = 11.sp, plainColor: Color = AppColors.TextMuted) {
    var status by remember(line) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text("출처 / Source: ", color = AppColors.TextPrimary, fontSize = fontSize)
            when (line) {
                is CitationSourceLine.Link -> Text(
                    line.text,
                    color = AppColors.NeonBlue,
                    fontSize = fontSize,
                    textDecoration = TextDecoration.Underline,
                    // weight(fill = false): a long label wraps inside the row instead of being clipped.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { openCitation(line.url) { status = it } },
                )
                is CitationSourceLine.Plain ->
                    Text(line.text, color = plainColor, fontSize = fontSize, modifier = Modifier.weight(1f, fill = false))
            }
        }
        status?.let { Text(it, color = AppColors.TextMuted, fontSize = fontSize) }
    }
}

@Composable
internal fun SpecCitationText(citation: SpecCitation, fontSize: TextUnit = 11.sp) =
    CitationSourceText(CitationSourceLine.Link(citation.label(), citation.url), fontSize)
