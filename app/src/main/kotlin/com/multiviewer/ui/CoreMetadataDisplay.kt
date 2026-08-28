package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection

@Composable
fun CoreMetadataDisplay(summary: MediaSummary, modifier: Modifier = Modifier) {
    CoreMetadataDisplay(sections = summary.sections, modifier = modifier)
}

// Stacked vertically (was a horizontal Row with each card at weight(1f)) -- that layout assumed
// the wide center panel it used to share with the preview; now that this is the only remaining
// caller (the right-side DetailedPropertiesPanel's Overview tab, always narrow), a Row squeezed
// every card down to a sliver, wrapping every field's label/value onto separate misaligned lines.
// Also no longer filters by section title -- the old whitelist ("General"/"Video"/"Audio"/
// "Image"/"Camera Info") silently dropped sections like "GPS Location" and "Samsung Metadata"
// that MediaSummaryBuilder deliberately produces; an at-a-glance overview should show all of them.
@Composable
fun CoreMetadataDisplay(sections: List<SummarySection>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sections.forEach { section ->
            MetadataCard(section, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun layerDescriptionForSection(title: String): String? = when (title) {
    "General" -> "Container Layer (컨테이너 계층)"
    "Track List" -> "Track Multiplexing (트랙 구성)"
    "Video" -> "Stream & Codec (비디오 스트림/코덱)"
    "Video Detail" -> "Bitstream & NAL (비트스트림 구조)"
    "Audio" -> "Stream & Codec (오디오 스트림/코덱)"
    "Apple Device" -> "Device & Creator (기기 정보)"
    "Apple Video Metadata" -> "QuickTime Extensions (센서/확장)"
    "Live Photo" -> "Motion Photo (라이브 포토)"
    "Camera Info" -> "EXIF Capture (촬영 설정)"
    "GPS Location" -> "Geotagging (위치 정보)"
    "Image" -> "Image Properties (이미지 속성)"
    "Samsung Metadata" -> "Vendor Extensions (제조사 메타)"
    else -> if (title.endsWith("Detail")) "Format Structure (포맷 구조)" else null
}

private fun contextualFieldLabel(sectionTitle: String, originalLabel: String): String = when (sectionTitle) {
    "General" -> when (originalLabel) {
        "Format" -> "Container Format"
        "Overall Bit Rate" -> "Total Bit Rate (All Streams)"
        "Duration" -> "Total Duration"
        else -> originalLabel
    }
    "Video" -> when (originalLabel) {
        "Format" -> "Video Codec"
        "Bit Rate" -> "Video Bit Rate"
        else -> originalLabel
    }
    "Audio" -> when (originalLabel) {
        "Format" -> "Audio Codec"
        "Bit Rate" -> "Audio Bit Rate"
        else -> originalLabel
    }
    "Apple Video Metadata" -> when (originalLabel) {
        "Color Primaries / Transfer / Matrix" -> "QuickTime NCLC Tag (Raw)"
        "Timed Metadata Tracks" -> "Timed Metadata Tracks (Sensors)"
        else -> originalLabel
    }
    else -> originalLabel
}

private fun contextualFieldValue(sectionTitle: String, label: String, value: String): String {
    if (sectionTitle == "Apple Video Metadata" && label.startsWith("Color Primaries / Transfer / Matrix")) {
        val parts = value.split(" / ").map { it.trim() }
        if (parts.size == 3) {
            val pDesc = when (parts[0]) { "1" -> "BT.709"; "9" -> "BT.2020"; "12" -> "Display P3"; else -> null }
            val tDesc = when (parts[1]) { "1" -> "BT.709"; "13" -> "sRGB"; "16" -> "SMPTE 2084"; "18" -> "HLG"; else -> null }
            val mDesc = when (parts[2]) { "1" -> "BT.709"; "9" -> "BT.2020-NC"; else -> null }
            if (pDesc != null || tDesc != null || mDesc != null) {
                return "$value (${pDesc ?: parts[0]} / ${tDesc ?: parts[1]} / ${mDesc ?: parts[2]})"
            }
        }
    }
    return value
}

@Composable
private fun MetadataCard(section: SummarySection, modifier: Modifier = Modifier) {
    val layerDesc = layerDescriptionForSection(section.title)
    Column(
        modifier = modifier
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Border)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.title.uppercase(),
                style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue)
            )
            if (layerDesc != null) {
                Text(
                    text = layerDesc,
                    style = AppTypography.bodyLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        section.fields.forEach { field ->
            val displayLabel = contextualFieldLabel(section.title, field.label)
            val displayValue = contextualFieldValue(section.title, field.label, field.value)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(displayLabel, style = AppTypography.labelLarge.copy(fontSize = 12.sp))
                Text(displayValue, style = AppTypography.bodyLarge.copy(fontSize = 12.sp), color = AppColors.TextPrimary)
            }
        }
    }
}

