package com.multiviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.GainmapValidator
import com.multiviewer.parser.integrity.IntegrityCheckItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gain map conformance checks for [tab], computed off the UI thread (they read the file); null while
 * running or when the tab has no gain map. Re-keyed on the base bitmap: a HEIC base is decoded later
 * by the ffmpeg fallback, and the aspect check needs its size.
 */
@Composable
internal fun rememberGainmapChecks(tab: TabState): List<IntegrityCheckItem>? {
    val info = tab.gainmapInfo?.takeIf { it.hasGainmap }
    val root = tab.root
    val base = tab.imageForensic?.bitmap
    val checks by produceState<List<IntegrityCheckItem>?>(null, tab.file, info, root, base?.width, base?.height) {
        value = if (info == null || root == null) {
            null
        } else {
            withContext(Dispatchers.IO) { GainmapValidator.validate(tab.file, root, info, base?.width, base?.height) }
        }
    }
    return checks
}

@Composable
internal fun GainmapCheckPage(tab: TabState, language: AppLanguage) {
    val ko = language == AppLanguage.KO
    val checks = rememberGainmapChecks(tab)
    if (checks == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DecodingIndicator(if (ko) "게인맵 검사 중..." else "Checking gain map...")
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        ContentFindingTable(gainmapFindings(checks), tab.file.length(), ko, Modifier.fillMaxSize()) { offset, length ->
            tab.parameterSetHighlightRange = offset until (offset + length)
        }
    }
}
