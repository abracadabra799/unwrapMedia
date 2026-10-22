package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.sniffFormat
import java.io.File

enum class SpecRequirement { MUST, SHOULD, INFO }

/** One Google Motion Photo 1.0 rule result; [section] is the spec section number (e.g. "3.3"). */
data class GoogleSpecFinding(
    val ruleId: String,
    val section: String,
    val requirement: SpecRequirement,
    val severity: SefIntegritySeverity,
    val title: String,
    val detail: String,
)

data class GmpSpecInput(
    val fileName: String,
    val format: ContainerFormat?,
    val xmpText: String?,
    val videoDurationUs: Long?,
)

internal const val HDR_GAINMAP_NS = "http://ns.adobe.com/hdr-gain-map/1.0/"
private val FILENAME_PATTERN = Regex("""^([^\s/\\][^/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)$""")
private val ALLOWED_MIMES = listOf("image/jpeg", "image/heic", "image/avif", "video/mp4", "video/quicktime")

/** PASS when [ok]; otherwise MUST → CRITICAL, SHOULD → WARNING, INFO → INFO. */
internal fun spec(ruleId: String, section: String, requirement: SpecRequirement, ok: Boolean, title: String, detail: String) =
    GoogleSpecFinding(
        ruleId, section, requirement,
        if (ok) SefIntegritySeverity.PASS else when (requirement) {
            SpecRequirement.MUST -> SefIntegritySeverity.CRITICAL
            SpecRequirement.SHOULD -> SefIntegritySeverity.WARNING
            SpecRequirement.INFO -> SefIntegritySeverity.INFO
        },
        title, detail,
    )

/** A rule that could not be evaluated: SKIPPED, never PASS. */
internal fun specSkip(ruleId: String, section: String, requirement: SpecRequirement, title: String, reason: String) =
    GoogleSpecFinding(ruleId, section, requirement, SefIntegritySeverity.SKIPPED, title, "검사하지 못함: $reason")

internal fun specInfo(ruleId: String, section: String, title: String, detail: String) =
    GoogleSpecFinding(ruleId, section, SpecRequirement.INFO, SefIntegritySeverity.INFO, title, detail)

/** Row shape of the existing motion-photo tables: "§3.3 [MUST] title". */
fun GoogleSpecFinding.toSefCheckResult(): SefCheckResult = SefCheckResult(severity, "§$section [${requirement.name}] $title", detail)

fun GoogleSpecFinding.groupLabel(): String = when {
    ruleId.startsWith("gmp.dir") || ruleId.startsWith("gmp.item") -> "Container 디렉터리 / Directory"
    ruleId.startsWith("gmp.layout") || ruleId.startsWith("gmp.gainmap") -> "배치 / Layout"
    ruleId.startsWith("gmp.mpvd") -> "HEIC·AVIF mpvd"
    ruleId == "gmp.filename" -> "파일명 / Filename"
    else -> "Camera XMP"
}

/**
 * Google Motion Photo Format 1.0 (developer.android.com/media/platform/motion-photo-format), the first-priority
 * standard for every motion photo: MUST → FAIL, SHOULD → WARN. Blocking file reads only, no subprocess.
 */
object GoogleMotionPhotoSpecChecker {
    fun check(file: File, root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<GoogleSpecFinding> {
        val xmp = findFirst(root) { node -> node.fields.any { it.name == "xmp" } }?.fields?.find { it.name == "xmp" }?.value
        return check(GmpSpecInput(file.name, sniffFormat(reader), xmp, videoDurationUs), reader)
    }

    fun check(input: GmpSpecInput, reader: ByteReader): List<GoogleSpecFinding> = buildList {
        val xmp = checkXmp(input, this)
        checkLayout(input, xmp, reader, this)
        val nameOk = FILENAME_PATTERN.matches(input.fileName)
        add(spec("gmp.filename", "2.1", SpecRequirement.SHOULD, nameOk, "파일명 / Filename",
            if (nameOk) "\"${input.fileName}\"이 권장 패턴(…MP.jpg/heic/avif)과 일치합니다"
            else "\"${input.fileName}\"이 권장 패턴 ${FILENAME_PATTERN.pattern} 과 다릅니다 — 스펙상 리더는 XMP와 영상을 무시할 수 있습니다"))
    }

    private fun checkXmp(input: GmpSpecInput, out: MutableList<GoogleSpecFinding>): GmpXmp? {
        val text = input.xmpText
        if (text == null) {
            out += spec("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, false, "Camera:MotionPhoto",
                "XMP 패킷이 없습니다 — Camera:MotionPhoto=1이 없으므로 스펙 리더는 이 파일을 모션포토로 취급하지 않습니다")
            out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, false, "Container:Directory",
                "XMP 패킷이 없어 Container:Directory가 없습니다")
            return null
        }
        val xmp = try {
            parseGmpXmp(text)
        } catch (e: Throwable) {
            // Untrusted input: deep nesting can throw StackOverflowError (an Error), as in findGoogleMotionPhotoVideo.
            out += spec("gmp.xmp.parse", "3", SpecRequirement.MUST, false, "XMP 파싱 / XMP parse",
                "XMP를 파싱할 수 없습니다: ${e.message ?: e.toString()}")
            out += specSkip("gmp.xmp.rules", "3", SpecRequirement.MUST, "XMP 규칙 / XMP rules",
                "XMP를 읽지 못해 Camera·Container·Item 규칙을 평가하지 않았습니다")
            return null
        }
        val nsOk = xmp.wrongNamespace.isEmpty()
        out += spec("gmp.ns.uris", "3", SpecRequirement.MUST, nsOk, "네임스페이스 URI / Namespace URIs",
            if (nsOk) "Camera·Container·Item 속성이 모두 스펙 URI를 사용합니다"
            else "스펙 URI가 아닌 네임스페이스의 모션포토 속성: ${xmp.wrongNamespace.joinToString(", ")} — 스펙 리더는 이 값을 찾지 못합니다")
        checkCamera(input, xmp, out)
        checkDirectory(input, xmp, out)
        return xmp
    }

    private fun checkCamera(input: GmpSpecInput, xmp: GmpXmp, out: MutableList<GoogleSpecFinding>) {
        val motionPhoto = xmp.camera["MotionPhoto"]
        out += spec("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, motionPhoto == "1", "Camera:MotionPhoto",
            when (motionPhoto) {
                "1" -> "Camera:MotionPhoto=1"
                null -> "Camera:MotionPhoto가 없습니다 — 스펙 리더는 이 파일을 모션포토로 취급하지 않습니다"
                else -> "Camera:MotionPhoto=\"$motionPhoto\" — 1이 아니면 영상이 붙어 있어도 모션포토로 취급되지 않습니다"
            })

        val version = xmp.camera["MotionPhotoVersion"]
        out += spec("gmp.camera.version", "3.1", SpecRequirement.SHOULD, version == "1", "Camera:MotionPhotoVersion",
            when (version) {
                "1" -> "Camera:MotionPhotoVersion=1"
                null -> "Camera:MotionPhotoVersion이 없습니다 (이 스펙은 버전 1을 정의합니다)"
                else -> "Camera:MotionPhotoVersion=\"$version\" — 이 스펙은 버전 1을 정의합니다"
            })

        val ptsTitle = "Camera:MotionPhotoPresentationTimestampUs"
        val pts = xmp.camera["MotionPhotoPresentationTimestampUs"]
        val ptsValue = pts?.toLongOrNull()
        val duration = input.videoDurationUs
        out += when {
            pts == null -> specInfo("gmp.camera.pts", "3.1", ptsTitle, "없음 — 리더는 영상 길이의 절반 직전 프레임을 사용합니다")
            ptsValue == null -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, false, ptsTitle, "\"$pts\"는 Long 정수가 아닙니다")
            ptsValue == -1L -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, true, ptsTitle, "-1 (미지정)")
            ptsValue < -1L -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, false, ptsTitle, "$ptsValue — 음수는 -1(미지정)만 허용됩니다")
            duration == null -> specSkip("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, ptsTitle, "영상 길이를 확인할 수 없어 범위를 검사하지 않았습니다 (값=$ptsValue)")
            else -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, ptsValue <= duration, ptsTitle,
                "$ptsValue us (영상 길이 $duration us)" + if (ptsValue <= duration) "" else " — 영상 길이를 벗어납니다")
        }

        val legacy = xmp.camera.keys.filter { it.startsWith("MicroVideo") }
        if (legacy.isNotEmpty()) {
            out += specInfo("gmp.legacy.microvideo", "3.1", "레거시 MicroVideo 필드 / Legacy MicroVideo fields",
                "${legacy.joinToString(", ")} — Motion Photo 1.0에서 삭제된 필드이며 리더는 무시해야 합니다" +
                    if (xmp.directory == null) ". Container:Directory가 없어 1.0 리더는 영상을 찾지 못합니다" else "")
        }
    }

    private fun checkDirectory(input: GmpSpecInput, xmp: GmpXmp, out: MutableList<GoogleSpecFinding>) {
        val items = xmp.directory
        if (items.isNullOrEmpty()) {
            val legacyOnly = xmp.camera.keys.any { it.startsWith("MicroVideo") }
            out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, false, "Container:Directory",
                if (items == null) "Container:Directory가 없습니다" + if (legacyOnly) " (삭제된 레거시 MicroVideo V1 형식만 있음)" else ""
                else "Container:Directory에 항목이 없습니다")
            out += specSkip("gmp.dir.items", "3.3", SpecRequirement.MUST, "디렉터리 항목 / Directory items",
                "Container:Directory 항목이 없어 Item 규칙을 평가하지 않았습니다")
            return
        }
        fun label(item: GmpDirectoryItem) = "#${item.index + 1}(${item.semantic ?: "?"})"

        out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, true, "Container:Directory",
            "항목 ${items.size}개: ${items.joinToString(", ") { it.semantic ?: "?" }}")
        out += spec("gmp.dir.primary_first", "3.2", SpecRequirement.MUST, items[0].semantic == "Primary",
            "첫 항목 = Primary / First item is Primary", "첫 항목 Item:Semantic=\"${items[0].semantic ?: "없음"}\"")
        val primaries = items.count { it.semantic == "Primary" }
        out += spec("gmp.dir.primary_unique", "3.4", SpecRequirement.MUST, primaries == 1,
            "Primary 항목 1개 / One Primary item", "Primary 항목 ${primaries}개")
        val motions = items.count { it.semantic == "MotionPhoto" }
        out += spec("gmp.dir.motionphoto_unique", "3.4", SpecRequirement.MUST, motions == 1,
            "MotionPhoto 항목 1개 / One MotionPhoto item", "MotionPhoto 항목 ${motions}개")

        val noMime = items.filter { it.mime == null }
        out += spec("gmp.item.mime_required", "3.3", SpecRequirement.MUST, noMime.isEmpty(), "Item:Mime 필수 / Item:Mime required",
            if (noMime.isEmpty()) "모든 항목에 Item:Mime이 있습니다" else "Item:Mime 없는 항목: ${noMime.joinToString { label(it) }}")
        val noSemantic = items.filter { it.semantic == null }
        out += spec("gmp.item.semantic_required", "3.3", SpecRequirement.MUST, noSemantic.isEmpty(), "Item:Semantic 필수 / Item:Semantic required",
            if (noSemantic.isEmpty()) "모든 항목에 Item:Semantic이 있습니다" else "Item:Semantic 없는 항목: ${noSemantic.joinToString { label(it) }}")
        val badMime = items.filter { it.mime != null && it.mime !in ALLOWED_MIMES }
        out += spec("gmp.item.mime_value", "3.4", SpecRequirement.MUST, badMime.isEmpty(), "Item:Mime 허용값 / Item:Mime values",
            if (badMime.isEmpty()) "모든 Item:Mime이 허용값입니다"
            else "허용되지 않은 Item:Mime: ${badMime.joinToString { "${label(it)}=\"${it.mime}\"" }} (허용: ${ALLOWED_MIMES.joinToString()})")

        val primary = items[0]
        val expectedPrimaryMime = when (input.format) {
            ContainerFormat.JPEG -> "image/jpeg"
            ContainerFormat.HEIF -> "image/heic"
            ContainerFormat.AVIF -> "image/avif"
            else -> null
        }
        out += spec("gmp.item.primary_mime", "3.3", SpecRequirement.MUST,
            expectedPrimaryMime != null && primary.mime == expectedPrimaryMime, "Primary Item:Mime",
            if (expectedPrimaryMime == null) "기본 이미지가 JPEG·HEIC·AVIF가 아닙니다 (감지 형식: ${input.format?.displayName ?: "알 수 없음"})"
            else "첫 항목 Item:Mime=\"${primary.mime ?: "없음"}\", 실제 이미지 ${input.format!!.displayName} → \"$expectedPrimaryMime\"")

        val lengthProblems = items.drop(1).mapNotNull { item ->
            val raw = item.length
            val value = raw?.toLongOrNull()
            when {
                raw == null -> "${label(item)} Item:Length 없음"
                value == null || value < 0 -> "${label(item)} Item:Length=\"$raw\"는 0 이상의 정수가 아님"
                value == 0L && item.semantic == "MotionPhoto" -> "${label(item)} Item:Length=0 — 영상 항목은 양수여야 함"
                else -> null
            }
        }
        out += spec("gmp.item.length_required", "3.3", SpecRequirement.MUST, lengthProblems.isEmpty(),
            "보조 항목 Item:Length / Secondary Item:Length",
            if (lengthProblems.isEmpty()) "모든 보조 항목에 유효한 Item:Length가 있습니다 (0은 앞 항목과 데이터 공유)" else lengthProblems.joinToString("; "))
        val primaryLengthOk = primary.length == null || primary.length == "0"
        out += spec("gmp.item.primary_length", "3.3", SpecRequirement.SHOULD, primaryLengthOk, "Primary Item:Length",
            if (primary.length == null) "생략됨 (0으로 간주)"
            else "Primary Item:Length=\"${primary.length}\"" + if (primaryLengthOk) "" else " — 0이어야 합니다")

        val laterPadding = items.drop(1).filter { it.padding != null }
        out += spec("gmp.item.padding_first_only", "3.3", SpecRequirement.MUST, laterPadding.isEmpty(),
            "Item:Padding은 첫 항목만 / Padding only on the first item",
            if (laterPadding.isEmpty()) "첫 항목 외에는 Item:Padding이 없습니다"
            else "첫 항목이 아닌데 Item:Padding이 있음: ${laterPadding.joinToString { "${label(it)}=\"${it.padding}\"" }}")
        val padding = primary.padding
        val paddingValue = padding?.toLongOrNull()
        if (input.format == ContainerFormat.HEIF || input.format == ContainerFormat.AVIF) {
            out += spec("gmp.item.padding_isobmff", "3.3", SpecRequirement.MUST, paddingValue == 8L, "HEIC/AVIF Primary Item:Padding",
                if (padding == null) "Item:Padding이 없습니다 — HEIC/AVIF는 mpvd 박스 헤더 길이 8이 필수입니다"
                else "Item:Padding=\"$padding\"" + if (paddingValue == 8L) "" else " — mpvd 박스 헤더 길이 8이어야 합니다")
        } else {
            val ok = padding == null || (paddingValue != null && paddingValue > 0)
            out += spec("gmp.item.padding_jpeg", "3.3", SpecRequirement.SHOULD, ok, "JPEG Primary Item:Padding",
                if (padding == null) "생략됨" else "Item:Padding=\"$padding\"" + if (ok) "" else " — 스펙은 양의 정수로 정의합니다")
        }
    }
}
