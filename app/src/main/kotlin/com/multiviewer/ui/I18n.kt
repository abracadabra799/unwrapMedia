package com.multiviewer.ui

import java.util.prefs.Preferences

enum class AppLanguage {
    KO, EN
}

private val langPreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)
private const val LANGUAGE_KEY = "appLanguage"

fun loadLanguage(): AppLanguage {
    val saved = langPreferences.get(LANGUAGE_KEY, null)
    if (saved != null) {
        return if (saved == AppLanguage.EN.name) AppLanguage.EN else AppLanguage.KO
    }
    return AppLanguage.KO
}

fun saveLanguage(language: AppLanguage) {
    langPreferences.put(LANGUAGE_KEY, language.name)
}

object I18n {
    // Menu: File
    fun menuFile(lang: AppLanguage) = if (lang == AppLanguage.KO) "파일" else "File"
    fun menuOpen(lang: AppLanguage) = if (lang == AppLanguage.KO) "열기" else "Open"
    fun menuOpenFile(lang: AppLanguage) = if (lang == AppLanguage.KO) "파일 열기..." else "Open File..."
    fun menuOpenFolder(lang: AppLanguage) = if (lang == AppLanguage.KO) "폴더 열기..." else "Open Folder..."
    fun menuClose(lang: AppLanguage) = if (lang == AppLanguage.KO) "닫기" else "Close"

    // Menu: Navigate (Folder Media)
    fun menuNavigate(lang: AppLanguage) = if (lang == AppLanguage.KO) "탐색" else "Navigate"
    fun menuPrevFileInFolder(lang: AppLanguage) = if (lang == AppLanguage.KO) "이전 미디어 파일 (폴더 내)" else "Previous Media File in Folder"
    fun menuNextFileInFolder(lang: AppLanguage) = if (lang == AppLanguage.KO) "다음 미디어 파일 (폴더 내)" else "Next Media File in Folder"
    fun menuToggleLeftPanel(lang: AppLanguage) = if (lang == AppLanguage.KO) "좌측 패널 전환 (구조 트리 ↔ 폴더 탐색)" else "Toggle Left Panel (Structure ↔ Folder)"

    // Menu: Analyze
    fun menuAnalyze(lang: AppLanguage) = if (lang == AppLanguage.KO) "분석" else "Analyze"
    fun menuDumpStructure(lang: AppLanguage) = if (lang == AppLanguage.KO) "구조 덤프..." else "Dump Structure..."
    fun menuCheckStructure(lang: AppLanguage) = if (lang == AppLanguage.KO) "구조 결함 검사..." else "Check Structure..."
    fun menuGenerateAiPrompt(lang: AppLanguage) = if (lang == AppLanguage.KO) "AI 진단 프롬프트 생성..." else "Generate AI Prompt..."
    fun menuGenerateAiPromptAndCopy(lang: AppLanguage) = if (lang == AppLanguage.KO) "AI 프롬프트 생성 및 클립보드 복사" else "Generate AI Prompt & Copy"

    // Menu: Motion Photo
    fun menuMotionPhoto(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토" else "Motion Photo"
    fun menuCreateMotionPhotoV2(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 v2.0 생성 (권장)..." else "Create Motion Photo v2.0 (Recommended)..."
    fun menuCreateMotionPhotoV1(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 v1.0 생성 (MicroVideo)..." else "Create Motion Photo v1.0 (MicroVideo)..."
    fun menuCreateMotionPhoto(lang: AppLanguage) = menuCreateMotionPhotoV2(lang)
    fun menuExtractMotionVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 동영상 추출" else "Extract Motion Photo Video"
    fun menuExtractPreviewVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 미리보기 재생용 비디오 추출" else "Extract Preview Video"
    fun menuMotionFrameDropAnalysis(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 동영상 프레임 드랍 분석" else "Motion Video Frame Drop Analysis"

    // Menu: Gain Map
    fun menuGainmap(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵" else "Gain Map"
    fun menuViewGainmapXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 XMP 메타데이터 보기..." else "View Gain Map XMP..."
    fun menuViewGainmapImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지 보기 (팝업)..." else "View Gain Map Image (Popup)..."
    fun menuExtractGainmapImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지 추출 및 저장..." else "Extract & Save Gain Map Image..."

    // Gain Map UI Titles & Actions
    fun titleGainmapXmpWindow(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 XMP 메타데이터" else "Gain Map XMP Metadata"
    fun titleGainmapImageWindow(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지" else "Gain Map Image"
    fun tabGainmapParameters(lang: AppLanguage) = if (lang == AppLanguage.KO) "📊 파싱된 게인맵 파라미터" else "📊 Parsed Gain Map Parameters"
    fun tabGainmapRawXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "📄 전체 XMP 메타데이터 원본" else "📄 Raw XMP Metadata"
    fun tabGainmapSecondaryXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 XMP (Secondary)" else "Gain Map XMP (Secondary)"
    fun tabGainmapPrimaryXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "컨테이너 XMP (Primary)" else "Container XMP (Primary)"
    fun btnCopyXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "XMP 복사" else "Copy XMP"
    fun btnViewGainmapImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지 열기" else "View Gain Map Image"
    fun btnSaveGainmapImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지 저장..." else "Save Gain Map Image..."
    fun btnViewXmp(lang: AppLanguage) = if (lang == AppLanguage.KO) "XMP 메타데이터 보기" else "View XMP Metadata"
    fun saveGainmapDialogTitle(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 이미지 저장" else "Save Gain Map Image"

    // Menu: Bitstream
    fun menuBitstream(lang: AppLanguage) = if (lang == AppLanguage.KO) "비트스트림 추출" else "Extract Bitstream"
    fun menuExtractVideoTrack(lang: AppLanguage) = if (lang == AppLanguage.KO) "비디오 추출 (.mp4 or .mov etc)" else "Extract Video (.mp4 or .mov etc)"
    fun menuExtractAudioTrack(lang: AppLanguage) = if (lang == AppLanguage.KO) "오디오 추출 (.m4a)" else "Extract Audio (.m4a)"

    // Menu: Frame Interval
    fun menuFrameInterval(lang: AppLanguage) = if (lang == AppLanguage.KO) "프레임 간격 분석" else "Frame Intervals"
    fun menuViewFrameIntervals(lang: AppLanguage) = if (lang == AppLanguage.KO) "프레임 간격 분석 보기" else "View Frame Interval Analysis"

    // Menu: Tools / Compare
    fun menuTools(lang: AppLanguage) = if (lang == AppLanguage.KO) "도구" else "Tools"
    fun menuCompareAndAnalysis(lang: AppLanguage) = menuTools(lang)
    fun menuCompareFiles(lang: AppLanguage) = if (lang == AppLanguage.KO) "두 파일 상세 비교 (구조 · 메타 · 프레임 · Hex Diff)..." else "Compare Two Files (Structure, Meta, Frame, Hex Diff)..."
    fun menuQualityBenchmark(lang: AppLanguage) = if (lang == AppLanguage.KO) "동영상 인코딩 화질 측정 (PSNR · SSIM · VMAF 벤치마크)..." else "Video Quality Benchmark (PSNR, SSIM, VMAF Metrics)..."

    // Backward compatibility aliases
    fun menuMediaCompare(lang: AppLanguage) = menuCompareAndAnalysis(lang)
    fun menuOpenMediaCompare(lang: AppLanguage) = menuCompareFiles(lang)
    fun menuImageCompare(lang: AppLanguage) = menuCompareAndAnalysis(lang)
    fun menuOpenImageCompare(lang: AppLanguage) = menuCompareFiles(lang)
    fun menuQualityCompare(lang: AppLanguage) = menuCompareAndAnalysis(lang)
    fun menuOpenQualityCompare(lang: AppLanguage) = menuQualityBenchmark(lang)

    // Menu: View
    fun menuView(lang: AppLanguage) = if (lang == AppLanguage.KO) "보기" else "View"
    fun menuDarkTheme(lang: AppLanguage) = if (lang == AppLanguage.KO) "다크 테마" else "Dark Theme"
    fun menuLightTheme(lang: AppLanguage) = if (lang == AppLanguage.KO) "라이트 테마" else "Light Theme"
    fun menuPixelGrid(lang: AppLanguage) = if (lang == AppLanguage.KO) "픽셀 그리드" else "Pixel Grid"
    fun menuMotionVectors(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션 벡터 보기" else "View Motion Vectors"
    fun menuQpHeatmap(lang: AppLanguage) = if (lang == AppLanguage.KO) "QP 히트맵 보기" else "View QP Heatmap"
    fun menuKorean(lang: AppLanguage) = "한국어 (Korean)"
    fun menuEnglish(lang: AppLanguage) = "English"

    // Feedback & Toast messages
    fun toastPromptCopied(lang: AppLanguage) = if (lang == AppLanguage.KO) "AI 분석 프롬프트가 클립보드에 복사되었습니다." else "AI analysis prompt copied to clipboard."
    fun toastClipboardFailed(lang: AppLanguage) = if (lang == AppLanguage.KO) "클립보드 접근에 실패했습니다." else "Failed to access clipboard."
    fun toastExtractingVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "비디오 트랙 추출 중..." else "Extracting video track..."
    fun toastExtractingAudio(lang: AppLanguage) = if (lang == AppLanguage.KO) "오디오 트랙 추출 중..." else "Extracting audio track..."
    fun toastCreatingMotionPhoto(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 생성 중..." else "Creating Motion Photo..."
    fun toastMotionPhotoCreated(lang: AppLanguage, filename: String) = if (lang == AppLanguage.KO) "모션포토 생성 완료: $filename" else "Motion Photo created: $filename"
    fun toastMotionPhotoFailed(lang: AppLanguage, msg: String) = if (lang == AppLanguage.KO) "모션포토 생성 실패: $msg" else "Failed to create Motion Photo: $msg"
    fun toastSaved(lang: AppLanguage, filename: String) = if (lang == AppLanguage.KO) "저장됨: $filename" else "Saved: $filename"
    fun toastExtractionFailed(lang: AppLanguage) = if (lang == AppLanguage.KO) "트랙 추출 실패" else "Track extraction failed"
    fun toastGainmapExtracted(lang: AppLanguage, filename: String) = if (lang == AppLanguage.KO) "게인맵 이미지가 저장되었습니다: $filename" else "Gain map image saved: $filename"
    fun toastGainmapExtractFailed(lang: AppLanguage, msg: String) = if (lang == AppLanguage.KO) "게인맵 이미지 저장 실패: $msg" else "Failed to save gain map image: $msg"
    fun toastGainmapXmpCopied(lang: AppLanguage) = if (lang == AppLanguage.KO) "게인맵 XMP 메타데이터가 클립보드에 복사되었습니다." else "Gain map XMP copied to clipboard."

    // Motion Photo Validation Errors
    fun dialogTitleMotionPhotoCannotCreate(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 생성 불가" else "Cannot Create Motion Photo"
    fun errMotionPhotoAlreadyExists(lang: AppLanguage) = if (lang == AppLanguage.KO) "이미 모션포토 동영상이 포함되어 있는 파일입니다.\n모션포토를 새로 생성하려면 일반 정지 이미지를 열어주세요." else "The selected file is already a Motion Photo.\nPlease select a static image to create a new Motion Photo."
    fun errMotionPhotoInvalidImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "이미지 파일이 유효하지 않거나 손상되었습니다." else "The image file is invalid or corrupted."
    fun errMotionPhotoUnsupportedImage(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 생성이 지원되지 않는 이미지 형식입니다.\n(JPEG, HEIC/HEIF 형식만 지원됩니다)" else "Unsupported image format for Motion Photo.\n(Only JPEG and HEIC/HEIF formats are supported)"
    fun errMotionPhotoAnimatedGif(lang: AppLanguage) = if (lang == AppLanguage.KO) "애니메이션 GIF 파일은 모션포토로 변환할 수 없습니다." else "Animated GIF files cannot be converted to Motion Photo."
    fun errMotionPhotoV1HeicNotSupported(lang: AppLanguage) = if (lang == AppLanguage.KO) "MicroVideo (v1.0) 포맷은 HEIC 형식을 지원하지 않습니다.\n모션포토 v2.0 생성을 사용해 주세요." else "MicroVideo (v1.0) format does not support HEIC/HEIF.\nPlease use Motion Photo v2.0."
    fun errMotionPhotoInvalidVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "선택한 동영상 파일이 비어있거나 유효하지 않습니다." else "The selected video file is empty or invalid."
    fun errMotionPhotoImageLoading(lang: AppLanguage) = if (lang == AppLanguage.KO) "이미지 파일을 불러오는 중입니다. 완료 후 다시 시도해 주세요." else "Image is still loading. Please try again once loading completes."

    // Empty state
    fun placeholderEmptyState(lang: AppLanguage) = if (lang == AppLanguage.KO) "📂 파일들을 끌어다 놓거나 클릭하여 열기 (다중 파일 지원)" else "📂 Drag & Drop or Click to Open (Multiple Files Supported)"

    // App Version & About
    const val APP_VERSION = "1.9.1"
    fun menuHelp(lang: AppLanguage) = if (lang == AppLanguage.KO) "도움말" else "Help"
    fun menuAbout(lang: AppLanguage) = if (lang == AppLanguage.KO) "unwrapMedia 정보..." else "About unwrapMedia..."
    fun menuVersionInfo(lang: AppLanguage) = if (lang == AppLanguage.KO) "버전: v$APP_VERSION" else "Version: v$APP_VERSION"
    fun titleAboutWindow(lang: AppLanguage) = if (lang == AppLanguage.KO) "unwrapMedia 정보" else "About unwrapMedia"
}
