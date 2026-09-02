package com.multiviewer.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class I18nTest {

    @Test
    fun `menu strings return Korean and English correctly`() {
        // File
        assertEquals("파일", I18n.menuFile(AppLanguage.KO))
        assertEquals("File", I18n.menuFile(AppLanguage.EN))
        assertEquals("열기", I18n.menuOpen(AppLanguage.KO))
        assertEquals("Open", I18n.menuOpen(AppLanguage.EN))

        // Analyze
        assertEquals("분석", I18n.menuAnalyze(AppLanguage.KO))
        assertEquals("Analyze", I18n.menuAnalyze(AppLanguage.EN))
        assertEquals("구조 덤프", I18n.menuDumpStructure(AppLanguage.KO))
        assertEquals("Dump Structure", I18n.menuDumpStructure(AppLanguage.EN))
        assertEquals("구조 결함 검사", I18n.menuCheckStructure(AppLanguage.KO))
        assertEquals("Check Structure", I18n.menuCheckStructure(AppLanguage.EN))
        assertEquals("AI 진단 프롬프트 생성", I18n.menuGenerateAiPrompt(AppLanguage.KO))
        assertEquals("Generate AI Prompt", I18n.menuGenerateAiPrompt(AppLanguage.EN))

        // Motion Photo
        assertEquals("모션포토", I18n.menuMotionPhoto(AppLanguage.KO))
        assertEquals("Motion Photo", I18n.menuMotionPhoto(AppLanguage.EN))

        // View
        assertEquals("보기", I18n.menuView(AppLanguage.KO))
        assertEquals("View", I18n.menuView(AppLanguage.EN))
        assertEquals("다크 테마", I18n.menuDarkTheme(AppLanguage.KO))
        assertEquals("Dark Theme", I18n.menuDarkTheme(AppLanguage.EN))
    }

    @Test
    fun `saveLanguage and loadLanguage persist selected language`() {
        val initial = loadLanguage()
        try {
            saveLanguage(AppLanguage.EN)
            assertEquals(AppLanguage.EN, loadLanguage())

            saveLanguage(AppLanguage.KO)
            assertEquals(AppLanguage.KO, loadLanguage())
        } finally {
            saveLanguage(initial)
        }
    }
}
