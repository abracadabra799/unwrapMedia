package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.findFirst
import java.io.File

enum class PlatformSupportLevel {
    FULL,            // 비디오 완전 재생 및 셔터 순간 일치
    PARTIAL,         // 재생 가능하나 일부 메타데이터 미지원 또는 변환 필요
    STILL_ONLY,      // 모션 비디오 무시/유실되고 스틸 사진만 표시됨
    UNSUPPORTED,     // 포맷 오류 또는 지원 불가
}

data class PlatformCompatibilityEntry(
    val platformName: String,
    val supportLevel: PlatformSupportLevel,
    val requiredFeatures: String,
    val currentStatusText: String,
    val notes: String,
)

data class MotionPhotoProfileSpecs(
    val hasSamsungSef: Boolean,
    val sefHasMotionPhotoData: Boolean,
    val hasGoogleXmpV2Directory: Boolean,
    val hasGoogleXmpV1MicroVideo: Boolean,
    val hasAppleLivePhotoPair: Boolean,
    val hasAppleMpvdBox: Boolean,
    val hasEmbeddedVideo: Boolean,
    val videoCodec: String? = null,
)

data class CompatibilityMatrixReport(
    val detectedProfile: String,
    val entries: List<PlatformCompatibilityEntry>,
)

/**
 * Evaluates motion photo compatibility across major gallery apps, OS viewers, browsers, and social media.
 */
fun evaluateMotionPhotoCompatibility(specs: MotionPhotoProfileSpecs): CompatibilityMatrixReport {
    val detectedProfile = when {
        specs.hasAppleLivePhotoPair -> "Apple Live Photo (Still + MOV 페어)"
        specs.hasAppleMpvdBox -> "HEIC mpvd 임베디드 모션포토"
        specs.hasSamsungSef && specs.hasGoogleXmpV2Directory -> "삼성 SEF + 구글 XMP v2 하이브리드 모션포토"
        specs.hasSamsungSef && specs.hasGoogleXmpV1MicroVideo -> "삼성 SEF + 구글 XMP v1 하이브리드 모션포토"
        specs.hasSamsungSef -> "삼성 SEF 레거시 모션포토"
        specs.hasGoogleXmpV2Directory -> "구글 XMP v2 (Container:Directory) 모션포토"
        specs.hasGoogleXmpV1MicroVideo -> "구글 XMP v1 (MicroVideoOffset) 모션포토"
        specs.hasEmbeddedVideo -> "일반 임베디드 비디오 미디어"
        else -> "정적 이미지 (모션포토 미감지)"
    }

    val entries = mutableListOf<PlatformCompatibilityEntry>()

    // 1. Samsung Gallery
    val samsungLevel = when {
        specs.sefHasMotionPhotoData || specs.hasAppleMpvdBox -> PlatformSupportLevel.FULL
        specs.hasGoogleXmpV2Directory || specs.hasGoogleXmpV1MicroVideo -> PlatformSupportLevel.FULL
        specs.hasAppleLivePhotoPair -> PlatformSupportLevel.PARTIAL
        specs.hasEmbeddedVideo -> PlatformSupportLevel.PARTIAL
        else -> PlatformSupportLevel.STILL_ONLY
    }
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "삼성 갤러리 (Samsung Gallery)",
            supportLevel = samsungLevel,
            requiredFeatures = "SEF MotionPhoto_Data 또는 XMP Directory / MicroVideo 또는 HEIC mpvd",
            currentStatusText = if (samsungLevel == PlatformSupportLevel.FULL) "완전 재생 가능" else "일부 재생 또는 스틸 전용",
            notes = if (specs.sefHasMotionPhotoData) "삼성 순정 SEF 트레일러 지원" else if (specs.hasGoogleXmpV2Directory) "최신 OneUI에서 구글 XMP 모션포토 정상 호환" else "Live Photo 페어의 경우 별도 동영상 파일로 인식될 수 있음",
        ),
    )

    // 2. Google Photos
    val googleLevel = when {
        specs.hasGoogleXmpV2Directory || specs.hasGoogleXmpV1MicroVideo || specs.hasAppleLivePhotoPair -> PlatformSupportLevel.FULL
        specs.hasAppleMpvdBox -> PlatformSupportLevel.PARTIAL
        specs.hasSamsungSef && !specs.hasGoogleXmpV2Directory && !specs.hasGoogleXmpV1MicroVideo -> PlatformSupportLevel.STILL_ONLY
        else -> PlatformSupportLevel.STILL_ONLY
    }
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "구글 포토 (Google Photos)",
            supportLevel = googleLevel,
            requiredFeatures = "XMP Container:Directory (v2) 또는 MicroVideo (v1) 또는 Apple Live Photo Pair",
            currentStatusText = if (googleLevel == PlatformSupportLevel.FULL) "완전 재생 가능" else if (googleLevel == PlatformSupportLevel.PARTIAL) "부분 호환" else "스틸 사진만 표시 (정적 뷰어)",
            notes = if (specs.hasSamsungSef && !specs.hasGoogleXmpV2Directory && !specs.hasGoogleXmpV1MicroVideo) {
                "구글 포토는 삼성 전용 SEF 트레일러를 파싱하지 않아 비디오가 무시되고 스틸 사진만 표시됩니다."
            } else if (specs.hasGoogleXmpV2Directory) {
                "Google 공식 표준 XMP Directory 규격 완전 호환"
            } else if (specs.hasAppleLivePhotoPair) {
                "iOS 및 Android 구글 포토 앱에서 Apple Live Photo 페어 완벽 지원"
            } else {
                "지원 메타데이터 부재"
            },
        ),
    )

    // 3. Apple Photos
    val appleLevel = when {
        specs.hasAppleLivePhotoPair -> PlatformSupportLevel.FULL
        specs.hasAppleMpvdBox -> PlatformSupportLevel.PARTIAL
        else -> PlatformSupportLevel.STILL_ONLY
    }
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "애플 사진 (Apple Photos - iOS/macOS)",
            supportLevel = appleLevel,
            requiredFeatures = "Still + MOV Content Identifier UUID 페어 또는 Live Photo HEIC mpvd",
            currentStatusText = if (appleLevel == PlatformSupportLevel.FULL) "Live Photo 완전 동작" else if (appleLevel == PlatformSupportLevel.PARTIAL) "HEIC mpvd 일부 지원" else "스틸 사진만 표시",
            notes = if (appleLevel == PlatformSupportLevel.FULL) {
                "Apple 순정 Live Photo 페어 구조"
            } else {
                "iOS는 안드로이드의 SEF / Google XMP 트레일러를 인식하지 못하므로 정적 사진으로만 표시됩니다."
            },
        ),
    )

    // 4. Xiaomi Gallery
    val xiaomiLevel = when {
        specs.hasGoogleXmpV1MicroVideo || specs.hasGoogleXmpV2Directory -> PlatformSupportLevel.FULL
        specs.hasSamsungSef -> PlatformSupportLevel.STILL_ONLY
        else -> PlatformSupportLevel.STILL_ONLY
    }
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "샤오미 갤러리 (Xiaomi / HyperOS)",
            supportLevel = xiaomiLevel,
            requiredFeatures = "XMP MicroVideo (v1) 또는 Container:Directory (v2)",
            currentStatusText = if (xiaomiLevel == PlatformSupportLevel.FULL) "모션포토 완전 재생" else "스틸 사진 전용",
            notes = if (xiaomiLevel == PlatformSupportLevel.FULL) "안드로이드 공통 XMP 규격을 통해 모션 재생 지원" else "타사 전용 메타데이터 미인식",
        ),
    )

    // 5. Standard Web Browsers
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "웹 브라우저 (Chrome, Safari, Edge)",
            supportLevel = PlatformSupportLevel.STILL_ONLY,
            requiredFeatures = "브라우저 HTML <img> 태그는 정적 포맷 디코더만 내장",
            currentStatusText = "스틸 사진만 렌더링",
            notes = "단일 파일에 비디오가 포함되어 있어도 웹 브라우저는 파일 후반부 비디오 트레일러를 파싱하지 않고 스틸 이미지만 렌더링합니다.",
        ),
    )

    // 6. Social Platforms (Instagram, X, Facebook)
    val socialLevel = if (specs.hasAppleLivePhotoPair) PlatformSupportLevel.PARTIAL else PlatformSupportLevel.STILL_ONLY
    entries.add(
        PlatformCompatibilityEntry(
            platformName = "소셜 미디어 (Instagram, X, Facebook)",
            supportLevel = socialLevel,
            requiredFeatures = "업로드 시 비디오 트레일러 보존 또는 Live Photo 부메랑 변환",
            currentStatusText = if (specs.hasAppleLivePhotoPair) "스틸 변환 또는 Boomerang 선택" else "스틸 사진만 업로드 (비디오 유실)",
            notes = if (specs.hasAppleLivePhotoPair) {
                "인스타그램 스토리 등에서 Apple Live Photo를 Boomerang 비디오로 변환하여 업로드 가능"
            } else {
                "단일 파일 모션포토 업로드 시 서버 인코더가 EXIF/트레일러를 스트립하여 임베디드 비디오가 완전히 유실됩니다."
            },
        ),
    )

    return CompatibilityMatrixReport(
        detectedProfile = detectedProfile,
        entries = entries,
    )
}

/**
 * Extracts motion photo profile specs from a BoxNode and environment.
 */
fun extractMotionPhotoProfileSpecs(
    root: BoxNode,
    hasPair: Boolean = false,
    videoCodec: String? = null,
): MotionPhotoProfileSpecs {
    val sefdNode = findFirst(root) { it.type == "sefd" }
    val hasSamsungSef = sefdNode != null
    val sefHasMotionPhotoData = sefdNode?.children?.any { it.type == "MotionPhoto_Data" } == true

    val mpvdNode = findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" }
    val hasAppleMpvdBox = mpvdNode != null

    val xmpText = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value

    var hasGoogleXmpV2 = false
    var hasGoogleXmpV1 = false
    if (xmpText != null) {
        if (xmpText.contains("Container:Directory", ignoreCase = true) && xmpText.contains("MotionPhoto", ignoreCase = true)) {
            hasGoogleXmpV2 = true
        }
        if (xmpText.contains("MicroVideoOffset", ignoreCase = true) || xmpText.contains("MicroVideo", ignoreCase = true)) {
            hasGoogleXmpV1 = true
        }
    }

    val hasEmbedded = hasSamsungSef || hasAppleMpvdBox || hasGoogleXmpV2 || hasGoogleXmpV1

    return MotionPhotoProfileSpecs(
        hasSamsungSef = hasSamsungSef,
        sefHasMotionPhotoData = sefHasMotionPhotoData,
        hasGoogleXmpV2Directory = hasGoogleXmpV2,
        hasGoogleXmpV1MicroVideo = hasGoogleXmpV1,
        hasAppleLivePhotoPair = hasPair,
        hasAppleMpvdBox = hasAppleMpvdBox,
        hasEmbeddedVideo = hasEmbedded,
        videoCodec = videoCodec,
    )
}
