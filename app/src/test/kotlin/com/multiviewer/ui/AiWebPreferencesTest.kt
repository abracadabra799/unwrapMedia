package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiWebPreferencesTest {

    @Test
    fun testDefaultUrls() {
        assertEquals("https://chatgpt.com", WebAiService.CHATGPT.defaultUrl)
        assertEquals("https://claude.ai/new", WebAiService.CLAUDE.defaultUrl)
        assertEquals("https://gemini.google.com/app", WebAiService.GEMINI.defaultUrl)
    }

    @Test
    fun testCustomUrlPersistenceAndReset() {
        val original = AiWebPreferences.getUrl(WebAiService.GEMINI)
        try {
            val customCompanyUrl = "https://gemini.company-internal.net"
            AiWebPreferences.setUrl(WebAiService.GEMINI, customCompanyUrl)
            assertEquals(customCompanyUrl, AiWebPreferences.getUrl(WebAiService.GEMINI))

            AiWebPreferences.resetUrl(WebAiService.GEMINI)
            assertEquals(WebAiService.GEMINI.defaultUrl, AiWebPreferences.getUrl(WebAiService.GEMINI))
        } finally {
            AiWebPreferences.setUrl(WebAiService.GEMINI, original)
        }
    }
}
