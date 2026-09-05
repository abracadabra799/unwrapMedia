package com.multiviewer.ui

import java.util.prefs.Preferences

enum class WebAiService(val displayName: String, val defaultUrl: String) {
    CHATGPT("ChatGPT", "https://chatgpt.com"),
    CLAUDE("Claude", "https://claude.ai/new"),
    GEMINI("Gemini", "https://gemini.google.com/app");

    val prefKey: String
        get() = "ai_web_url_${name.lowercase()}"
}

object AiWebPreferences {
    private val prefs: Preferences = Preferences.userNodeForPackage(AiWebPreferences::class.java)

    fun getUrl(service: WebAiService): String {
        return prefs.get(service.prefKey, service.defaultUrl).ifBlank { service.defaultUrl }
    }

    fun setUrl(service: WebAiService, url: String) {
        if (url.isBlank() || url.trim() == service.defaultUrl) {
            prefs.remove(service.prefKey)
        } else {
            prefs.put(service.prefKey, url.trim())
        }
    }

    fun resetUrl(service: WebAiService) {
        prefs.remove(service.prefKey)
    }
}
