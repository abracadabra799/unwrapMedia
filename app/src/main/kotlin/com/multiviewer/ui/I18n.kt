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
    fun menuClose(lang: AppLanguage) = if (lang == AppLanguage.KO) "닫기" else "Close"

    // Menu: Analyze
    fun menuAnalyze(lang: AppLanguage) = if (lang == AppLanguage.KO) "분석" else "Analyze"
    fun menuDumpStructure(lang: AppLanguage) = if (lang == AppLanguage.KO) "구조 덤프..." else "Dump Structure..."
    fun menuCheckStructure(lang: AppLanguage) = if (lang == AppLanguage.KO) "구조 결함 검사..." else "Check Structure..."
    fun menuGenerateAiPrompt(lang: AppLanguage) = if (lang == AppLanguage.KO) "AI 진단 프롬프트 생성..." else "Generate AI Prompt..."
    fun menuGenerateAiPromptAndCopy(lang: AppLanguage) = if (lang == AppLanguage.KO) "AI 프롬프트 생성 및 클립보드 복사" else "Generate AI Prompt & Copy"

    // Menu: Motion Photo
    fun menuMotionPhoto(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토" else "Motion Photo"
    fun menuExtractMotionVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 동영상 추출" else "Extract Motion Photo Video"
    fun menuExtractPreviewVideo(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 미리보기 재생용 비디오 추출" else "Extract Preview Video"
    fun menuMotionFrameDropAnalysis(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 동영상 프레임 드랍 분석" else "Motion Video Frame Drop Analysis"

    // Menu: Bitstream
    fun menuBitstream(lang: AppLanguage) = if (lang == AppLanguage.KO) "비트스트림 추출" else "Extract Bitstream"
    fun menuExtractVideoTrack(lang: AppLanguage) = if (lang == AppLanguage.KO) "비디오 추출 (.mp4 or .mov etc)" else "Extract Video (.mp4 or .mov etc)"
    fun menuExtractAudioTrack(lang: AppLanguage) = if (lang == AppLanguage.KO) "오디오 추출 (.m4a)" else "Extract Audio (.m4a)"

    // Menu: Frame Interval
    fun menuFrameInterval(lang: AppLanguage) = if (lang == AppLanguage.KO) "프레임 간격 분석" else "Frame Intervals"
    fun menuViewFrameIntervals(lang: AppLanguage) = if (lang == AppLanguage.KO) "프레임 간격 분석 보기" else "View Frame Interval Analysis"

    // Menu: Quality Compare
    fun menuQualityCompare(lang: AppLanguage) = if (lang == AppLanguage.KO) "품질 비교" else "Quality Compare"
    fun menuOpenQualityCompare(lang: AppLanguage) = if (lang == AppLanguage.KO) "품질 비교 열기" else "Open Quality Compare"

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
    fun toastSaved(lang: AppLanguage, filename: String) = if (lang == AppLanguage.KO) "저장됨: $filename" else "Saved: $filename"
    fun toastExtractionFailed(lang: AppLanguage) = if (lang == AppLanguage.KO) "트랙 추출 실패" else "Track extraction failed"

    // Empty state
    fun placeholderEmptyState(lang: AppLanguage) = if (lang == AppLanguage.KO) "📂 파일들을 끌어다 놓거나 클릭하여 열기 (다중 파일 지원)" else "📂 Drag & Drop or Click to Open (Multiple Files Supported)"
}
