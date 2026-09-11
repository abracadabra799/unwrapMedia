package com.multiviewer.parser

enum class MediaCategory { IMAGE, VIDEO, AUDIO }

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
    val thumbnail: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaSummary) return false
        if (category != other.category) return false
        if (sections != other.sections) return false
        if (motionPhotoVideoSections != other.motionPhotoVideoSections) return false
        if (thumbnail?.contentEquals(other.thumbnail) != true && thumbnail != other.thumbnail) return false
        return true
    }

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + sections.hashCode()
        result = 31 * result + (motionPhotoVideoSections?.hashCode() ?: 0)
        result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
        return result
    }
}
