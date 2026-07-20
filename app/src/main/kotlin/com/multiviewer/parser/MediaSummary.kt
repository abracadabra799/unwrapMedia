package com.multiviewer.parser

enum class MediaCategory { IMAGE, VIDEO }

data class SummaryField(
    val label: String,
    val value: String,
)

data class SummarySection(
    val title: String,
    val fields: List<SummaryField>,
)

data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
)
