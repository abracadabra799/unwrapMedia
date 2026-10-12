package com.multiviewer.parser

import java.util.Locale

private fun f(format: String, vararg args: Any): String = String.format(Locale.US, format, *args)

fun summarizeMetadata(unit: MetadataUnit): String = when (val d = unit.decoded) {
    is DecodedMetadata.Mastering -> f("max %.0f / min %.4f cd/m²", d.value.maxLuminance, d.value.minLuminance)
    is DecodedMetadata.LightLevel -> "MaxCLL ${d.value.maxCll} / MaxFALL ${d.value.maxFall}"
    is DecodedMetadata.Hdr10Plus -> d.value.windows.first().let { w ->
        f("HDR10+ MaxSCL %.1f nits, avg %.1f nits, target %d nits", w.maxSclNits, w.averageMaxrgbNits, d.value.targetedSystemDisplayMaximumLuminance)
    }
    is DecodedMetadata.ItuT35 -> f("T.35 country 0x%02X, provider %s", d.countryCode, d.providerCode?.let { f("0x%04X", it) } ?: "-")
    is DecodedMetadata.UserDataUnregistered -> "${d.uuid} ${d.asciiPreview.take(40)}".trimEnd()
    is DecodedMetadata.RecoveryPoint -> "recovery ${d.recoveryCount}, exact_match=${d.exactMatch}, broken_link=${d.brokenLink}"
    is DecodedMetadata.AlternativeTransfer -> "preferred_transfer_characteristics ${d.preferredTransferCharacteristics}"
    DecodedMetadata.PresenceOnly -> "${unit.payloadSize} bytes"
    is DecodedMetadata.Malformed -> "malformed: ${d.reason}"
}

fun describeMetadata(unit: MetadataUnit): List<Pair<String, String>> = when (val d = unit.decoded) {
    is DecodedMetadata.Mastering -> d.value.let { m ->
        listOf(
            "R (x, y)" to f("%.5f, %.5f", m.redX, m.redY),
            "G (x, y)" to f("%.5f, %.5f", m.greenX, m.greenY),
            "B (x, y)" to f("%.5f, %.5f", m.blueX, m.blueY),
            "white point (x, y)" to f("%.5f, %.5f", m.whiteX, m.whiteY),
            "max / min luminance" to f("%.4f / %.4f cd/m²", m.maxLuminance, m.minLuminance),
        )
    }
    is DecodedMetadata.Hdr10Plus -> d.value.let { m ->
        val w = m.windows.first()
        listOfNotNull(
            "application_version" to m.applicationVersion.toString(),
            "num_windows" to m.numWindows.toString(),
            "targeted_system_display_max" to "${m.targetedSystemDisplayMaximumLuminance} nits",
            "maxscl R/G/B" to w.maxscl.joinToString(" / ") { f("%.1f", it / 10.0) } + " nits",
            "average_maxrgb" to f("%.1f nits", w.averageMaxrgbNits),
            "distribution_maxrgb" to w.distribution.joinToString(", ") { (p, v) -> f("%d%%: %.1f", p, v / 10.0) } + " nits",
            "fraction_bright_pixels" to w.fractionBrightPixels.toString(),
            "tone_mapping" to if (w.toneMappingFlag) "(${w.kneePointX}, ${w.kneePointY}), anchors ${w.bezierCurveAnchors.joinToString(", ")}" else "off",
            m.colorSaturationWeight?.let { "color_saturation_weight" to it.toString() },
        )
    }
    is DecodedMetadata.ItuT35 -> listOf("payload" to d.hexPreview)
    is DecodedMetadata.UserDataUnregistered -> listOf("uuid" to d.uuid, "data" to d.asciiPreview)
    else -> emptyList()
}
